package app.aaps.ui.elvin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.elvin.ElvinChannel
import app.aaps.core.interfaces.elvin.ElvinMonitor
import app.aaps.core.keys.LongComposedKey
import app.aaps.core.keys.elvin.*
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.LocalConfig
import app.aaps.core.ui.compose.LocalPreferences
import kotlinx.coroutines.launch

/** The only custom settings UI. Uses registered AAPS keys, so encrypted export/import includes EI. */
class ElvinSettingsScreen(private val monitor: ElvinMonitor) : ComposeScreenContent {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content(onBack: () -> Unit) {
        val preferences = LocalPreferences.current
        val config = LocalConfig.current
        val scope = rememberCoroutineScope()
        var diagnosticsPage by remember { mutableStateOf(false) }
        var confirmReset by remember { mutableStateOf(false) }
        var resetGeneration by remember { mutableStateOf(0) }
        val diagnosticText by monitor.diagnostics.collectAsState()
        val expiration = preferences.get(LongComposedKey.AppExpiration, config.VERSION_NAME)
        Scaffold(topBar = {
            AapsTopAppBar(title = { Text(if (diagnosticsPage) "Elvin Connection Diagnostics" else "Elvin Settings") },
                navigationIcon = { TextButton(onClick = { if (diagnosticsPage) diagnosticsPage = false else onBack() }) { Text("Back") } })
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("4.0.0-EI.1 · Android experimental custom monitoring", style = MaterialTheme.typography.titleMedium)
                    Text("Existing AAPS alarms and insulin safety constraints remain active. Timing and audibility require physical-device validation.")
                }
                if (diagnosticsPage) {
                    item {
                        TextButton(onClick = { scope.launch { monitor.tick() } }) { Text("Refresh diagnostics") }
                        TextButton(onClick = monitor::exportDiagnostics) { Text("Export diagnostic log (no credentials)") }
                        Text(diagnosticText)
                    }
                    item {
                        Text("Allowlisted observations only. Pump attempt counts cover AAPS status reads, not every Bluetooth transaction. Zero timestamps mean not observed, not success.")
                    }
                } else {
                    item {
                        TextButton(onClick = { diagnosticsPage = true; scope.launch { monitor.tick() } }) { Text("Connection diagnostics and alarm history") }
                    }
                    ElvinBooleanKey.entries.forEach { setting ->
                        item(key = setting.key) {
                            val checked by preferences.observe(setting).collectAsState()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text((setting.title as app.aaps.core.keys.interfaces.TextRef.Literal).text, Modifier.weight(1f))
                                Switch(checked = checked, onCheckedChange = {
                                    preferences.put(setting, it)
                                    scope.launch { monitor.tick() }
                                })
                            }
                        }
                    }
                    ElvinIntKey.entries.filter { it != ElvinIntKey.Schema }.forEach { setting ->
                        item(key = setting.key) {
                            var text by remember(setting, resetGeneration) { mutableStateOf(preferences.get(setting).toString()) }
                            val number = text.toIntOrNull()
                            val valid = number != null && number in setting.min..setting.max
                            OutlinedTextField(value = text, onValueChange = { value ->
                                text = value
                                value.toIntOrNull()?.takeIf { it in setting.min..setting.max }?.let { preferences.put(setting, it) }
                            }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                label = { Text((setting.title as app.aaps.core.keys.interfaces.TextRef.Literal).text) },
                                supportingText = { Text("${setting.min}–${setting.max}; valid edits save immediately") },
                                isError = !valid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                    }
                    item {
                        Text("Volume is a percentage of the selected Android audio stream. The OS stream volume and DND profile still apply; EI does not force global volume to maximum.")
                        ElvinChannel.entries.forEach { channel ->
                            Row {
                                TextButton(onClick = { monitor.test(channel) }) { Text("Test $channel alarm (30 seconds)") }
                                TextButton(onClick = { monitor.snooze(channel) }) { Text("Snooze $channel") }
                            }
                        }
                        TextButton(onClick = monitor::stopTest) { Text("Stop custom audio") }
                    }
                    item {
                        Text("Permissions and background operation", style = MaterialTheme.typography.titleMedium)
                        listOf("notifications", "dnd", "battery", "bluetooth").forEach { kind ->
                            TextButton(onClick = { monitor.openPermissionSettings(kind) }) { Text("Open $kind settings") }
                        }
                        Text("Grant permissions through Android. Battery exemption improves availability but cannot guarantee timing. After force-stop, reopen AAPS. WorkManager handles normal reboot rescheduling.")
                    }
                    item {
                        Text("Update management", style = MaterialTheme.typography.titleMedium)
                        Text("Known expiration for the current official version: ${if (expiration > 0) kotlin.time.Instant.fromEpochMilliseconds(expiration).toString() else "not currently recorded; this does not establish that no restriction exists"}")
                        Text("Routine newer-version reminders are informational. Version expiration can restrict maximum IOB; signature, objectives and hard limits have separate safety roles. EI reminder snoozing never changes these restrictions.")
                        TextButton(onClick = monitor::snoozeRoutineUpdate) { Text("Snooze routine update reminder only (Elvin enabled)") }
                    }
                    item {
                        Text("Settings backup and import", style = MaterialTheme.typography.titleMedium)
                        Text("Elvin settings are included in standard AAPS encrypted settings export/import. Use Maintenance for export, import and cloud backup. Device runtime, snoozes and diagnostics are excluded. No separate unencrypted settings export is created.")
                        TextButton(onClick = { confirmReset = true }) { Text("Reset only Elvin Settings") }
                    }
                    item {
                        Text("EI.1 changelog", style = MaterialTheme.typography.titleMedium)
                        Text("Added opt-in communication monitoring, separate timeouts/repeats/snoozes, audio previews and diagnostics. Live glucose targets, SMB, basal calculations, pump commands and safety restrictions are unchanged. Offline research tools live in tools/elvin-research.")
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
        if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("Reset Elvin Settings?") },
            text = { Text("Custom monitoring will be disabled. Standard AAPS settings will stay unchanged.") },
            confirmButton = { TextButton(onClick = { monitor.reset(); resetGeneration++; confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } })
    }
}
