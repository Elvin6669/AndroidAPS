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
import app.aaps.core.interfaces.elvin.ElvinSampleSource
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
        var page by remember { mutableStateOf("settings") }
        var settingsRevision by remember { mutableStateOf(0) }
        var confirmReset by remember { mutableStateOf(false) }
        var resetGeneration by remember { mutableStateOf(0) }
        val diagnosticText by monitor.diagnostics.collectAsState()
        val profileText by monitor.profileHistory.collectAsState()
        val maintenanceText by monitor.maintenanceStatus.collectAsState()
        val customEnabled by preferences.observe(ElvinBooleanKey.Enabled).collectAsState()
        val expiration = preferences.get(LongComposedKey.AppExpiration, config.VERSION_NAME)
        Scaffold(topBar = {
            AapsTopAppBar(title = { Text(when (page) { "diagnostics" -> "Elvin Connection Diagnostics"; "profiles" -> "Elvin Profile History"; else -> "Elvin Settings" }) },
                navigationIcon = { TextButton(onClick = { if (page != "settings") page = "settings" else onBack() }) { Text("Back") } })
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("4.0.0-EI.2 · Android experimental custom monitoring", style = MaterialTheme.typography.titleMedium)
                    Text("Existing AAPS alarms and insulin safety constraints remain active. Timing and audibility require physical-device validation.")
                }
                if (page != "settings") {
                    item {
                        if (page == "profiles") TextButton(onClick = { scope.launch { monitor.refreshProfiles() } }) { Text("Refresh profile history") }
                        else TextButton(onClick = { scope.launch { monitor.tick(ElvinSampleSource.MANUAL) } }) { Text("Refresh diagnostics") }
                        if (page == "diagnostics") TextButton(onClick = monitor::exportDiagnostics) { Text("Export diagnostic log (no credentials)") }
                        Text(if (page == "profiles") profileText else diagnosticText + "\n" + maintenanceText)
                    }
                    item {
                        Text(if (page == "profiles") "Reads existing AAPS requested/effective profile records. This page cannot change a profile or insulin delivery."
                            else "Allowlisted observations only. Pump attempt counts cover AAPS status reads, not every Bluetooth transaction. Zero timestamps mean not observed, not success.")
                    }
                } else {
                    item {
                        TextButton(onClick = { page = "diagnostics"; scope.launch { monitor.tick(ElvinSampleSource.MANUAL) } }) { Text("Connection diagnostics and alarm history") }
                    }
                    item { TextButton(onClick = { page = "profiles"; scope.launch { monitor.refreshProfiles() } }) { Text("Profile change history (read-only)") } }
                    ElvinBooleanKey.entries.filter { it !in ElvinKeys.legacyBooleans }.forEach { setting ->
                        item(key = setting.key) {
                            val stored by preferences.observe(setting).collectAsState()
                            val checked = remember(stored, settingsRevision, resetGeneration) { ElvinAudioSettings.boolean(preferences, setting) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(setting.title.text, Modifier.weight(1f))
                                Switch(checked = checked, onCheckedChange = {
                                    preferences.put(setting, it)
                                    settingsRevision++
                                    scope.launch { monitor.tick(ElvinSampleSource.MANUAL) }
                                })
                            }
                        }
                    }
                    ElvinIntKey.entries.filter { it != ElvinIntKey.Schema && it !in ElvinKeys.legacyInts }.forEach { setting ->
                        item(key = setting.key) {
                            var text by remember(setting, resetGeneration) { mutableStateOf(ElvinAudioSettings.integer(preferences, setting).toString()) }
                            val number = text.toIntOrNull()
                            val valid = number != null && number in setting.min..setting.max
                            OutlinedTextField(value = text, onValueChange = { value ->
                                text = value
                                value.toIntOrNull()?.takeIf { it in setting.min..setting.max }?.let { preferences.put(setting, it)
                                    settingsRevision++ }
                            }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                label = { Text(setting.title.text) },
                                supportingText = { Text("${setting.min}–${setting.max}; valid edits save immediately") },
                                isError = !valid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                    }
                    item {
                        Text("Volume is a percentage of the selected Android audio stream. The OS stream volume and DND profile still apply; EI does not force global volume to maximum.")
                        ElvinChannel.entries.forEach { channel ->
                            Row {
                                TextButton(enabled = customEnabled, onClick = { monitor.test(channel) }) { Text("Test $channel alarm (30 seconds)") }
                                TextButton(enabled = customEnabled, onClick = { monitor.snooze(channel) }) { Text("Snooze $channel") }
                            }
                        }
                        TextButton(onClick = monitor::stopTest) { Text("Stop custom audio") }
                    }
                    item {
                        Text("Permissions and background operation", style = MaterialTheme.typography.titleMedium)
                        listOf("notifications", "dnd", "battery", "bluetooth", "audio").forEach { kind ->
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
                        Text("Automatic encrypted backups reuse AAPS encryption and destinations. Configure a master password, enable unattended export and cache its password using Maintenance first. An existing enabled Automation export schedule takes precedence to avoid duplicates. Device runtime and snoozes are excluded from settings backups.")
                        Text(maintenanceText)
                        TextButton(enabled = customEnabled, onClick = monitor::backupNow) { Text("Encrypted backup now") }
                        TextButton(onClick = { confirmReset = true }) { Text("Reset only Elvin Settings") }
                    }
                    item {
                        Text("EI.2 changelog", style = MaterialTheme.typography.titleMedium)
                        Text("EI.2: independent pump/CGM audio and recovery options; durable allowlisted history; upload-failure monitoring; read-only profile history; encrypted backup scheduling through existing AAPS storage. Live glucose targets, SMB, basal calculations, pump commands and safety restrictions are unchanged.")
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
