package app.aaps.core.keys.elvin

/** Pure observation policy: no pump commands, glucose values, delivery calculations or Android APIs. */
object CommunicationAlarmPolicy {
    data class Settings(val enabled: Boolean, val timeoutMinutes: Int, val repeatMinutes: Int)
    data class State(
        val watchStarted: Long = 0, val lastAlarm: Long = 0, val snoozedUntil: Long = 0,
        val active: Boolean = false, val outageStarted: Long = 0
    )
    data class Observation(
        val now: Long, val lastSuccess: Long?, val intentionalDisconnect: Boolean = false,
        val configured: Boolean = true, val suspended: Boolean = false, val terminalPodState: Boolean = false
    )
    enum class Event { NONE, ALARM, RECOVERY, CLEAR }
    data class Decision(val state: State, val event: Event, val reason: String)

    fun evaluate(settings: Settings, state: State, observation: Observation): Decision {
        val o = observation
        if (!settings.enabled) return Decision(State(), Event.CLEAR, "Custom monitoring disabled")
        val started = state.watchStarted.takeIf { it > 0 && it <= o.now } ?: o.now
        val current = state.copy(watchStarted = started)
        if (o.intentionalDisconnect || !o.configured || o.terminalPodState) {
            val reason = when {
                o.intentionalDisconnect -> "Intentional AAPS pump disconnection"
                o.terminalPodState -> "Pod terminal/expired state; existing pump safety alarms remain active"
                else -> "Pump not configured"
            }
            return Decision(State(watchStarted = started), Event.CLEAR, reason)
        }
        val success = o.lastSuccess?.takeIf { it > 0 }
        if (success != null && success > o.now) return Decision(current, Event.NONE, "Future timestamp; check phone clock")
        val anchor = success ?: started
        if (o.now - anchor < settings.timeoutMinutes.coerceIn(10, 300) * 60_000L) {
            return Decision(State(watchStarted = started), if (current.active) Event.RECOVERY else Event.CLEAR, "Communication current")
        }
        val outage = current.outageStarted.takeIf { it > 0 } ?: anchor
        val active = current.copy(active = true, outageStarted = outage)
        if (o.now < current.snoozedUntil) return Decision(active, Event.NONE, "Custom alarm snoozed")
        val repeat = settings.repeatMinutes.coerceIn(1, 300) * 60_000L
        if (current.lastAlarm > 0 && o.now >= current.lastAlarm && o.now - current.lastAlarm < repeat)
            return Decision(active, Event.NONE, "Waiting for custom repeat interval")
        val reason = if (success == null) "No reading/communication record since monitoring began"
            else if (o.suspended) "Communication stale; pump is also suspended (suspension does not suppress this alarm)"
            else "Communication timeout"
        return Decision(active.copy(lastAlarm = o.now), Event.ALARM, reason)
    }

    fun snooze(state: State, now: Long, minutes: Int) = state.copy(snoozedUntil = now + minutes.coerceIn(1, 300) * 60_000L)

    /** A gain relative to the user's stream setting; never changes global Android stream volume. */
    fun volume(startPercent: Int, maximumPercent: Int, elapsedMillis: Long, durationMinutes: Int, escalate: Boolean): Float {
        val maximum = maximumPercent.coerceIn(0, 100)
        val start = startPercent.coerceIn(0, maximum)
        if (!escalate) return maximum / 100f
        val fraction = (elapsedMillis.coerceAtLeast(0).toDouble() / (durationMinutes.coerceIn(1, 60) * 60_000L)).coerceIn(0.0, 1.0)
        return ((start + (maximum - start) * fraction) / 100.0).toFloat()
    }
}
