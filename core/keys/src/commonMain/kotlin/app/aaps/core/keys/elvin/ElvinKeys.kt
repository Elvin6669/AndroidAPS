package app.aaps.core.keys.elvin

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.*

/** EI.1 schema. Only settings are exportable; runtime and diagnostic state stay on this phone. */
enum class ElvinBooleanKey(
    override val key: String, override val defaultValue: Boolean, label: String
) : BooleanPreferenceKey {
    Enabled("elvin_enabled", false, "Enable Elvin custom functionality"),
    PumpEnabled("elvin_pump_enabled", true, "Monitor pump communication"),
    CgmEnabled("elvin_cgm_enabled", true, "Monitor CGM readings"),
    Recovery("elvin_recovery", true, "Notify on communication recovery"),
    Vibration("elvin_vibration", true, "Vibrate for custom alarms"),
    SilentOverride("elvin_silent_override", false, "Use Android alarm audio stream (silent mode)"),
    DndOverride("elvin_dnd_override", false, "Allow custom alarms during Do Not Disturb"),
    Escalate("elvin_escalate", true, "Gradually increase custom alarm volume");

    override val title = TextRef.Literal(label)
    override val preferenceType = PreferenceType.SWITCH
}

enum class ElvinIntKey(
    override val key: String, override val defaultValue: Int,
    override val min: Int, override val max: Int, label: String
) : IntPreferenceKey {
    PumpTimeout("elvin_pump_timeout", 10, 10, 300, "Pump timeout (minutes)"),
    CgmTimeout("elvin_cgm_timeout", 15, 10, 300, "CGM timeout (minutes)"),
    PumpRepeat("elvin_pump_repeat", 5, 1, 300, "Pump repeat interval (minutes)"),
    CgmRepeat("elvin_cgm_repeat", 5, 1, 300, "CGM repeat interval (minutes)"),
    PumpSnooze("elvin_pump_snooze", 5, 1, 300, "Pump snooze (minutes)"),
    CgmSnooze("elvin_cgm_snooze", 5, 1, 300, "CGM snooze (minutes)"),
    PumpSound("elvin_pump_sound", 0, 0, 3, "Pump sound: 0 alarm, 1 urgent, 2 error, 3 bolus error"),
    CgmSound("elvin_cgm_sound", 1, 0, 3, "CGM sound: 0 alarm, 1 urgent, 2 error, 3 bolus error"),
    StartVolume("elvin_start_volume", 30, 0, 100, "Starting volume (% of Android stream volume)"),
    MaxVolume("elvin_max_volume", 100, 0, 100, "Maximum volume (% of Android stream volume)"),
    EscalationMinutes("elvin_escalation_minutes", 10, 1, 60, "Volume escalation duration (minutes)"),
    UpdateDays("elvin_update_days", 1, 1, 30, "Routine update reminder interval (days)"),
    UpdateSnoozeDays("elvin_update_snooze_days", 1, 1, 30, "Routine update snooze (days)"),
    Schema("elvin_schema", 1, 1, 1, "Elvin settings schema");

    override val title = TextRef.Literal(label)
}

enum class ElvinRuntimeKey(override val key: String) : StringNonPreferenceKey {
    State("elvin_runtime_state");
    override val defaultValue = ""
    override val exportable = false
}

enum class ElvinUpdateLongKey(override val key: String) : LongNonPreferenceKey {
    SnoozedUntil("elvin_routine_update_snoozed_until"),
    LastRoutineReminder("elvin_last_routine_update_reminder");
    override val defaultValue = 0L
    override val exportable = false
}

object ElvinKeys {
    val settings: List<NonPreferenceKey> = ElvinBooleanKey.entries + ElvinIntKey.entries
    val all: List<NonPreferenceKey> = settings + ElvinRuntimeKey.entries + ElvinUpdateLongKey.entries
}
