package app.aaps.plugins.constraints.versionChecker

import android.os.Build
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.versionChecker.VersionDefinition
import app.aaps.core.keys.elvin.*
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.constraints.versionChecker.keys.VersionCheckerLongKey
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockingDetails
import org.mockito.kotlin.*

class ElvinUpdateReminderTest {
    private class Fixture(expiryDate: String) {
        val preferences = mock<Preferences>()
        val notifications = mock<NotificationManager>()
        private val now = kotlin.time.Instant.parse("2026-11-25T12:00:00Z").toEpochMilliseconds()
        private var upcoming = 0L
        private var routine = 0L
        private val config = mock<Config>().also { whenever(it.VERSION_NAME).thenReturn("4.0.0") }
        private val date = mock<DateUtil>().also {
            whenever(it.now()).thenReturn(now)
            whenever(it.isAfterNoon()).thenReturn(true)
        }
        init {
            whenever(preferences.get(ElvinBooleanKey.Enabled)).thenReturn(true)
            whenever(preferences.get(ElvinIntKey.UpdateDays)).thenReturn(7)
            whenever(preferences.get(ElvinUpdateLongKey.LastRoutineReminder)).thenAnswer { routine }
            whenever(preferences.get(VersionCheckerLongKey.LastExpiredWarning)).thenAnswer { upcoming }
            doAnswer { routine = it.arguments[1] as Long; null }.whenever(preferences).put(eq(ElvinUpdateLongKey.LastRoutineReminder), any())
            doAnswer { upcoming = it.arguments[1] as Long; null }.whenever(preferences).put(eq(VersionCheckerLongKey.LastExpiredWarning), any())
        }
        val checker = VersionCheckerUtilsImpl(mock(), preferences, mock(), { config }, date, notifications,
            VersionDefinition { buildJsonObject { put(Build.VERSION.SDK_INT.toString(), "9.9.9"); put("4.0.0", expiryDate) } })
        fun posts() = mockingDetails(notifications).invocations.filter { it.method.name == "post" }.map { it.arguments[0] }
    }
    @Test fun `routine reminder frequency has independent bookkeeping`() {
        val f = Fixture("2030-01-01")
        f.checker.triggerCheckVersion(); f.checker.triggerCheckVersion()
        assertEquals(1, f.posts().count { it == NotificationId.NEW_VERSION_DETECTED })
    }
    @Test fun `routine snooze cannot suppress an expired version warning`() {
        val f = Fixture("2020-01-01")
        whenever(f.preferences.get(ElvinUpdateLongKey.SnoozedUntil)).thenReturn(Long.MAX_VALUE)
        f.checker.triggerCheckVersion()
        assertTrue(NotificationId.VERSION_EXPIRE in f.posts())
        assertFalse(NotificationId.NEW_VERSION_DETECTED in f.posts())
    }
    @Test fun `upcoming expiration warning does not repeat on every algorithm invocation`() {
        val f = Fixture("2030-01-01")
        whenever(f.preferences.get(ElvinUpdateLongKey.SnoozedUntil)).thenReturn(Long.MAX_VALUE)
        f.checker.triggerCheckVersion(); f.checker.triggerCheckVersion()
        assertEquals(1, f.posts().count { it == NotificationId.VERSION_EXPIRE })
    }
}
