package app.aaps.implementation.elvin

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.data.model.RM
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.elvin.*
import app.aaps.core.interfaces.notifications.*
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.elvin.*
import app.aaps.core.keys.elvin.CommunicationAlarmPolicy as Policy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import dev.zacsweers.metro.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class ElvinMonitorImpl(
    private val preferences: Preferences,
    private val activePlugin: () -> ActivePlugin,
    private val loop: () -> Loop,
    private val persistence: PersistenceLayer,
    private val date: DateUtil,
    private val config: Config,
    private val notifications: NotificationManager,
    private val platform: ElvinPlatform,
    private val maintenance: ElvinMaintenance? = null
) : ElvinMonitor {
    private val stateLock = AapsLock()
    private val tickMutex = Mutex()
    private val output = MutableStateFlow("Elvin monitor has not run yet. Android background timing is not guaranteed.")
    override val diagnostics: StateFlow<String> = output
    private val profilesOutput = MutableStateFlow("Press Refresh profile history to read existing AAPS records")
    override val profileHistory: StateFlow<String> = profilesOutput
    private val noMaintenance = MutableStateFlow("Maintenance adapter unavailable in this runtime")
    override val maintenanceStatus: StateFlow<String> get() = maintenance?.status ?: noMaintenance
    private data class History(val at: Long, val code: String, val channel: String)
    private val historyCodes = setOf("SAMPLE_PERIODIC", "SAMPLE_MANUAL", "ALARM", "RECOVERY", "SNOOZE")
    private val events = ArrayDeque<History>()
    private var lastTick = 0L
    private val states = mutableMapOf<ElvinChannel, Policy.State>()
    private var previouslyEnabled = false

    init {
        preferences.registerPreferences(ElvinKeys.all)
        platform.onSnooze(::snooze)
        // Malformed or future-schema runtime data is ignored, never used as imported alarm state.
        runCatching {
            val json = Json.parseToJsonElement(preferences.get(ElvinRuntimeKey.State)).jsonObject
            if ((json["schema"]?.jsonPrimitive?.intOrNull ?: 0) in 1..2) {
                ElvinChannel.entries.forEach { channel ->
                    json[channel.name]?.jsonObject?.let { v ->
                        fun number(k: String) = v[k]?.jsonPrimitive?.longOrNull ?: 0L
                        states[channel] = Policy.State(number("watch"), number("alarm"), number("snooze"),
                            v["active"]?.jsonPrimitive?.booleanOrNull ?: false, number("outage"))
                    }
                }
                lastTick = json["lastPeriodic"]?.jsonPrimitive?.longOrNull ?: 0
                json["history"]?.jsonArray?.takeLast(100)?.forEach { item ->
                    val entry = item.jsonObject
                    val code = entry["code"]?.jsonPrimitive?.content ?: ""
                    val channel = entry["channel"]?.jsonPrimitive?.content ?: ""
                    val at = entry["at"]?.jsonPrimitive?.longOrNull ?: 0
                    if (code in historyCodes && (channel.isEmpty() || ElvinChannel.entries.any { it.name == channel }) && at > 0)
                        events.addLast(History(at, code, channel))
                }
            }
        }
        previouslyEnabled = states.values.any { it.watchStarted > 0 || it.active || it.snoozedUntil > 0 }
    }

    private fun int(key: ElvinIntKey) = preferences.get(key).coerceIn(key.min, key.max)
    private inline fun <T> diagnosticRead(block: () -> T): T? = try { block() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    private fun id(channel: ElvinChannel) = if (channel == ElvinChannel.PUMP) NotificationId.ELVIN_PUMP_LOSS else NotificationId.ELVIN_CGM_LOSS
    private fun sound(channel: ElvinChannel): AlarmSound = listOf(AlarmSound.ALARM, AlarmSound.URGENT_ALARM, AlarmSound.ERROR, AlarmSound.BOLUS_ERROR)[
        int(if (channel == ElvinChannel.PUMP) ElvinIntKey.PumpSound else ElvinIntKey.CgmSound)]
    private fun record(code: String, channel: ElvinChannel? = null) {
        events.addLast(History(date.now(), code, channel?.name ?: ""))
        while (events.size > 100) events.removeFirst()
    }

    override suspend fun tick(source: ElvinSampleSource) {
        // This hook must never prevent the existing AAPS alarm/maintenance path from running.
        try { tickMutex.withLock { evaluate(source) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            ElvinChannel.entries.forEach { runCatching { platform.stop(it) } }
            output.value = "Custom monitor failed (${e::class.simpleName}); official AAPS alarms remain unchanged. No exception contents are exported."
        }
        // Export/cloud IO runs on a separate adapter job and cannot delay communication checks.
        runCatching { maintenance?.onSample(source == ElvinSampleSource.PERIODIC) }
    }

    private suspend fun evaluate(source: ElvinSampleSource) {
        val now = date.now()
        val enabled = preferences.get(ElvinBooleanKey.Enabled) && platform.supported && config.APS
        val sample = enabled || (source == ElvinSampleSource.MANUAL && platform.supported && config.APS)
        // An unselected/unavailable pump must not prevent independent CGM monitoring.
        val pump = if (sample) diagnosticRead { activePlugin().activePump } else null
        // The concentration wrapper implements Pump's default context. Read the selected driver's
        // diagnostic getter explicitly; do not alter the concentration/delivery wrapper.
        val pumpContext = diagnosticRead { pump?.selectedActivePump()?.elvinCommunicationContext }
        val mode = if (sample && pump != null) diagnosticRead { loop().runningMode() } else null
        val disconnected = mode == RM.Mode.DISCONNECTED_PUMP
        val pumpTime = diagnosticRead { pump?.lastDataTime?.value }
        val lastPump = pumpTime?.takeIf { it > 0 }
        val suspended = diagnosticRead { pump?.isSuspended() }
        val configured = diagnosticRead { pump?.isConfigured() }
        var cgmAvailable = true
        val lastCgm = if (sample) try { persistence.getLastGlucoseValue()?.timestamp }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { cgmAvailable = false; null } else null
        val status = platform.status()
        stateLock.withLock {
            // A reset/disable may have happened while persistence or running-mode lookup suspended.
            val enabledNow = enabled && preferences.get(ElvinBooleanKey.Enabled)
            val summary = mutableListOf("Elvin 4.0.0-EI.2; enabled=$enabled", "Monitor sample: $now; source=$source; last periodic sample=$lastTick; periodic gap(ms)=${if (lastTick > 0) now - lastTick else 0}")
            if (sample) record("SAMPLE_$source")
            if (source == ElvinSampleSource.PERIODIC) lastTick = now
            ElvinChannel.entries.forEach { channel ->
                val pumpChannel = channel == ElvinChannel.PUMP
                val last = if (pumpChannel) lastPump else lastCgm
                val policy = Policy.Settings(enabledNow && preferences.get(if (pumpChannel) ElvinBooleanKey.PumpEnabled else ElvinBooleanKey.CgmEnabled),
                    int(if (pumpChannel) ElvinIntKey.PumpTimeout else ElvinIntKey.CgmTimeout),
                    int(if (pumpChannel) ElvinIntKey.PumpRepeat else ElvinIntKey.CgmRepeat))
                val decision = Policy.evaluate(policy, states[channel] ?: Policy.State(), Policy.Observation(now, last,
                    intentionalDisconnect = pumpChannel && disconnected,
                    configured = !pumpChannel || configured == true,
                    suspended = pumpChannel && suspended == true,
                    terminalPodState = pumpChannel && pumpContext?.terminal == true,
                    failedAttemptConfirmed = if (pumpChannel) ElvinTelemetry.pump.value.let {
                        it.consecutiveFailures > 0 && it.lastAttempt > (lastPump ?: 0L)
                    } else null, inputsAvailable = if (pumpChannel) pump == null || (mode != null && configured != null && pumpTime != null) else cgmAvailable))
                states[channel] = decision.state
                summary.add("$channel: last successful timestamp=${last ?: "none"}; age(ms)=${last?.let { now - it } ?: "unknown"}; ${decision.reason}")
                when (decision.event) {
                    Policy.Event.ALARM -> {
                        record("ALARM", channel)
                        notifications.post(id(channel), TextRef.Literal("Elvin $channel: ${decision.reason}"), actions = listOf(
                            NotificationAction(TextRef.Literal("Snooze custom alarm")) { snooze(channel) }))
                        platform.present(channel, "Elvin $channel: ${decision.reason}")
                        // Standard audible safety alarms always take priority over custom playback.
                        if (notifications.notifications.value.any { it.sound != null }) {
                            platform.stop(channel)
                            summary.add("$channel custom audio deferred: official audible alarm active")
                        } else platform.play(channel, sound(channel))
                    }
                    Policy.Event.RECOVERY -> {
                        platform.dismiss(channel)
                        notifications.dismiss(id(channel))
                        record("RECOVERY", channel)
                        if (ElvinAudioSettings.read(preferences, pumpChannel).recovery) notifications.post(NotificationId.ELVIN_RECOVERY,
                            TextRef.Literal("Elvin $channel communication recovered"), validMinutes = 5)
                    }
                    Policy.Event.CLEAR -> { platform.dismiss(channel); notifications.dismiss(id(channel)) }
                    Policy.Event.NONE -> {
                        if (decision.reason == "Custom alarm snoozed") platform.stop(channel)
                        else if (decision.state.active && decision.reason == "Waiting for custom repeat interval") {
                            // Restore a persisted active warning after process death, without resetting its repeat clock.
                            if (notifications.notifications.value.none { it.id == id(channel) }) {
                                val text = "Elvin $channel: communication remains stale"
                                notifications.post(id(channel), TextRef.Literal(text), actions = listOf(
                                    NotificationAction(TextRef.Literal("Snooze custom alarm")) { snooze(channel) }))
                                platform.present(channel, text)
                                platform.play(channel, sound(channel))
                            } else if (source == ElvinSampleSource.MANUAL) platform.play(channel, sound(channel))
                        }
                    }
                }
            }
            summary.add("Pump suspended=${suspended ?: "unknown"}; intentional disconnection=${if (mode == null) "unknown" else disconnected}")
            if (sample && pump != null && mode == null) summary.add("Pump custom monitor suppressed: running-mode input unavailable; CGM monitoring continues")
            summary.add("Pod state: ${pumpContext?.description ?: "not sampled"}")
            summary.add("Pump status-read attempts (this process): ${ElvinTelemetry.pump.value}")
            summary.add("Nightscout V3 upload observations (this process): ${ElvinTelemetry.nightscout.value}")
            summary.addAll(status.map { "${it.key}: ${it.value}" })
            summary.add(maintenanceStatus.value)
            summary.add("Scheduling is best effort. A stopped/force-stopped app cannot monitor itself; use independent external monitoring.")
            summary.add("Timestamps are epoch milliseconds. No glucose values, credentials, identifiers or response bodies are exported.")
            summary.addAll(events.map { "${kotlin.time.Instant.fromEpochMilliseconds(it.at)} ${it.code} ${it.channel}" })
            output.value = summary.joinToString("\n")
            if (enabledNow || previouslyEnabled) persist()
            previouslyEnabled = enabledNow
        }
    }

    private fun persist() {
        val json = buildJsonObject {
            put("schema", 2)
            put("lastPeriodic", lastTick)
            put("history", buildJsonArray { events.forEach { event -> add(buildJsonObject {
                put("at", event.at); put("code", event.code); put("channel", event.channel)
            }) } })
            states.forEach { (channel, state) -> put(channel.name, buildJsonObject {
                put("watch", state.watchStarted); put("alarm", state.lastAlarm); put("snooze", state.snoozedUntil)
                put("active", state.active); put("outage", state.outageStarted)
            }) }
        }
        preferences.put(ElvinRuntimeKey.State, json.toString())
    }

    override fun snooze(channel: ElvinChannel) = stateLock.withLock {
        states[channel] = Policy.snooze(states[channel] ?: Policy.State(), date.now(),
            int(if (channel == ElvinChannel.PUMP) ElvinIntKey.PumpSnooze else ElvinIntKey.CgmSnooze))
        platform.dismiss(channel)
        notifications.dismiss(id(channel))
        record("SNOOZE", channel)
        persist()
    }
    override fun test(channel: ElvinChannel) {
        if (preferences.get(ElvinBooleanKey.Enabled) && platform.supported) platform.play(channel, sound(channel), test = true)
        else output.value = "Enable Elvin functionality before testing custom alarm audio. Official alarms remain unchanged."
    }
    override fun stopTest() { ElvinChannel.entries.forEach { platform.stop(it) } }
    override fun reset() = stateLock.withLock {
        stopTest()
        ElvinKeys.settings.forEach(preferences::remove)
        preferences.remove(ElvinRuntimeKey.State)
        ElvinUpdateLongKey.entries.forEach(preferences::remove)
        ElvinMaintenanceLongKey.entries.forEach(preferences::remove)
        states.clear(); events.clear(); lastTick = 0L; previouslyEnabled = false
        maintenance?.reset()
        ElvinChannel.entries.forEach { platform.dismiss(it); notifications.dismiss(id(it)) }
        output.value = "Elvin settings reset. Custom monitoring is disabled. Standard AAPS settings were not changed."
    }
    override fun exportDiagnostics() = platform.share(output.value)
    override fun openPermissionSettings(kind: String) = platform.openSettings(kind)
    override suspend fun refreshProfiles() {
        profilesOutput.value = try { maintenance?.profiles() ?: "Profile history adapter unavailable" }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { "Profile history currently unavailable. No data was changed." }
    }
    override fun backupNow() { maintenance?.backupNow() }
    override fun snoozeRoutineUpdate() {
        if (!preferences.get(ElvinBooleanKey.Enabled)) return
        preferences.put(ElvinUpdateLongKey.SnoozedUntil, date.now() + int(ElvinIntKey.UpdateSnoozeDays) * 86_400_000L)
        notifications.dismiss(NotificationId.NEW_VERSION_DETECTED)
        // Never dismiss VERSION_EXPIRE or modify AppExpiration / max-IOB constraints.
    }
}
