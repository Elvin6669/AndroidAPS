package app.aaps.core.keys.elvin

import app.aaps.core.keys.elvin.CommunicationAlarmPolicy as P
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CommunicationAlarmPolicyTest {
    private val start = 1_000_000L
    private fun observation(minutes: Int, success: Long? = start, disconnected: Boolean = false, suspended: Boolean = false, terminal: Boolean = false) =
        P.Observation(start + minutes * 60_000L, success, intentionalDisconnect = disconnected, suspended = suspended, terminalPodState = terminal)
    private val settings = P.Settings(true, 10, 5)

    @Test fun `disabled monitor emits no alarms and discards custom runtime`() {
        val result = P.evaluate(settings.copy(enabled = false), P.State(active = true, lastAlarm = start), observation(300))
        assertEquals(P.Event.CLEAR, result.event)
        assertEquals(P.State(), result.state)
    }
    @Test fun `all timeout settings respect boundary inclusive`() {
        for (minutes in 10..300) {
            val config = settings.copy(timeoutMinutes = minutes)
            assertEquals(P.Event.CLEAR, P.evaluate(config, P.State(), observation(minutes - 1)).event)
            assertEquals(P.Event.ALARM, P.evaluate(config, P.State(), observation(minutes)).event)
        }
    }
    @Test fun `pump and CGM policies have independent clocks`() {
        assertEquals(P.Event.ALARM, P.evaluate(settings, P.State(), observation(10)).event)
        assertEquals(P.Event.CLEAR, P.evaluate(settings.copy(timeoutMinutes = 15), P.State(), observation(10)).event)
    }
    @Test fun `repeat interval independent of timeout`() {
        val first = P.evaluate(settings, P.State(), observation(10))
        assertEquals(P.Event.NONE, P.evaluate(settings, first.state, observation(14)).event)
        assertEquals(P.Event.ALARM, P.evaluate(settings, first.state, observation(15)).event)
    }
    @Test fun `snooze uses separate duration and resumes overdue alarm`() {
        val first = P.evaluate(settings, P.State(), observation(10))
        val snoozed = P.snooze(first.state, start + 10 * 60_000L, 8)
        assertEquals(P.Event.NONE, P.evaluate(settings, snoozed, observation(17)).event)
        assertEquals(P.Event.ALARM, P.evaluate(settings, snoozed, observation(18)).event)
    }
    @Test fun `a short snooze expires independently of a longer repeat interval`() {
        val config = settings.copy(repeatMinutes = 60)
        val initial = P.evaluate(config, P.State(), observation(10))
        val snoozed = P.snooze(initial.state, start + 600_000L, 3)
        assertEquals(P.Event.NONE, P.evaluate(config, snoozed, observation(12)).event)
        val resumed = P.evaluate(config, snoozed, observation(13))
        assertEquals(P.Event.ALARM, resumed.event)
        assertEquals(0L, resumed.state.snoozedUntil)
        assertEquals(P.Event.NONE, P.evaluate(config, resumed.state, observation(14)).event)
    }
    @Test fun `recovery clears snooze and emits once`() {
        val first = P.evaluate(settings, P.State(), observation(10))
        val recovered = P.evaluate(settings, first.state, observation(11, start + 11 * 60_000L))
        assertEquals(P.Event.RECOVERY, recovered.event)
        assertFalse(recovered.state.active)
        assertEquals(0, recovered.state.snoozedUntil)
        assertEquals(P.Event.CLEAR, P.evaluate(settings, recovered.state, observation(12, start + 11 * 60_000L)).event)
    }
    @Test fun `missing CGM starts a watch and alarms after timeout`() {
        val first = P.evaluate(settings, P.State(), observation(0, null))
        assertEquals(P.Event.CLEAR, first.event)
        assertEquals(P.Event.ALARM, P.evaluate(settings, first.state, observation(10, null)).event)
    }
    @Test fun `restart does not snooze overdue known data`() {
        assertEquals(P.Event.ALARM, P.evaluate(settings, P.State(), observation(30)).event)
    }
    @Test fun `persisted runtime carries missing-record watch across restart`() {
        val stored = P.evaluate(settings, P.State(), observation(0, null)).state
        assertEquals(P.Event.ALARM, P.evaluate(settings, stored.copy(), observation(11, null)).event)
    }
    @Test fun `intentional disconnection suppresses only custom pump alarm`() {
        assertEquals(P.Event.CLEAR, P.evaluate(settings, P.State(active = true), observation(30, disconnected = true)).event)
    }
    @Test fun `suspension does not hide a communication failure`() {
        assertEquals(P.Event.ALARM, P.evaluate(settings, P.State(), observation(30, suspended = true)).event)
    }
    @Test fun `terminal pod state is distinguished from radio loss`() {
        val result = P.evaluate(settings, P.State(), observation(30, terminal = true))
        assertEquals(P.Event.CLEAR, result.event)
        assertTrue(result.reason.contains("terminal"))
    }
    @Test fun `unconfigured pump is not a communication loss`() {
        assertEquals(P.Event.CLEAR, P.evaluate(settings, P.State(), observation(30).copy(configured = false)).event)
    }
    @Test fun `future glucose timestamp is diagnosed`() {
        val result = P.evaluate(settings, P.State(), observation(0, start + 60_000))
        assertEquals(P.Event.NONE, result.event)
        assertTrue(result.reason.contains("clock"))
    }
    @Test fun `clock rollback cannot leave repeat blocked forever`() {
        val previous = P.State(watchStarted = start, lastAlarm = start + 90 * 60_000L, active = true)
        assertEquals(P.Event.ALARM, P.evaluate(settings, previous, observation(30)).event)
    }
    @Test fun `imported malformed thresholds are bounded`() {
        assertEquals(P.Event.CLEAR, P.evaluate(P.Settings(true, -100, -5), P.State(), observation(9)).event)
        assertEquals(P.Event.ALARM, P.evaluate(P.Settings(true, 9999, 9999), P.State(), observation(300)).event)
    }
    @Test fun `escalation has configured endpoints and midpoint`() {
        assertEquals(0.3f, P.volume(30, 100, 0, 10, true), 0.0001f)
        assertEquals(0.65f, P.volume(30, 100, 300_000, 10, true), 0.0001f)
        assertEquals(1f, P.volume(30, 100, 600_000, 10, true), 0.0001f)
        assertEquals(1f, P.volume(30, 100, 900_000, 10, true), 0.0001f)
    }
    @Test fun `volume never exceeds configured cap even with inconsistent imported values`() {
        for (elapsed in -1000L..601_000L step 1000) {
            assertTrue(P.volume(90, 40, elapsed, 10, true) in 0f..0.4f)
        }
        assertEquals(0.4f, P.volume(30, 40, 0, 10, false), 0.0001f)
    }
}
