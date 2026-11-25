package app.aaps.implementation.elvin

import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.automation.AutomationEvent
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.maintenance.*
import app.aaps.core.interfaces.notifications.*
import app.aaps.core.interfaces.nsclient.NSClientRepository
import app.aaps.core.interfaces.protection.ExportPasswordDataStore
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.elvin.*
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.AppPlatform
import app.aaps.implementation.maintenance.migration.FileKeyValueStore
import app.aaps.implementation.sharedPreferences.PreferencesImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

class ElvinMaintenanceImplTest {
    private fun posted(f: Fixture, id: NotificationId) = org.mockito.Mockito.mockingDetails(f.notices).invocations.count {
        it.method.name == "post" && it.arguments.firstOrNull() == id
    }
    @Test fun `stalled V3 queue warns and intentional pause clears only custom warning`() = runTest {
        val f = Fixture(); f.setup()
        f.prefs.put(ElvinBooleanKey.AutoBackup, false)
        f.prefs.put(ElvinBooleanKey.NightscoutMonitoring, true)
        f.prefs.put(BooleanKey.NsClientUploadData, true)
        whenever(f.repo.queueSize).thenReturn(MutableStateFlow(2L))
        whenever(f.repo.urlUpdate).thenReturn(MutableStateFlow("https://test.invalid"))
        val m = ElvinMaintenanceImpl(f.prefs, f.config, f.date, f.persistence, { f.exporter }, { f.password }, { f.automation }, { f.repo }, f.notices, f.platform, backgroundScope)
        m.onSample(true); m.awaitIdle()
        assertEquals(0, posted(f, NotificationId.ELVIN_NIGHTSCOUT_STALLED))
        f.now += 30 * 60_000L; m.onSample(true); m.awaitIdle()
        assertEquals(1, posted(f, NotificationId.ELVIN_NIGHTSCOUT_STALLED))
        assertFalse(m.status.value.contains("test.invalid"))
        whenever(f.repo.uploadsPaused()).thenReturn(true)
        m.onSample(true); m.awaitIdle()
        assertTrue(m.status.value.contains("intentionally paused"))
        assertEquals(0L, f.prefs.get(ElvinMaintenanceLongKey.NsPendingSince))
        verify(f.notices).dismiss(NotificationId.ELVIN_NIGHTSCOUT_STALLED)
        verifyNoInteractions(f.exporter, f.password)
    }
    @Test fun `profile history is read only and uses configured window`() = runTest {
        val f = Fixture(); f.setup()
        whenever(f.persistence.getProfileSwitchesIncludingInvalidFromTime(any(), eq(false))).thenReturn(emptyList())
        whenever(f.persistence.getEffectiveProfileSwitchesIncludingInvalidFromTime(any(), eq(false))).thenReturn(emptyList())
        f.prefs.put(ElvinIntKey.ProfileHistoryDays, 7)
        val m = ElvinMaintenanceImpl(f.prefs, f.config, f.date, f.persistence, { f.exporter }, { f.password }, { f.automation }, { f.repo }, f.notices, f.platform, backgroundScope)
        assertTrue(m.profiles().contains("7 days"))
        verify(f.persistence).getProfileSwitchesIncludingInvalidFromTime(f.now - 7 * 86_400_000L, false)
        verify(f.persistence).getEffectiveProfileSwitchesIncludingInvalidFromTime(f.now - 7 * 86_400_000L, false)
        verifyNoMoreInteractions(f.persistence)
        verifyNoInteractions(f.exporter, f.password, f.repo)
    }
    private class Fixture {
        var now = 10_000_000L
        val config = mock<Config>().also {
            whenever(it.APS).thenReturn(true)
            whenever(it.platform).thenReturn(AppPlatform.Android)
        }
        val prefs = PreferencesImpl(FileKeyValueStore(), { mock() }, { mock() }, { mock() }, mock(), config, mock())
        val exporter = mock<ImportExportPrefs>()
        val password = mock<ExportPasswordDataStore>()
        val automation = mock<Automation>().also { whenever(it.events).thenReturn(MutableStateFlow(emptyList())) }
        val repo = mock<NSClientRepository>()
        val persistence = mock<PersistenceLayer>()
        val notices = mock<NotificationManager>()
        val platform = mock<ElvinPlatform>()
        val date = mock<DateUtil>().also { whenever(it.now()).thenAnswer { now } }
        suspend fun setup() {
            prefs.put(ElvinBooleanKey.Enabled, true)
            prefs.put(ElvinBooleanKey.AutoBackup, true)
            whenever(exporter.isMasterPasswordSet()).thenReturn(true)
            whenever(password.exportPasswordStoreEnabled()).thenReturn(true)
            whenever(password.getPasswordFromDataStore()).thenReturn(Triple("test-only", false, false))
            whenever(exporter.getExportConfig()).thenReturn(ExportConfig(false, false, false, true, false, false, false, false, false, null))
            whenever(exporter.executeAutomaticExport(any())).thenReturn(ExportResult(localSuccess = true))
        }
    }
    @Test fun `disabled master cannot export or inspect sync`() = runTest {
        val f = Fixture(); f.setup(); f.prefs.put(ElvinBooleanKey.Enabled, false)
        val m = ElvinMaintenanceImpl(f.prefs, f.config, f.date, f.persistence, { f.exporter }, { f.password }, { f.automation }, { f.repo }, f.notices, f.platform, backgroundScope)
        m.backupNow(); m.awaitIdle()
        verifyNoInteractions(f.exporter, f.password, f.repo)
    }
    @Test fun `scheduled encrypted backup respects interval and reset preserves standard credentials`() = runTest {
        val f = Fixture(); f.setup()
        val m = ElvinMaintenanceImpl(f.prefs, f.config, f.date, f.persistence, { f.exporter }, { f.password }, { f.automation }, { f.repo }, f.notices, f.platform, backgroundScope)
        m.onSample(true); m.awaitIdle()
        assertEquals(f.now, f.prefs.get(ElvinMaintenanceLongKey.LastBackup))
        f.now += 60_000; m.onSample(true); m.awaitIdle()
        verify(f.exporter, times(1)).executeAutomaticExport("test-only")
        m.reset()
        assertEquals(0L, f.prefs.get(ElvinMaintenanceLongKey.LastBackup))
        verify(f.password, never()).clearPasswordDataStore()
    }
    @Test fun `expired password prevents any export and exposes no password`() = runTest {
        val f = Fixture(); f.setup()
        whenever(f.password.getPasswordFromDataStore()).thenReturn(Triple("test-only", true, false))
        val m = ElvinMaintenanceImpl(f.prefs, f.config, f.date, f.persistence, { f.exporter }, { f.password }, { f.automation }, { f.repo }, f.notices, f.platform, backgroundScope)
        m.backupNow(); m.awaitIdle()
        verify(f.exporter, never()).executeAutomaticExport(any())
        assertTrue(m.status.value.contains("expired"))
        assertFalse(m.status.value.contains("test-only"))
    }
    @Test fun `partial cloud failure is reported and successful local backup throttles schedule`() = runTest {
        val f = Fixture(); f.setup()
        whenever(f.exporter.executeAutomaticExport(any())).thenReturn(ExportResult(true, false))
        val m = ElvinMaintenanceImpl(f.prefs, f.config, f.date, f.persistence, { f.exporter }, { f.password }, { f.automation }, { f.repo }, f.notices, f.platform, backgroundScope)
        m.backupNow(); m.awaitIdle()
        assertTrue(m.status.value.contains("cloud=false"))
        assertEquals(f.now, f.prefs.get(ElvinMaintenanceLongKey.LastBackup))
        assertEquals(1, posted(f, NotificationId.ELVIN_BACKUP_STATUS))
    }
    @Test fun `existing export Automation takes precedence while manual backup remains available`() = runTest {
        val f = Fixture(); f.setup()
        val event = mock<AutomationEvent>().also {
            whenever(it.isEnabled).thenReturn(true)
            whenever(it.containsSettingsExport).thenReturn(true)
        }
        whenever(f.automation.events).thenReturn(MutableStateFlow(listOf(event)))
        val m = ElvinMaintenanceImpl(f.prefs, f.config, f.date, f.persistence, { f.exporter }, { f.password }, { f.automation }, { f.repo }, f.notices, f.platform, backgroundScope)
        m.onSample(true); m.awaitIdle()
        verify(f.exporter, never()).executeAutomaticExport(any())
        assertTrue(m.status.value.contains("duplicate"))
        m.backupNow(); m.awaitIdle()
        verify(f.exporter).executeAutomaticExport("test-only")
    }
}
