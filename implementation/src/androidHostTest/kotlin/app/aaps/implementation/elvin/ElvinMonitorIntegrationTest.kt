package app.aaps.implementation.elvin

import app.aaps.core.data.model.RM
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.elvin.*
import app.aaps.core.interfaces.notifications.*
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.elvin.*
import app.aaps.core.keys.interfaces.AppPlatform
import app.aaps.implementation.maintenance.migration.FileKeyValueStore
import app.aaps.implementation.sharedPreferences.PreferencesImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class ElvinMonitorIntegrationTest {
    private class Platform : ElvinPlatform {
        override val supported = true
        val played = mutableListOf<ElvinChannel>()
        var failStop = false
        override fun present(channel: ElvinChannel, text: String) = Unit
        override fun onSnooze(action: (ElvinChannel) -> Unit) = Unit
        override fun status() = mapOf("test" to "allowlisted status")
        override fun play(channel: ElvinChannel, sound: AlarmSound, test: Boolean) { played.add(channel) }
        override fun stop(channel: ElvinChannel) { if (failStop) error("test-only stop failure") }
        override fun dismiss(channel: ElvinChannel) = Unit
        override fun share(text: String) = Unit
        override fun openSettings(kind: String) = Unit
    }
    private class Fixture {
        var now = 1_000_000L
        val config = mock<Config>().also {
            whenever(it.APS).thenReturn(true)
            whenever(it.platform).thenReturn(AppPlatform.Android)
        }
        val preferences = PreferencesImpl(FileKeyValueStore(), { mock() }, { mock() }, { mock() }, mock(), config, mock())
        val pump = mock<PumpWithConcentration>().also {
            whenever(it.isConfigured()).thenReturn(true)
            whenever(it.lastDataTime).thenReturn(MutableStateFlow(1_000_000L))
            whenever(it.elvinCommunicationContext).thenReturn(ElvinPumpContext())
            whenever(it.selectedActivePump()).thenReturn(it)
        }
        val active = mock<ActivePlugin>().also { whenever(it.activePump).thenReturn(pump) }
        val loop = mock<Loop>()
        val persistence = mock<PersistenceLayer>()
        val notifications = mock<NotificationManager>().also { whenever(it.notifications).thenReturn(MutableStateFlow(emptyList())) }
        val date = mock<DateUtil>().also { whenever(it.now()).thenAnswer { now } }
        val platform = Platform()
        fun monitor() = ElvinMonitorImpl(preferences, { active }, { loop }, persistence, date, config, notifications, platform)
        suspend fun setup(enabled: Boolean = true) {
            whenever(loop.runningMode()).thenReturn(RM.Mode.CLOSED_LOOP)
            whenever(persistence.getLastGlucoseValue()).thenReturn(null)
            preferences.put(ElvinBooleanKey.Enabled, enabled)
            preferences.put(ElvinBooleanKey.PumpEnabled, false)
            preferences.put(ElvinIntKey.CgmTimeout, 10)
        }
    }

    @Test fun `missing CGM record generates additive alarm and persists watch across monitor reconstruction`() = runTest {
        val f = Fixture(); f.setup()
        f.monitor().tick()
        assertTrue(f.platform.played.isEmpty())
        f.now += 600_000
        val restarted = f.monitor()
        restarted.tick()
        assertEquals(listOf(ElvinChannel.CGM), f.platform.played)
        assertTrue(restarted.diagnostics.value.contains("No reading/communication record"))
        verify(f.pump, never()).getPumpStatus(any())
    }
    @Test fun `disabled functionality cannot play custom alarms`() = runTest {
        val f = Fixture(); f.setup(false)
        val monitor = f.monitor()
        monitor.tick(); f.now += 900_000; monitor.tick(); monitor.test(ElvinChannel.CGM)
        assertTrue(f.platform.played.isEmpty())
        verifyNoInteractions(f.active, f.loop, f.persistence)
    }
    @Test fun `custom snooze cannot dismiss standard notification IDs`() = runTest {
        val f = Fixture(); f.setup()
        val monitor = f.monitor()
        monitor.tick(); f.now += 600_000; monitor.tick()
        monitor.snooze(ElvinChannel.CGM)
        f.now += 60_000; monitor.tick()
        assertEquals(1, f.platform.played.size)
        verify(f.notifications, never()).dismiss(NotificationId.BG_READINGS_MISSED)
        verify(f.notifications, never()).dismiss(NotificationId.PUMP_UNREACHABLE)
    }
    @Test fun `monitor failure cannot escape into official alarm path`() = runTest {
        val f = Fixture(); f.setup()
        whenever(f.persistence.getLastGlucoseValue()).thenThrow(IllegalStateException("test"))
        val monitor = f.monitor()
        monitor.tick()
        assertTrue(monitor.diagnostics.value.contains("official AAPS alarms remain unchanged"))
    }
    @Test fun `even a failed custom cleanup cannot block official alarm processing`() = runTest {
        val f = Fixture(); f.setup(); f.platform.failStop = true
        whenever(f.persistence.getLastGlucoseValue()).thenThrow(IllegalStateException("test-only private failure details"))
        val monitor = f.monitor()
        monitor.tick()
        assertTrue(monitor.diagnostics.value.contains("official AAPS alarms remain unchanged"))
        assertFalse(monitor.diagnostics.value.contains("private failure details"))
    }
    @Test fun `official audible alarm takes priority over custom playback`() = runTest {
        val f = Fixture(); f.setup()
        whenever(f.notifications.notifications).thenReturn(MutableStateFlow(listOf(
            AapsNotification(NotificationId.PUMP_ERROR, 1, "standard safety alarm", NotificationLevel.URGENT, sound = AlarmSound.ALARM))))
        val monitor = f.monitor()
        monitor.tick(); f.now += 600_000; monitor.tick()
        assertTrue(f.platform.played.isEmpty())
        assertTrue(monitor.diagnostics.value.contains("official audible alarm active"))
    }
    @Test fun `routine reminder snooze cannot dismiss expiration warnings`() = runTest {
        val f = Fixture(); f.setup()
        f.monitor().snoozeRoutineUpdate()
        assertTrue(f.preferences.get(ElvinUpdateLongKey.SnoozedUntil) > f.now)
        verify(f.notifications).dismiss(NotificationId.NEW_VERSION_DETECTED)
        verify(f.notifications, never()).dismiss(NotificationId.VERSION_EXPIRE)
    }
    @Test fun `selected driver terminal pod context is read through the concentration wrapper`() = runTest {
        val f = Fixture(); f.setup()
        f.preferences.put(ElvinBooleanKey.PumpEnabled, true)
        f.preferences.put(ElvinBooleanKey.CgmEnabled, false)
        whenever(f.pump.elvinCommunicationContext).thenReturn(ElvinPumpContext(true, "terminal pod"))
        f.now += 1_800_000
        val monitor = f.monitor(); monitor.tick()
        assertTrue(f.platform.played.isEmpty())
        assertTrue(monitor.diagnostics.value.contains("terminal pod"))
        verify(f.pump).selectedActivePump()
        verify(f.notifications, never()).dismiss(NotificationId.PUMP_UNREACHABLE)
    }
    @Test fun `CGM monitor remains independent when no pump is selected`() = runTest {
        val f = Fixture(); f.setup()
        whenever(f.active.activePump).thenThrow(IllegalStateException("No pump selected"))
        val monitor = f.monitor(); monitor.tick()
        f.now += 600_000; monitor.tick()
        assertEquals(listOf(ElvinChannel.CGM), f.platform.played)
        verifyNoInteractions(f.loop)
    }
}
