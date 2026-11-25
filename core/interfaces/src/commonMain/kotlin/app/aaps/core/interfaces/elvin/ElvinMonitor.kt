package app.aaps.core.interfaces.elvin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class ElvinChannel { PUMP, CGM }

interface ElvinMonitor {
    val diagnostics: StateFlow<String>
    suspend fun tick()
    fun snooze(channel: ElvinChannel)
    fun test(channel: ElvinChannel)
    fun stopTest()
    fun reset()
    fun exportDiagnostics()
    fun openPermissionSettings(kind: String)
}

/** Read-only status supplied by a driver. No pump operations are added to the monitor. */
data class ElvinPumpContext(val terminal: Boolean = false, val description: String = "Not reported by this driver")

/** Allowlisted observations only: never store reasons, URLs, glucose values or response bodies. */
object ElvinTelemetry {
    data class Attempts(val lastAttempt: Long = 0, val lastSuccess: Long = 0, val consecutiveFailures: Int = 0)
    val pump = MutableStateFlow(Attempts())
    val nightscout = MutableStateFlow(Attempts())
    fun pumpAttempt(now: Long) { pump.update { it.copy(lastAttempt = now) } }
    fun pumpResult(now: Long, success: Boolean) {
        pump.update { if (success) it.copy(lastSuccess = now, consecutiveFailures = 0)
            else it.copy(consecutiveFailures = (it.consecutiveFailures + 1).coerceAtMost(1_000_000)) }
    }
    fun nightscoutResult(now: Long, success: Boolean) {
        nightscout.update { if (success) Attempts(now, now, 0)
            else it.copy(lastAttempt = now, consecutiveFailures = (it.consecutiveFailures + 1).coerceAtMost(1_000_000)) }
    }
}
