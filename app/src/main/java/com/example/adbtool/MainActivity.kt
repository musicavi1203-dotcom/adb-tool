package com.example.adbtool

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AdbToolTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        MainScreen()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val adb = remember { AdbController(ctx.applicationContext) }
    val fastboot = remember { FastbootController(ctx.applicationContext) }
    val scope = rememberCoroutineScope()

    // מצב ADB
    var info by remember { mutableStateOf<DeviceInfo?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<RebootTarget?>(null) }
    var command by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }

    // מצב Fastboot
    var fbInfo by remember { mutableStateOf<FastbootInfo?>(null) }
    var fbStatus by remember { mutableStateOf<String?>(null) }
    var fbBusy by remember { mutableStateOf(false) }
    var fbPartition by remember { mutableStateOf("boot") }
    var fbSlot by remember { mutableStateOf(SlotChoice.AUTO) }
    var fbFileUri by remember { mutableStateOf<Uri?>(null) }
    var fbFileName by remember { mutableStateOf<String?>(null) }
    var fbProgress by remember { mutableStateOf(-1) }
    var fbOutput by remember { mutableStateOf("") }
    var fbFlashCheck by remember { mutableStateOf<FlashCheck?>(null) }
    var fbPendingFlash by remember { mutableStateOf(false) }
    var fbCmd by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fbFileUri = uri
        fbFileName = uri?.lastPathSegment?.substringAfterLast('/')
    }

    // שמירת קובץ שנשאב
    var fetchBytes by remember { mutableStateOf<ByteArray?>(null) }
    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = fetchBytes
        if (uri != null && bytes != null) {
            scope.launch {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "boot.img"
                    fbOutput += ctx.getString(R.string.fetch_success, bytes.size / 1024, name) + "\n"
                } catch (e: Exception) {
                    fbOutput += ctx.getString(R.string.err_fetch_save) + ": ${e.message}\n"
                } finally {
                    fetchBytes = null
                    fbBusy = false
                }
            }
        } else {
            fetchBytes = null
            fbBusy = false
        }
    }

    val rebootButtons = listOf(
        RebootTarget.SYSTEM to R.string.btn_system,
        RebootTarget.RECOVERY to R.string.btn_recovery,
        RebootTarget.BOOTLOADER to R.string.btn_bootloader,
        RebootTarget.FASTBOOT to R.string.btn_fastboot
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // ───── כרטיס ADB ─────
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = status ?: stringResource(R.string.status_no_device),
                    style = MaterialTheme.typography.titleMedium
                )
                info?.let { d ->
                    Spacer(Modifier.height(8.dp))
                    InfoLine(stringResource(R.string.label_model), d.model)
                    InfoLine(stringResource(R.string.label_android), d.android)
                    InfoLine(stringResource(R.string.label_chipset), d.chipset)
                    InfoLine(stringResource(R.string.label_build), d.build)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        busy = true
                        status = ctx.getString(R.string.status_connecting)
                        scope.launch {
                            adb.connect()
                                .onSuccess { info = it; status = ctx.getString(R.string.status_connected) }
                                .onFailure { info = null; status = it.message ?: ctx.getString(R.string.err_adb) }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    shape = RoundedCornerShape(50)
                ) { Text(stringResource(R.string.btn_connect)) }
            }
        }

        // ───── הפעלה מחדש ─────
        Text(stringResource(R.string.section_reboot), style = MaterialTheme.typography.titleMedium)
        rebootButtons.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (target, labelRes) ->
                    val destructive = target == RebootTarget.BOOTLOADER || target == RebootTarget.FASTBOOT
                    Button(
                        onClick = { pending = target },
                        enabled = info != null,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(50),
                        colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                 else ButtonDefaults.buttonColors()
                    ) { Text(stringResource(labelRes)) }
                }
            }
        }

        // ───── פקודת shell ─────
        Text(stringResource(R.string.section_shell), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.hint_shell)) }
        )
        Button(
            onClick = {
                scope.launch {
                    adb.runShell(command)
                        .onSuccess { output = it }
                        .onFailure { output = it.message ?: "" }
                }
            },
            enabled = info != null && command.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(50)
        ) { Text(stringResource(R.string.btn_run)) }

        if (output.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Text(text = output, modifier = Modifier.padding(16.dp), fontFamily = FontFamily.Monospace)
            }
        }

        // ───── Fastboot ─────
        Text(stringResource(R.string.section_fastboot), style = MaterialTheme.typography.titleMedium)

        // כרטיס 1: חיבור ויעד
        FbCard(title = stringResource(R.string.section_connection)) {
            Text(
                text = fbStatus ?: stringResource(R.string.fb_disconnected),
                style = MaterialTheme.typography.bodyMedium
            )
            fbInfo?.let { i ->
                Text(
                    text = stringResource(R.string.fb_details, i.unlocked, i.slot),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            OutlinedButton(
                onClick = {
                    fbBusy = true
                    scope.launch {
                        if (fbInfo != null) {
                            fastboot.disconnect()
                            fbInfo = null
                            fbStatus = null
                        } else {
                            fastboot.connect()
                                .onSuccess { i ->
                                    fbInfo = i
                                    fbStatus = ctx.getString(R.string.fb_connected_product, i.product)
                                }
                                .onFailure { fbInfo = null; fbStatus = it.message }
                        }
                        fbBusy = false
                    }
                },
                enabled = !fbBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    if (fbInfo != null) stringResource(R.string.btn_fb_disconnect)
                    else stringResource(R.string.btn_fb_connect)
                )
            }

            OutlinedTextField(
                value = fbPartition,
                onValueChange = { fbPartition = it },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.label_partition)) }
            )

            Text(stringResource(R.string.label_slot), style = MaterialTheme.typography.bodySmall)
            val slots = SlotChoice.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                slots.forEachIndexed { index, choice ->
                    SegmentedButton(
                        selected = fbSlot == choice,
                        onClick = { fbSlot = choice },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = slots.size)
                    ) {
                        Text(
                            when (choice) {
                                SlotChoice.AUTO -> stringResource(R.string.slot_auto)
                                SlotChoice.A -> "a"
                                SlotChoice.B -> "b"
                            }
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    fbBusy = true
                    scope.launch {
                        fastboot.reboot()
                        fbInfo = null
                        fbStatus = ctx.getString(R.string.fb_rebooting)
                        fbBusy = false
                    }
                },
                enabled = fbInfo != null && !fbBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50)
            ) { Text(stringResource(R.string.btn_fb_reboot)) }
        }

        // כרטיס 2: שאיבה
        FbCard(title = stringResource(R.string.section_fetch), hint = stringResource(R.string.fetch_hint)) {
            Button(
                onClick = {
                    fbBusy = true
                    fbProgress = 0
                    fbOutput = ""
                    scope.launch {
                        fastboot.fetchPartition(
                            baseName = fbPartition,
                            slot = fbSlot,
                            onProgress = { p -> scope.launch { fbProgress = p } },
                            onLog = { line -> scope.launch { fbOutput += line + "\n" } }
                        ).onSuccess { bytes ->
                            fetchBytes = bytes
                            saveFileLauncher.launch("${fbPartition}.img")
                        }.onFailure { e ->
                            val msg = e.message ?: ""
                            fbOutput += (if (msg.contains("fetch")) ctx.getString(R.string.err_fetch_unsupported) else msg) + "\n"
                            fbProgress = -1
                            fbBusy = false
                        }
                    }
                },
                enabled = fbInfo != null && fbPartition.isNotBlank() && !fbBusy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(50)
            ) { Text(stringResource(R.string.btn_fetch)) }
        }

        // כרטיס 3: צריבה
        FbCard(title = stringResource(R.string.section_flash), hint = stringResource(R.string.flash_hint)) {
            OutlinedButton(
                onClick = { filePicker.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50)
            ) {
                Text(fbFileName ?: stringResource(R.string.btn_pick_file))
            }

            Button(
                onClick = {
                    val uri = fbFileUri ?: return@Button
                    fbBusy = true
                    scope.launch {
                        fastboot.checkFlash(fbPartition, fbSlot, uri)
                            .onSuccess { fbFlashCheck = it; fbPendingFlash = true }
                            .onFailure { fbOutput += (it.message ?: "") + "\n" }
                        fbBusy = false
                    }
                },
                enabled = fbInfo != null && fbFileUri != null && fbPartition.isNotBlank() && !fbBusy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text(stringResource(R.string.btn_flash)) }
        }

        // כרטיס 4: בדיקה
        FbCard(title = stringResource(R.string.section_check), hint = stringResource(R.string.check_hint)) {
            OutlinedButton(
                onClick = {
                    fbBusy = true
                    fbOutput = ""
                    scope.launch {
                        fastboot.getVarAll(onLog = { line -> scope.launch { fbOutput += line + "\n" } })
                            .onFailure { fbOutput += (it.message ?: "") + "\n" }
                        fastboot.partitionInfo(fbPartition, fbSlot)
                            .onSuccess { (target, size) -> fbOutput += "מחיצה $target: ${fmtMb(size)}\n" }
                            .onFailure { fbOutput += (it.message ?: "") + "\n" }
                        fbBusy = false
                    }
                },
                enabled = fbInfo != null && !fbBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50)
            ) { Text(stringResource(R.string.btn_check)) }

            OutlinedTextField(
                value = fbCmd,
                onValueChange = { fbCmd = it },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.label_free_cmd)) }
            )
            Button(
                onClick = {
                    val cmd = fbCmd.trim()
                    fbBusy = true
                    fbOutput += "> $cmd\n"
                    scope.launch {
                        fastboot.runCommand(cmd, onLog = { line -> scope.launch { fbOutput += line + "\n" } })
                            .onSuccess { fbOutput += it + "\n" }
                            .onFailure { fbOutput += (it.message ?: "") + "\n" }
                        fbBusy = false
                    }
                },
                enabled = fbInfo != null && fbCmd.isNotBlank() && !fbBusy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(50)
            ) { Text(stringResource(R.string.btn_run)) }
        }

        // יומן ונתוני התקדמות משותפים לכל הכרטיסים
        if (fbProgress in 0..100) {
            LinearProgressIndicator(
                progress = { fbProgress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Text("$fbProgress%", style = MaterialTheme.typography.bodySmall)
        }
        if (fbOutput.isNotEmpty()) {
            FbCard(title = stringResource(R.string.section_log)) {
                Text(
                    text = fbOutput,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    // דיאלוג אישור reboot
    pending?.let { target ->
        AlertDialog(
            onDismissRequest = { pending = null },
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.confirm_title)) },
            text = { Text(stringResource(R.string.confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        adb.reboot(target)
                            .onSuccess { output = ctx.getString(R.string.reboot_sent); info = null; status = null }
                            .onFailure { output = it.message ?: "" }
                    }
                }) { Text(stringResource(R.string.btn_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    // דיאלוג אישור צריבה: מציג מחיצה וגודלים, וחוסם אם הקובץ גדול מהמחיצה
    val check = fbFlashCheck
    val flashUri = fbFileUri
    if (fbPendingFlash && check != null && flashUri != null) {
        val tooBig = check.partitionSize != null && check.fileSize > check.partitionSize
        AlertDialog(
            onDismissRequest = { fbPendingFlash = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.flash_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.flash_check_body,
                            check.target,
                            fmtMb(check.partitionSize),
                            fbFileName ?: "",
                            fmtMb(check.fileSize)
                        )
                    )
                    if (tooBig) {
                        Text(
                            stringResource(R.string.err_flash_too_big),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !tooBig,
                    onClick = {
                        fbPendingFlash = false
                        fbBusy = true
                        fbProgress = 0
                        fbOutput = ""
                        scope.launch {
                            fastboot.flash(
                                baseName = fbPartition,
                                slot = fbSlot,
                                fileUri = flashUri,
                                onProgress = { p -> scope.launch { fbProgress = p } },
                                onLog = { line -> scope.launch { fbOutput += line + "\n" } }
                            )
                                .onSuccess { target -> fbOutput += ctx.getString(R.string.flash_success, target) + "\n" }
                                .onFailure { fbOutput += (it.message ?: "") + "\n" }
                            fbProgress = -1
                            fbBusy = false
                        }
                    }
                ) { Text(stringResource(R.string.btn_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { fbPendingFlash = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }
}

@Composable
private fun FbCard(title: String, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (hint != null) {
                Text(text = hint, style = MaterialTheme.typography.bodySmall)
            }
            content()
        }
    }
}

private fun fmtMb(bytes: Long?): String =
    if (bytes == null) "לא ידוע" else "%.1f MB".format(bytes / 1048576.0)

@Composable
private fun InfoLine(label: String, value: String) {
    Text(text = "$label: $value", style = MaterialTheme.typography.bodyMedium)
}
