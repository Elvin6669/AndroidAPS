package app.aaps.core.keys.elvin

/** Pure observation policy: no pump commands, glucose values, delivery calculations or Android APIs. */
object CommunicationAlarmPolicy {
    enum class AudioRoute { QUIET, NOTIFICATION, ALARM }
    fun audioRoute(dndActive: Boolean, silentOverride: Boolean, dndOverride: Boolean, policyAccess: Boolean): AudioRoute =
        if (dndActive && (!dndOverride || !policyAccess)) AudioRoute.QUIET
        else if (silentOverride || (dndActive && dndOverride)) AudioRoute.ALARM else AudioRoute.NOTIFICATION
    data class Settings(val enabled: Boolean, val timeoutMinutes: Int, val repeatMinutes: Int)
    data class State(
        val watchStarted: Long = 0, val lastAlarm: Long = 0, val snoozedUntil: Long = 0,
        val active: Boolean = false, val outageStarted: Long = 0
    )
    data class Observation(
        val now: Long, val lastSuccess: Long?, val intentionalDisconnect: Boolean = false,
        val configured: Boolean = true, val suspended: Boolean = false, val terminalPodState: Boolean = false,
        val failedAttemptConfirmed: Boolean? = null, val inputsAvailable: Boolean = true
    )
    enum class Event { NONE, ALARM, RECOVERY, CLEAR }
    data class Decision(val state: State, val event: Event, val reason: String)

    fun evaluate(settings: Settings, state: State, observation: Observation): Decision {
        val o = observation
        if (!settings.enabled) return Decision(State(), Event.CLEAR, "Custom monitoring disabled")
        if (!o.inputsAvailable) return Decision(state, Event.NONE, "Monitoring inputs unavailable; previous warning retained")
        val started = state.watchStarted.takeIf { it > 0 && it <= o.now } ?: o.now
        val rolledBack = state.watchStarted > o.now || state.lastAlarm > o.now
        val current = state.copy(watchStarted = started,
            lastAlarm = if (rolledBack) 0 else state.lastAlarm,
            snoozedUntil = if (rolledBack) 0 else state.snoozedUntil)
        if (o.intentionalDisconnect || !o.configured || o.terminalPodState) {
            val reason = when {
                o.intentionalDisconnect -> "Intentional AAPS pump disconnection"
                o.terminalPodState -> "Pod terminal (fault/deactivated) state; existing pump safety alarms remain active"
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
        val snoozeExpired = current.snoozedUntil > 0 && o.now >= current.snoozedUntil
        val active = current.copy(active = true, outageStarted = outage,
            snoozedUntil = if (snoozeExpired) 0 else current.snoozedUntil)
        if (o.now < current.snoozedUntil) return Decision(active, Event.NONE, "Custom alarm snoozed")
        val repeat = settings.repeatMinutes.coerceIn(1, 300) * 60_000L
        if (!snoozeExpired && current.lastAlarm > 0 && o.now >= current.lastAlarm && o.now - current.lastAlarm < repeat)
            return Decision(active, Event.NONE, "Waiting for custom repeat interval")
        val reason = if (success == null) "No reading/communication record since monitoring began"
            else if (o.suspended) "Communication stale; pump is also suspended (suspension does not suppress this alarm)"
            else if (o.failedAttemptConfirmed == false) "Pump data stale; a failed status-read attempt is not confirmed"
            else if (o.failedAttemptConfirmed == true) "Communication timeout after a failed status-read attempt"
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

/** Read-only upload health and backup scheduling; does not decide or deliver insulin. */
object ElvinMaintenancePolicy {
    data class UploadDecision(val pendingSince: Long, val warn: Boolean, val reason: String)
    fun upload(now: Long, enabled: Boolean, configured: Boolean, uploadsEnabled: Boolean, queue: Long,
               lastAttempt: Long, lastSuccess: Long, failures: Long, pendingSince: Long,
               lastWarning: Long, timeoutMinutes: Int, repeatMinutes: Int, paused: Boolean = false): UploadDecision {
        if (!enabled) return UploadDecision(0, false, "Nightscout custom monitoring disabled")
        if (!configured) return UploadDecision(0, false, "Nightscout V3 not configured")
        if (!uploadsEnabled) return UploadDecision(0, false, "Nightscout uploads intentionally disabled")
        if (paused) return UploadDecision(0, false, "Nightscout intentionally paused")
        if (queue <= 0 && failures <= 0) return UploadDecision(0, false, "No pending uploads or observed failures")
        val start = pendingSince.takeIf { it > 0 && it <= now } ?: now
        val progress = lastSuccess.takeIf { it in 1..now } ?: 0
        val anchor = maxOf(start, progress)
        val overdue = now - anchor >= timeoutMinutes.coerceIn(10, 300) * 60_000L
        val due = lastWarning <= 0 || now < lastWarning || now - lastWarning >= repeatMinutes.coerceIn(1, 300) * 60_000L
        val reason = if (failures > 0 && lastAttempt >= lastSuccess) "Nightscout V3 uploads failing" else "Nightscout V3 queue pending without recent upload confirmation"
        return UploadDecision(anchor, overdue && due, reason)
    }
    fun backupDue(now: Long, lastSuccess: Long, lastAttempt: Long, hours: Int): Boolean {
        val intervalDue = lastSuccess <= 0 || now < lastSuccess || now - lastSuccess >= hours.coerceIn(1, 168) * 3_600_000L
        val retryDue = lastAttempt <= 0 || now < lastAttempt || now - lastAttempt >= 15 * 60_000L
        return intervalDue && retryDue
    }
}
