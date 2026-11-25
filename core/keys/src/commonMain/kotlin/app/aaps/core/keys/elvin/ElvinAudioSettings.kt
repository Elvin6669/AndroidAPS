package app.aaps.core.keys.elvin

import app.aaps.core.keys.interfaces.Preferences

/** Absent channel keys inherit EI.1 shared values, including after importing an older backup. */
data class ElvinAudioSettings(
    val recovery: Boolean, val vibration: Boolean, val silentOverride: Boolean, val dndOverride: Boolean,
    val escalate: Boolean, val start: Int, val maximum: Int, val minutes: Int
) {
    companion object {
        private val booleanParents = mapOf(
            ElvinBooleanKey.PumpRecovery to ElvinBooleanKey.Recovery, ElvinBooleanKey.CgmRecovery to ElvinBooleanKey.Recovery,
            ElvinBooleanKey.PumpVibration to ElvinBooleanKey.Vibration, ElvinBooleanKey.CgmVibration to ElvinBooleanKey.Vibration,
            ElvinBooleanKey.PumpSilentOverride to ElvinBooleanKey.SilentOverride, ElvinBooleanKey.CgmSilentOverride to ElvinBooleanKey.SilentOverride,
            ElvinBooleanKey.PumpDndOverride to ElvinBooleanKey.DndOverride, ElvinBooleanKey.CgmDndOverride to ElvinBooleanKey.DndOverride,
            ElvinBooleanKey.PumpEscalate to ElvinBooleanKey.Escalate, ElvinBooleanKey.CgmEscalate to ElvinBooleanKey.Escalate)
        private val intParents = mapOf(
            ElvinIntKey.PumpStartVolume to ElvinIntKey.StartVolume, ElvinIntKey.CgmStartVolume to ElvinIntKey.StartVolume,
            ElvinIntKey.PumpMaxVolume to ElvinIntKey.MaxVolume, ElvinIntKey.CgmMaxVolume to ElvinIntKey.MaxVolume,
            ElvinIntKey.PumpEscalationMinutes to ElvinIntKey.EscalationMinutes, ElvinIntKey.CgmEscalationMinutes to ElvinIntKey.EscalationMinutes)
        fun boolean(preferences: Preferences, key: ElvinBooleanKey): Boolean =
            preferences.getIfExists(key) ?: preferences.get(booleanParents[key] ?: key)
        fun integer(preferences: Preferences, key: ElvinIntKey): Int =
            (preferences.getIfExists(key) ?: preferences.get(intParents[key] ?: key)).coerceIn(key.min, key.max)
        fun read(preferences: Preferences, pump: Boolean): ElvinAudioSettings {
            fun bool(p: ElvinBooleanKey, c: ElvinBooleanKey, old: ElvinBooleanKey) =
                preferences.getIfExists(if (pump) p else c) ?: preferences.get(old)
            fun int(p: ElvinIntKey, c: ElvinIntKey, old: ElvinIntKey): Int {
                val key = if (pump) p else c
                return (preferences.getIfExists(key) ?: preferences.get(old)).coerceIn(key.min, key.max)
            }
            return ElvinAudioSettings(
                bool(ElvinBooleanKey.PumpRecovery, ElvinBooleanKey.CgmRecovery, ElvinBooleanKey.Recovery),
                bool(ElvinBooleanKey.PumpVibration, ElvinBooleanKey.CgmVibration, ElvinBooleanKey.Vibration),
                bool(ElvinBooleanKey.PumpSilentOverride, ElvinBooleanKey.CgmSilentOverride, ElvinBooleanKey.SilentOverride),
                bool(ElvinBooleanKey.PumpDndOverride, ElvinBooleanKey.CgmDndOverride, ElvinBooleanKey.DndOverride),
                bool(ElvinBooleanKey.PumpEscalate, ElvinBooleanKey.CgmEscalate, ElvinBooleanKey.Escalate),
                int(ElvinIntKey.PumpStartVolume, ElvinIntKey.CgmStartVolume, ElvinIntKey.StartVolume),
                int(ElvinIntKey.PumpMaxVolume, ElvinIntKey.CgmMaxVolume, ElvinIntKey.MaxVolume),
                int(ElvinIntKey.PumpEscalationMinutes, ElvinIntKey.CgmEscalationMinutes, ElvinIntKey.EscalationMinutes)
            )
        }
    }
}
