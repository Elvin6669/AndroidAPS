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
    Escalate("elvin_escalate", true, "Gradually increase custom alarm volume"),
    PumpRecovery("elvin_pump_recovery", true, "Pump: recovery notification"),
    CgmRecovery("elvin_cgm_recovery", true, "CGM: recovery notification"),
    PumpVibration("elvin_pump_vibration", true, "Pump: vibration"),
    CgmVibration("elvin_cgm_vibration", true, "CGM: vibration"),
    PumpSilentOverride("elvin_pump_silent", false, "Pump: use alarm stream in silent mode"),
    CgmSilentOverride("elvin_cgm_silent", false, "CGM: use alarm stream in silent mode"),
    PumpDndOverride("elvin_pump_dnd", false, "Pump: allow sound during DND (Android policy applies)"),
    CgmDndOverride("elvin_cgm_dnd", false, "CGM: allow sound during DND (Android policy applies)"),
    PumpEscalate("elvin_pump_escalate", true, "Pump: gradual volume escalation"),
    CgmEscalate("elvin_cgm_escalate", true, "CGM: gradual volume escalation"),
    NightscoutMonitoring("elvin_ns_monitor", false, "Monitor Nightscout V3 upload failures"),
    AutoBackup("elvin_auto_backup", false, "Automatic encrypted settings backups");

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
    PumpStartVolume("elvin_pump_start_volume", 30, 0, 100, "Pump: starting volume (%)"),
    CgmStartVolume("elvin_cgm_start_volume", 30, 0, 100, "CGM: starting volume (%)"),
    PumpMaxVolume("elvin_pump_max_volume", 100, 0, 100, "Pump: maximum volume (%)"),
    CgmMaxVolume("elvin_cgm_max_volume", 100, 0, 100, "CGM: maximum volume (%)"),
    PumpEscalationMinutes("elvin_pump_escalation", 10, 1, 60, "Pump: escalation duration (minutes)"),
    CgmEscalationMinutes("elvin_cgm_escalation", 10, 1, 60, "CGM: escalation duration (minutes)"),
    NightscoutTimeout("elvin_ns_timeout", 30, 10, 300, "Nightscout: failure/stalled queue threshold (minutes)"),
    NightscoutRepeat("elvin_ns_repeat", 30, 1, 300, "Nightscout: warning repeat interval (minutes)"),
    BackupHours("elvin_backup_hours", 24, 1, 168, "Encrypted backup interval (hours)"),
    ProfileHistoryDays("elvin_profile_history_days", 30, 1, 365, "Profile history window (days; latest 100 events)"),
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

enum class ElvinMaintenanceLongKey(override val key: String) : LongNonPreferenceKey {
    LastBackup("elvin_last_backup"), LastBackupAttempt("elvin_last_backup_attempt"),
    LastNsWarning("elvin_last_ns_warning"), NsPendingSince("elvin_ns_pending_since"),
    LastNsSuccess("elvin_ns_last_success"), LastNsAttempt("elvin_ns_last_attempt"), NsFailures("elvin_ns_failures");
    override val defaultValue = 0L
    override val exportable = false
}

object ElvinKeys {
    val settings: List<NonPreferenceKey> = ElvinBooleanKey.entries + ElvinIntKey.entries
    val all: List<NonPreferenceKey> = settings + ElvinRuntimeKey.entries + ElvinUpdateLongKey.entries + ElvinMaintenanceLongKey.entries
    val legacyBooleans = setOf(ElvinBooleanKey.Recovery, ElvinBooleanKey.Vibration, ElvinBooleanKey.SilentOverride, ElvinBooleanKey.DndOverride, ElvinBooleanKey.Escalate)
    val legacyInts = setOf(ElvinIntKey.StartVolume, ElvinIntKey.MaxVolume, ElvinIntKey.EscalationMinutes)
}
