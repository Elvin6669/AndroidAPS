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
    private val platform: ElvinPlatform
) : ElvinMonitor {
    private val stateLock = AapsLock()
    private val output = MutableStateFlow("Elvin monitor has not run yet. Android background timing is not guaranteed.")
    override val diagnostics: StateFlow<String> = output
    private val events = ArrayDeque<String>()
    private var lastTick = 0L
    private val states = mutableMapOf<ElvinChannel, Policy.State>()

    init {
        preferences.registerPreferences(ElvinKeys.all)
        // Malformed or future-schema runtime data is ignored, never used as imported alarm state.
        runCatching {
            val json = Json.parseToJsonElement(preferences.get(ElvinRuntimeKey.State)).jsonObject
            if (json["schema"]?.jsonPrimitive?.int == 1) {
                ElvinChannel.entries.forEach { channel ->
                    json[channel.name]?.jsonObject?.let { v ->
                        fun number(k: String) = v[k]?.jsonPrimitive?.longOrNull ?: 0L
                        states[channel] = Policy.State(number("watch"), number("alarm"), number("snooze"),
                            v["active"]?.jsonPrimitive?.booleanOrNull ?: false, number("outage"))
                    }
                }
            }
        }
    }

    private fun int(key: ElvinIntKey) = preferences.get(key).coerceIn(key.min, key.max)
    private fun id(channel: ElvinChannel) = if (channel == ElvinChannel.PUMP) NotificationId.ELVIN_PUMP_LOSS else NotificationId.ELVIN_CGM_LOSS
    private fun sound(channel: ElvinChannel): AlarmSound = listOf(AlarmSound.ALARM, AlarmSound.URGENT_ALARM, AlarmSound.ERROR, AlarmSound.BOLUS_ERROR)[
        int(if (channel == ElvinChannel.PUMP) ElvinIntKey.PumpSound else ElvinIntKey.CgmSound)]
    private fun record(text: String) {
        events.addLast("${date.now()} $text")
        while (events.size > 100) events.removeFirst()
    }

    override suspend fun tick() {
        // This hook must never prevent the existing AAPS alarm/maintenance path from running.
        try { evaluate() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            ElvinChannel.entries.forEach { platform.stop(it) }
            output.value = "Custom monitor failed; official AAPS alarms remain unchanged. Check standard AAPS logs."
        }
    }

    private suspend fun evaluate() {
        val now = date.now()
        val enabled = preferences.get(ElvinBooleanKey.Enabled) && platform.supported && config.APS
        val pump = if (enabled) activePlugin().activePump else null
        val disconnected = enabled && loop().runningMode() == RM.Mode.DISCONNECTED_PUMP
        val lastPump = pump?.lastDataTime?.value?.takeIf { it > 0 }
        val lastCgm = if (enabled) persistence.getLastGlucoseValue()?.timestamp else null
        val status = platform.status()
        stateLock.withLock {
        val summary = mutableListOf("Elvin 4.0.0-EI.1; enabled=$enabled", "Worker sample: $now; previous=$lastTick; gap(ms)=${if (lastTick > 0) now - lastTick else 0}")
        if (enabled) record("Background monitor sample")
        lastTick = now
        ElvinChannel.entries.forEach { channel ->
            val pumpChannel = channel == ElvinChannel.PUMP
            val last = if (pumpChannel) lastPump else lastCgm
            val policy = Policy.Settings(enabled && preferences.get(if (pumpChannel) ElvinBooleanKey.PumpEnabled else ElvinBooleanKey.CgmEnabled),
                int(if (pumpChannel) ElvinIntKey.PumpTimeout else ElvinIntKey.CgmTimeout),
                int(if (pumpChannel) ElvinIntKey.PumpRepeat else ElvinIntKey.CgmRepeat))
            val decision = Policy.evaluate(policy, states[channel] ?: Policy.State(), Policy.Observation(now, last,
                intentionalDisconnect = pumpChannel && disconnected,
                configured = !pumpChannel || pump?.isConfigured() == true,
                suspended = pumpChannel && pump?.isSuspended() == true,
                terminalPodState = pumpChannel && pump?.elvinCommunicationContext?.terminal == true))
            states[channel] = decision.state
            summary.add("$channel: last successful timestamp=${last ?: "none"}; age(ms)=${last?.let { now - it } ?: "unknown"}; ${decision.reason}")
            when (decision.event) {
                Policy.Event.ALARM -> {
                    record("$channel alarm: ${decision.reason}")
                    notifications.post(id(channel), TextRef.Literal("Elvin $channel: ${decision.reason}"), actions = listOf(
                        NotificationAction(TextRef.Literal("Snooze custom alarm")) { snooze(channel) }))
                    // Standard audible safety alarms always take priority over custom playback.
                    if (notifications.notifications.value.any { it.sound != null }) {
                        platform.stop(channel)
                        summary.add("$channel custom audio deferred: official audible alarm active")
                    } else platform.play(channel, sound(channel))
                }
                Policy.Event.RECOVERY -> {
                    platform.stop(channel)
                    notifications.dismiss(id(channel))
                    record("$channel recovered")
                    if (preferences.get(ElvinBooleanKey.Recovery)) notifications.post(NotificationId.ELVIN_RECOVERY,
                        TextRef.Literal("Elvin $channel communication recovered"), validMinutes = 5)
                }
                Policy.Event.CLEAR -> { platform.stop(channel); notifications.dismiss(id(channel)) }
                Policy.Event.NONE -> if (decision.reason == "Custom alarm snoozed") platform.stop(channel)
            }
        }
        summary.add("Pump suspended=${pump?.isSuspended() ?: "unknown"}; intentional disconnection=$disconnected")
        summary.add("Pod state: ${pump?.elvinCommunicationContext?.description ?: "not sampled"}")
        summary.add("Pump status-read attempts (this process): ${ElvinTelemetry.pump.value}")
        summary.add("Nightscout V3 upload observations (this process): ${ElvinTelemetry.nightscout.value}")
        summary.addAll(status.map { "${it.key}: ${it.value}" })
        summary.add("Scheduling is best effort. A stopped/force-stopped app cannot monitor itself; use independent external monitoring.")
        summary.add("Timestamps are epoch milliseconds. No glucose values, credentials, identifiers or response bodies are exported.")
        summary.addAll(events)
        output.value = summary.joinToString("\n")
        persist()
        }
    }

    private fun persist() {
        val json = buildJsonObject {
            put("schema", 1)
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
        platform.stop(channel)
        notifications.dismiss(id(channel))
        record("$channel custom alarm snoozed; official alarms unaffected")
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
        states.clear(); events.clear()
        ElvinChannel.entries.forEach { notifications.dismiss(id(it)) }
        output.value = "Elvin settings reset. Custom monitoring is disabled. Standard AAPS settings were not changed."
    }
    override fun exportDiagnostics() = platform.share(output.value)
    override fun openPermissionSettings(kind: String) = platform.openSettings(kind)
}
