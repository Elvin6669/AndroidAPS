# Elvin development — 4.0.0-EI.1

Base: official tag `4.0.0`, commit `f77abd9b82b308e8fc330da0d7a31370fb4a0f49`.
All EI development belongs on `custom-aaps`. `master` remains the exact official source;
`backup-aaps-3.4.2.1` preserves the original six fork commits.

## Scope and use

Open **Settings → Elvin Settings**. The master switch is OFF on a fresh installation.
Custom monitoring is additive: it never disables, snoozes, resets, or dismisses official
pump/CGM alarms. The only IDs it dismisses are its own. Reset removes only registered EI keys.
The underlying official version name/code remain 4.0.0/1500, preserving expiration-key lookup.
EI.1 identification is displayed on the custom page rather than appended to the safety version key.

Suggested editable defaults: pump 10 minutes, CGM 15 minutes, separate 5-minute repeats and
snoozes, 30% starting/100% maximum gain, 10-minute escalation. Thresholds accept 10–300 minutes.
Pump and CGM each have an enable switch, threshold, repeat interval, snooze duration and sound.
Recovery notification, vibration, silent-mode alarm stream, DND request and volume ramp are
shared custom audio options. Silent/DND overrides default OFF. Sound choices reuse four
bundled AAPS resources. The test buttons preview for at most 30 seconds; Stop custom audio
and the existing global alarm mute stop custom playback. Official audible alarms take priority.

Volume percentages are MediaPlayer gain relative to the user's Android stream volume, not an
absolute sound-pressure level or a forced system volume. A 100% custom cap cannot make a muted
Android stream audible. Android DND policies can still block alarm audio even with policy access.
No DND filter, global volume, alarm permission, battery exemption or Bluetooth permission is
changed automatically. The page exposes status and opens the appropriate system settings.

## Monitoring and limitations

`LocalAlertUtilsImpl.checkStaleBGAlert` calls the additive monitor before the official no-CGM
early return. It runs through the existing periodic maintenance/KeepAlive path and manual
diagnostics refresh. No new pump command, connection retry or insulin-delivery operation exists.

* Existing stale data can alarm on the first sample; custom monitoring has no automatic startup
  snooze. When there has never been a record, a watch starts when enabled and alarms after its
  timeout. The watch, repeat timestamp, active flag and snooze survive process restart locally.
* `lastDataTime` is pump communication freshness, not proof that a Bluetooth attempt just failed.
  AAPS's normal status freshness check is 15 minutes. A 10-minute custom setting can therefore
  warn about stale data before the next normal status read; it does not create faster polling.
* Intentional `DISCONNECTED_PUMP` mode suppresses the custom pump warning only. Suspension does
  not suppress stale communication. DASH supplies read-only terminal/fault/deactivated state and
  nominal pod expiry. Terminal state is reported separately and leaves official pod alarms active.
  Nominal expiry is not used to infer radio failure or silently override pod lifetime enforcement.
* WorkManager is best effort. Existing periodic work plus delayed samples aim at five-minute
  sampling, not exact deadlines. Doze, screen-off restrictions, force-stop, reboot and vendor
  battery controls need physical testing. No exact-alarm or foreground-service timing claim is made.
* A process cannot report its own total failure while it is dead. Independent app-health monitoring
  requires an external observer/phone/watch/Nightscout service; EI.1 does not pretend otherwise.
* Diagnostics history is bounded to 100 allowlisted events in the current process. Worker history
  means monitor samples, not the complete WorkManager database. Pump attempt counters cover
  `CommandReadStatus`, not every BLE transaction. Nightscout counters cover V3 upload responses
  and exceptions; they do not cover the legacy client or mean an entire sync queue completed.
  A zero timestamp means no observation in this process, not a successful upload.

Diagnostic export shares only the constructed report: timestamps, durations, booleans, status
labels, counts, suppression reasons and the bounded history. It never exports general AAPS logs,
preference dumps, glucose values, pod identifiers, Bluetooth addresses, URLs, response bodies,
tokens, credentials or authentication headers. The Android share chooser is user initiated.

## Settings persistence

The `elvin_` namespace is registered in PreferencesImpl at construction, before screen or monitor
creation. Ordinary settings are included by the existing encrypted AAPS export/import system.
Runtime watch state and routine-reminder snoozes are non-exportable, so another device cannot
inherit a snooze. Schema 1 is a new namespace with absent-key defaults; old AAPS keys are not
migrated or overwritten. Malformed runtime JSON is discarded. Monitor inputs are clamped even
when imported preferences contain out-of-range numbers. Reset does not clear the application store.

