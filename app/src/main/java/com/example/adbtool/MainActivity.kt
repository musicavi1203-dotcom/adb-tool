package com.example.adbtool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AdbToolTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        MainScreen(AdbController(applicationContext))
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen(adb: AdbController) {
    val ctx = LocalContext.current
    var deviceCount by remember { mutableIntStateOf(adb.attachedUsbDeviceCount()) }
    var pending by remember { mutableStateOf<RebootTarget?>(null) }
    var command by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }

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

        // כרטיס סטטוס
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = if (deviceCount > 0) {
                        stringResource(R.string.status_device_found)
                    } else {
                        stringResource(R.string.status_no_device)
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.status_adb_pending),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { deviceCount = adb.attachedUsbDeviceCount() },
                    shape = RoundedCornerShape(50)
                ) {
                    Text(stringResource(R.string.btn_refresh))
                }
            }
        }

        // הפעלה מחדש
        Text(
            text = stringResource(R.string.section_reboot),
            style = MaterialTheme.typography.titleMedium
        )
        rebootButtons.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { (target, labelRes) ->
                    val destructive = target == RebootTarget.BOOTLOADER ||
                            target == RebootTarget.FASTBOOT
                    Button(
                        onClick = { pending = target },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(50),
                        colors = if (destructive) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        }
                    ) {
                        Text(stringResource(labelRes))
                    }
                }
            }
        }

        // פקודת shell
        Text(
            text = stringResource(R.string.section_shell),
            style = MaterialTheme.typography.titleMedium
        )
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
                output = adb.runShell(command).fold(
                    { it },
                    { it.message ?: "" }
                )
            },
            enabled = command.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(50)
        ) {
            Text(stringResource(R.string.btn_run))
        }

        if (output.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(
                    text = output,
                    modifier = Modifier.padding(16.dp),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    // דיאלוג אישור לפני כל הפעלה מחדש
    pending?.let { target ->
        AlertDialog(
            onDismissRequest = { pending = null },
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.confirm_title)) },
            text = { Text(stringResource(R.string.confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    output = adb.reboot(target).fold(
                        { ctx.getString(R.string.reboot_sent) },
                        { it.message ?: "" }
                    )
                    pending = null
                }) {
                    Text(stringResource(R.string.btn_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}
