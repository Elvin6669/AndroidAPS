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