## Upstream mechanisms already present

* Encrypted settings export/import: `implementation/.../maintenance/ImportExportPrefsImpl.kt`,
  `LocalImportExportPrefs.kt`, `PreferenceImportApplier.kt`, secure export-password storage and
  cloud storage providers. Automation's `ActionSettingsExport` supports scheduled unattended
  export when the user enables it and configures a stored password. EI uses this, with no duplicate
  backup scheduler or new credential store.
* Profile history: `ui/.../compose/treatments/ProfileSwitchScreen.kt` and persisted profile/effective
  profile switches. Statistics already include TIR, AGP and TDD. EI does not create a competing
  profile-history database. Offline research adds descriptive groups, not causal performance claims.
* Nightscout: existing queue, upload retries/logs and status UI remain unchanged. EI adds numeric,
  allowlisted V3 upload observations. It does not install a new upload-failure safety alarm.
* Background health: existing KeepAlive scheduling, stalled-worker diagnostics and permission
  notifications remain active. EI exposes its own observations and permission snapshot.

## Integration points (all upstream edits)

1. `implementation/.../sharedPreferences/PreferencesImpl.kt`: register EI exportable and local keys.
2. `implementation/.../alerts/LocalAlertUtilsImpl.kt`: optional injected additive monitor hook before
   missing-record return. Original alarm policy remains unchanged after the hook. Monitor catches
   ordinary failures so they do not prevent the official stale-BG path; cancellation remains intact.
3. `core/interfaces/.../pump/Pump.kt`: read-only context with a default for all existing drivers.
4. `pump/omnipod/dash/.../OmnipodDashPumpPlugin.kt`: read-only context getter. No command or delivery
   method changes.
5. `core/interfaces/.../notifications/NotificationId.kt`: append three EI IDs, preserving prior ordinals.
6. `implementation/.../notifications/CommonNotificationManager.kt`: signal custom audio on global mute;
   include custom IDs in dismissal while preserving the original official alarm selection/playback.
7. `implementation/.../queue/commands/CommandReadStatus.kt`: timestamp/count observations around the
   existing operation. Results and thrown exceptions retain their original behaviour.
8. `plugins/sync/.../nsclientV3/NSClientV3Plugin.kt`: record numeric HTTP-success/failure observations and
   exception counts without recording response contents or changing retries, cursors or outcomes.
9. `ui/.../search/BuiltInSearchables.kt`: inject the custom screen provider.
10. `ui/.../compose/preferences/AllPreferencesScreen.kt`: one navigation entry for Elvin Settings.
11. `plugins/constraints/.../versionChecker/VersionCheckerUtilsImpl.kt`: opt-in routine availability
    reminder frequency/snooze only. Expiration lookup, expiration warnings and max-IOB constraint
    code remain unchanged. See UPDATE-AND-RESEARCH.md.
12. `.github/workflows/aaps-ci.yml`: retain existing signing/Drive formats, update SDK installation.
    Separate `elvin-validation.yml` uses no signing secrets and never assembles/distributes APKs.
13. `implementation/src/androidMain/AndroidManifest.xml`: declare notification-policy access for
    the opt-in DND grant screen. Access is still granted only by the user in Android settings.

All new runtime, UI, keys, tests and research files live under `elvin` directories where possible.
User-facing custom text is currently English; translations and global-search indexing remain future work.

## Validation

Pure policy tests: `./gradlew -p tools/elvin-policy-tests test` (JDK 21, no Android SDK required).
Offline analysis tests: `node --test tools/elvin-research/*.test.mjs`.
Android integration and existing constraint tests are in the no-APK validation workflow, including
application Kotlin compilation to exercise the Metro graph. No APK signing secrets are used.

Physical-device checklist: fresh disabled defaults; enable/disable/reset/import; separate sounds;
30-second previews; snooze/repeat/recovery; global mute; official alarm precedence; real missing
CGM data; intentional disconnection; suspension; pod fault/expiry; permissions revoked during
playback; stream volume zero; silent mode; all DND profiles; screen off/Doze; battery saver;
process death/reboot; and manufacturer battery management. Use an isolated non-delivering test
setup when exercising pump state. No production APK should be installed based on policy tests alone.
