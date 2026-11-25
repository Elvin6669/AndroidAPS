package app.aaps.implementation.elvin

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.elvin.*
import app.aaps.core.keys.interfaces.AppPlatform
import app.aaps.implementation.maintenance.migration.FileKeyValueStore
import app.aaps.implementation.sharedPreferences.PreferencesImpl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ElvinSettingsPersistenceTest {
    @Test fun `standard restore applies custom settings but rejects device runtime and snoozes`() = kotlinx.coroutines.test.runTest {
        val store = FileKeyValueStore()
        val prefs = preferences(store)
        val config = mock<Config>()
        whenever(config.APS).thenReturn(true)
        whenever(config.platform).thenReturn(AppPlatform.Android)
        val active = mock<app.aaps.core.interfaces.plugin.ActivePlugin>()
        whenever(active.getPluginsList()).thenReturn(arrayListOf())
        prefs.put(BooleanKey.AlertMissedBgReading, true)
        val applier = app.aaps.implementation.maintenance.PreferenceImportApplier(
            store, prefs, app.aaps.implementation.sharedPreferences.PreferenceKeyResolverFactory(prefs, active),
            config, mock(), mock(), app.aaps.implementation.maintenance.migration.PreferenceMigrations(mock(), config, mock(), mock(), mock()))
        val result = applier.apply(app.aaps.core.interfaces.maintenance.Prefs(mapOf(
            ElvinBooleanKey.PumpDndOverride.key to "true", ElvinIntKey.CgmTimeout.key to "17",
            ElvinRuntimeKey.State.key to "untrusted runtime", ElvinUpdateLongKey.SnoozedUntil.key to "12345",
            ElvinMaintenanceLongKey.LastBackup.key to "12345"), emptyMap()), true)
        assertTrue(prefs.get(ElvinBooleanKey.PumpDndOverride))
        assertEquals(17, prefs.get(ElvinIntKey.CgmTimeout))
        assertEquals("", prefs.get(ElvinRuntimeKey.State))
        assertEquals(0L, prefs.get(ElvinUpdateLongKey.SnoozedUntil))
        assertEquals(0L, prefs.get(ElvinMaintenanceLongKey.LastBackup))
        assertEquals(3, result.notExportable)
        assertTrue(prefs.get(BooleanKey.AlertMissedBgReading))
    }
    @Test fun `independent audio profiles inherit legacy settings but explicit channel settings win`() {
        val prefs = preferences(FileKeyValueStore())
        prefs.put(ElvinBooleanKey.SilentOverride, true)
        prefs.put(ElvinIntKey.StartVolume, 45)
        assertTrue(ElvinAudioSettings.read(prefs, true).silentOverride)
        assertTrue(ElvinAudioSettings.read(prefs, false).silentOverride)
        prefs.put(ElvinBooleanKey.PumpSilentOverride, false)
        prefs.put(ElvinIntKey.PumpStartVolume, 20)
        assertFalse(ElvinAudioSettings.read(prefs, true).silentOverride)
        assertTrue(ElvinAudioSettings.read(prefs, false).silentOverride)
        assertEquals(20, ElvinAudioSettings.read(prefs, true).start)
        assertEquals(45, ElvinAudioSettings.read(prefs, false).start)
        assertFalse(ElvinAudioSettings.boolean(prefs, ElvinBooleanKey.PumpSilentOverride))
    }
    private fun preferences(store: FileKeyValueStore): PreferencesImpl {
        val config = mock<Config>()
        whenever(config.APS).thenReturn(true)
        whenever(config.platform).thenReturn(AppPlatform.Android)
        return PreferencesImpl(store, { mock() }, { mock() }, { mock() }, mock(), config, mock())
    }
    @Test fun `old installation gains disabled defaults without touching standard safety settings`() {
        val store = FileKeyValueStore()
        val prefs = preferences(store)
        prefs.put(BooleanKey.AlertPumpUnreachable, true)
        assertFalse(prefs.get(ElvinBooleanKey.Enabled))
        assertEquals(1, prefs.get(ElvinIntKey.Schema))
        assertTrue(prefs.get(BooleanKey.AlertPumpUnreachable))
    }
    @Test fun `settings are registered before monitor creation and survive preferences reconstruction`() {
        val store = FileKeyValueStore()
        preferences(store).put(ElvinIntKey.PumpTimeout, 17)
        val reloaded = preferences(store)
        assertEquals(17, reloaded.get(ElvinIntKey.PumpTimeout))
        ElvinKeys.settings.forEach { assertTrue(reloaded.isExportableKey(it.key), it.key) }
        assertFalse(reloaded.isExportableKey(ElvinRuntimeKey.State.key))
        assertFalse(reloaded.isExportableKey(ElvinUpdateLongKey.SnoozedUntil.key))
    }
    @Test fun `reset key set cannot clear standard safety settings`() {
        val prefs = preferences(FileKeyValueStore())
        prefs.put(BooleanKey.AlertMissedBgReading, true)
        prefs.put(ElvinBooleanKey.Enabled, true)
        prefs.put(ElvinIntKey.CgmTimeout, 19)
        ElvinKeys.settings.forEach(prefs::remove)
        assertFalse(prefs.get(ElvinBooleanKey.Enabled))
        assertEquals(15, prefs.get(ElvinIntKey.CgmTimeout))
        assertTrue(prefs.get(BooleanKey.AlertMissedBgReading))
    }
}
