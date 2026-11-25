# Elvin EI.2 requirements audit

Review date: 25 November 2026. Based on official AAPS 4.0.0, unchanged version code 1500.
All custom controls are under Settings → Elvin Settings. Master is OFF by default.

## Feature status

| Requirement | Status and practical limit |
|---|---|
| Separate custom settings, reset, import/export | Implemented; uses registered AAPS keys and existing encrypted format. Reset only affects Elvin keys. |
| Independent pump/CGM timeout, repeat, snooze, sound, vibration, recovery, silent/DND and volume ramp | Implemented in source. Android audibility needs physical testing; OS policy/stream volume still applies. |
| No CGM record, overdue startup, recovery and persisted snooze | Implemented. No-record timer starts on first observation. Existing stale data can warn immediately. No automatic startup snooze. |
| Background, screen off, reboot | Existing AAPS WorkManager reused; partial wake lock during audio. Best effort, not exact timing. Force-stop requires reopening. Physical tests outstanding. |
| Intentional disconnect, suspension, pod lifetime | Distinct diagnostics. Disconnect and terminal fault/deactivation suppress custom pump warning; suspension does not. Nominal pod expiry does not imply radio failure or bypass lifetime safety. |
| Connection diagnostics and export | Implemented: read-only timestamps/counts, permissions, suppression reasons and last 100 allowlisted sample/alarm events, persisted locally. Status-read counts are not all BLE traffic; sample history is not the full WorkManager database. |
| Nightscout failure monitoring | Implemented for existing V3 queue/response telemetry, silent repeating warnings, intentional pause/disable suppression. Legacy client not covered. |
| Profile change history | Implemented read-only view of existing requested/effective AAPS records, maximum 100 displayed. No duplicate database. |
| Automatic encrypted backups | Implemented opt-in interval adapter using existing encryption, cached password and local/cloud destinations; existing automatic export Automation takes precedence. Phone storage/cloud and password expiry need device tests. |
| Routine update frequency/snooze | Implemented 1–30 days; expiry/signature/objectives/safety restrictions unchanged. Source definition cannot reliably classify every available release as safety-critical. |
| Independent application-death monitoring | Missing external observer. A stopped process cannot monitor itself. Existing AAPS health mechanisms remain. |
| Profile performance comparison | Partial: existing AAPS statistics plus descriptive offline grouping. No causal clinical comparison. |
| 3.5 mmol/L research boundaries, preventive SMB/basal masks | Offline preparation only; independent bounds/masks and hypoglycaemia trace preservation. No live 3.5 target, dosing modification or validated physiological simulator. |
| Version identification/changelog | EI.2 displayed in custom page; official safety version lookup remains 4.0.0. English text; no translations/global search indexing yet. |

## All visible settings

Defaults below apply to a fresh installation. Older EI.1 audio values are inherited until an explicit
channel value is saved. All switches accept ON/OFF. Minutes/hours/days are whole numbers.

| Setting | Default | Permitted range |
|---|---|---|
| Enable Elvin custom functionality | OFF | ON/OFF |
| Monitor pump communication | ON | ON/OFF; inactive while master OFF |
| Monitor CGM readings | ON | ON/OFF; inactive while master OFF |
| Pump / CGM recovery notification | ON / ON | ON/OFF independently |
| Pump / CGM vibration | ON / ON | ON/OFF independently |
| Pump / CGM silent-mode override (alarm stream) | OFF / OFF | ON/OFF independently |
| Pump / CGM DND sound request | OFF / OFF | ON/OFF independently; requires Android policy access |
| Pump / CGM gradual volume escalation | ON / ON | ON/OFF independently |
| Nightscout V3 upload monitoring | OFF | ON/OFF |
| Automatic encrypted settings backups | OFF | ON/OFF |
| Pump timeout | 10 min | 10–300 min |
| CGM timeout | 15 min | 10–300 min |
| Pump / CGM repeat interval | 5 / 5 min | 1–300 min independently |
| Pump / CGM snooze duration | 5 / 5 min | 1–300 min independently |
| Pump sound | 0 | 0 alarm; 1 urgent; 2 error; 3 bolus error |
| CGM sound | 1 | Same 0–3 choices |
| Pump / CGM starting volume | 30 / 30% | 0–100% independently |
| Pump / CGM maximum volume | 100 / 100% | 0–100% independently |
| Pump / CGM escalation duration | 10 / 10 min | 1–60 min independently |
| Routine update reminder frequency | 1 day | 1–30 days |
| Routine update snooze | 1 day | 1–30 days |
| Nightscout failure/stalled queue timeout | 30 min | 10–300 min |
| Nightscout warning repeat | 30 min | 1–300 min |
| Encrypted backup interval | 24 hours | 1–168 hours |
| Profile history window | 30 days | 1–365 days; latest 100 events |

If starting volume exceeds maximum, the effective start is capped at maximum. With escalation OFF,
maximum gain applies immediately. Gain is relative to Android's selected stream volume; a muted
stream remains muted. Sound loops continuously during an active warning; repeats refresh the
notification/vibration without restarting the ramp. Repeat/snooze deadlines are checked on samples,
so a 1-minute interval does not create 1-minute WorkManager scheduling. Previews stop after 30 seconds.

Other controls: diagnostics refresh/export; profile-history refresh; per-channel test/snooze;
stop custom audio; Android notification/DND/battery/Bluetooth/audio settings shortcuts;
routine reminder snooze; encrypted backup now; confirmed reset of Elvin Settings.

Hidden compatibility keys: shared recovery/vibration/escalation defaults ON; shared silent/DND
defaults OFF; shared start 30% (0–100), max 100% (0–100), ramp 10 minutes (1–60). They are exported
to preserve EI.1 restore compatibility, not additional visible controls. Schema marker is 1 (fixed).
Local runtime state/history, snoozes, reminder/backup/upload timestamps and counters are excluded
from export/import; reset clears them. Standard passwords, backup files and Automation events remain.

## Remaining verification and release gates

Automated tests validate policy and host integration, not physical sound, radio links or Doze.
Required phone tests: separate overrides with all silent/DND profiles, zero/low stream volume,
permission revocation during playback, independent concurrent alarms/escalation, official alarm
priority/global mute, previews, notification snooze, no-record CGM, reconnect/recovery, intentional
disconnect/suspension, real DASH fault/expiry, screen-off/Doze/battery saver/OEM controls,
process death/reboot/force-stop, encrypted export/restore/reset, cached-password expiry and
local/cloud failure recovery. Perform pump-state tests in a non-delivering test setup.

No live dosing algorithms, glucose target limits, concentration wrapper or critical version
restriction should differ from the official base. Backup and clean master branches are preserved.
Signing preflight verifies the existing non-debug key but cannot establish installed-phone
certificate compatibility without reading that phone's public certificate. Drive APK delivery is
blocked by stored OAuth refresh `invalid_grant`; reauthorisation is required. GitHub artifacts are
the configured alternative. Final APK assembly, distribution and installation remain deferred.

Run-specific test totals and exact commits belong in the final audit report and CI artifacts.
