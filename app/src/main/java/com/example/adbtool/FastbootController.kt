package com.example.adbtool

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val ACTION_FASTBOOT_PERMISSION = "com.example.adbtool.FASTBOOT_PERMISSION"
private const val CMD_TIMEOUT = 10000
private const val LONG_TIMEOUT = 300000   // צריבה יכולה לקחת דקות
private const val WRITE_TIMEOUT = 30000
private const val CHUNK_SIZE = 512 * 1024
private const val CMD_MAX = 64

enum class SlotChoice { AUTO, A, B }

data class FastbootInfo(val product: String, val unlocked: String, val slot: String)

private class FbResponse(val code: String, val payload: String)

class FastbootController(private val context: Context) {

    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var transport: FastbootTransport? = null

    // מחבר למכשיר fastboot ראשון שנמצא
    suspend fun connect(): Result<FastbootInfo> = withContext(Dispatchers.IO) {
        disconnect()
        try {
            val device = usb.deviceList.values.firstOrNull { findFastbootInterface(it) != null }
                ?: throw IOException(context.getString(R.string.err_no_fastboot_device))
            if (!usb.hasPermission(device) && !requestPermission(device)) {
                throw IOException(context.getString(R.string.err_permission))
            }
            val iface = findFastbootInterface(device)
                ?: throw IOException(context.getString(R.string.err_no_fastboot_device))
            val conn = usb.openDevice(device)
                ?: throw IOException(context.getString(R.string.err_permission))
            val t = FastbootTransport(conn, iface)
            transport = t
            Result.success(
                FastbootInfo(
                    product = t.getVarOrDefault("product"),
                    unlocked = t.getVarOrDefault("unlocked"),
                    slot = t.getVarOrDefault("current-slot")
                )
            )
        } catch (e: Exception) {
            disconnect()
            Result.failure(e)
        }
    }

    // צורב קובץ למחיצה. מחזיר את שם המחיצה שנצרבה בפועל
    suspend fun flash(
        baseName: String,
        slot: SlotChoice,
        fileUri: Uri,
        onProgress: (Int) -> Unit,
        onLog: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val t = transport ?: return@withContext Result.failure(notConnected())
        try {
            val target = t.resolvePartition(baseName, slot)
            val bytes = context.contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
                ?: throw IOException(context.getString(R.string.err_file_open))
            t.download(bytes, onProgress, onLog)
            t.flash(target, onLog)
            Result.success(target)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // מאתחל את המכשיר מתוך fastboot. הניתוק אחרי reboot הוא צפוי
    suspend fun reboot(): Result<Unit> = withContext(Dispatchers.IO) {
        val t = transport ?: return@withContext Result.failure(notConnected())
        runCatching { t.sendCommand("reboot") }
        disconnect()
        Result.success(Unit)
    }

    fun disconnect() {
        transport?.close()
        transport = null
    }

    private fun notConnected() =
        IllegalStateException(context.getString(R.string.err_not_connected))

    private fun findFastbootInterface(device: UsbDevice): UsbInterface? =
        (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull {
            it.interfaceClass == 0xFF &&
                it.interfaceSubclass == 0x42 &&
                it.interfaceProtocol == 0x03
        }

    private suspend fun requestPermission(device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != ACTION_FASTBOOT_PERMISSION) return
                    runCatching { context.unregisterReceiver(this) }
                    if (cont.isActive) {
                        cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
            }
            context.registerReceiver(
                receiver,
                IntentFilter(ACTION_FASTBOOT_PERMISSION),
                Context.RECEIVER_NOT_EXPORTED
            )
            val pi = PendingIntent.getBroadcast(
                context, 1,
                Intent(ACTION_FASTBOOT_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(device, pi)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        }
}

// תקשורת fastboot ברמה נמוכה: פקודות טקסט עד 64 בתים מעל bulk endpoints
private class FastbootTransport(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface
) {
    private val epIn: UsbEndpoint
    private val epOut: UsbEndpoint

    init {
        conn.claimInterface(iface, true)
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
        }
        epIn = inEp ?: throw IOException("no bulk IN endpoint")
        epOut = outEp ?: throw IOException("no bulk OUT endpoint")
    }

    fun sendCommand(cmd: String) {
        write(cmd)
        readFinal(CMD_TIMEOUT) {}
    }

    fun getVar(name: String): String {
        write("getvar:$name")
        return readFinal(CMD_TIMEOUT) {}.payload.trim()
    }

    fun getVarOrDefault(name: String): String =
        runCatching { getVar(name) }.getOrDefault("?")

    // a/b: מוסיף סיומת slot. AUTO קורא את ה-slot הפעיל, ואם המכשיר לא A/B משאיר את השם כמו שהוא
    fun resolvePartition(base: String, slot: SlotChoice): String {
        val root = base.removeSuffix("_a").removeSuffix("_b")
        val suffix = when (slot) {
            SlotChoice.A -> "a"
            SlotChoice.B -> "b"
            SlotChoice.AUTO -> runCatching { getVar("current-slot") }.getOrNull()
                ?.takeIf { it.isNotBlank() }
        } ?: return root
        return "${root}_$suffix"
    }

    fun download(data: ByteArray, onProgress: (Int) -> Unit, onLog: (String) -> Unit) {
        write("download:%08x".format(data.size))
        val r = readFinal(CMD_TIMEOUT, onLog)
        if (r.code != "DATA") throw IOException("המכשיר דחה את ההורדה: ${r.payload}")

        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + CHUNK_SIZE, data.size)
            val chunk = data.copyOfRange(offset, end)
            val n = conn.bulkTransfer(epOut, chunk, chunk.size, WRITE_TIMEOUT)
            if (n < 0) throw IOException("כתיבה ל-USB נכשלה בבייט $offset")
            offset = end
            onProgress(offset * 100 / data.size)
        }

        if (readFinal(LONG_TIMEOUT, onLog).code != "OKAY") throw IOException("ההורדה למכשיר נכשלה")
    }

    fun flash(partition: String, onLog: (String) -> Unit) {
        write("flash:$partition")
        if (readFinal(LONG_TIMEOUT, onLog).code != "OKAY") throw IOException("הצריבה של $partition נכשלה")
    }

    // קורא עד תשובה סופית. INFO מועבר ללוג, FAIL הופך לחריגה
    private fun readFinal(timeout: Int, onLog: (String) -> Unit): FbResponse {
        while (true) {
            val buf = ByteArray(CMD_MAX)
            val n = conn.bulkTransfer(epIn, buf, buf.size, timeout)
            if (n < 0) throw IOException("אין תשובה מהמכשיר (timeout)")
            val s = String(buf, 0, n, Charsets.UTF_8)
            when {
                s.startsWith("INFO") -> onLog(s.substring(4).trim())
                s.startsWith("OKAY") -> return FbResponse("OKAY", s.substring(4))
                s.startsWith("DATA") -> return FbResponse("DATA", s.substring(4))
                s.startsWith("FAIL") -> throw IOException(s.substring(4).trim().ifBlank { "FAIL" })
                else -> throw IOException("תשובה לא צפויה: $s")
            }
        }
    }

    private fun write(cmd: String) {
        val data = cmd.toByteArray(Charsets.UTF_8)
        val n = conn.bulkTransfer(epOut, data, data.size, WRITE_TIMEOUT)
        if (n < 0) throw IOException("כתיבה ל-USB נכשלה")
    }

    fun close() {
        runCatching { conn.releaseInterface(iface) }
        conn.close()
    }
}
