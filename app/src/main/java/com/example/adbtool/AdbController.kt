package com.example.adbtool

import android.content.Context
import android.hardware.usb.UsbManager

enum class RebootTarget { SYSTEM, RECOVERY, BOOTLOADER, FASTBOOT }

/**
 * שכבת ADB. בשלב הזה: זיהוי מכשיר USB בלבד.
 * פרוטוקול ADB מלא (CONNECT, OPEN, WRITE) יתווסף בשלב הבא.
 */
class AdbController(private val context: Context) {

    fun attachedUsbDeviceCount(): Int {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return usb.deviceList.size
    }

    fun reboot(target: RebootTarget): Result<Unit> =
        Result.failure(IllegalStateException(context.getString(R.string.err_not_connected)))

    fun runShell(command: String): Result<String> =
        Result.failure(IllegalStateException(context.getString(R.string.err_not_connected)))
}
