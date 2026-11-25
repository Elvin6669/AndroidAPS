# Version restrictions and isolated glucose research

## Existing update and safety mechanisms

`VersionCheckerUtilsImpl.triggerCheckVersion` reads the bundled/remote allowed-versions definition,
selects a newer version by Android API, and compares the numeric version prefix. `onNewVersionDetected`
posts the low-priority availability reminder. The upstream reminder runs after noon and normally uses
`warnEvery(0)`, which results in daily reminders.

If a newer version is available or the build is development, the checker looks up an expiry date by
the exact current version name. It parses the local calendar date, adds one day, and stores it in
`LongComposedKey.AppExpiration`. Expiration warnings use a separate path: seven-day intervals when
more than 28 days remain, three-day intervals when more than 14 days remain, otherwise daily.
`VersionCheckerPlugin.applyMaxIOBConstraints` invokes the checker and sets maximum IOB to zero
after a nonzero recorded expiration. EI does not change the expiration date, lookup key,
version definition or maximum-IOB constraint.

EI changes only the ordinary newer-version reminder while the EI master switch is on: 1–30-day
frequency, separately stored last reminder, and a user-selected 1–30-day routine snooze. It still
returns “new version detected” to the upstream caller, so expiration handling continues. No routine
snooze suppresses expiration warnings. Upstream's upcoming-expiry branch reads the routine warning
timestamp but writes `LastExpiredWarning`. With EI enabled, this branch reads its own existing
`LastExpiredWarning` timestamp, preserving the intended 7/3/1-day cadence independently of routine
reminders; it does not emit an “upcoming” notice after expiration. The expired critical warning path
and IOB restriction remain active. With EI disabled the original warning path is retained.
The page displays the current recorded expiration date.
An unknown date is explicitly unknown, not a promise that this version never expires.

The allowed-versions definition does not provide a trustworthy optional-versus-safety-critical flag
for every release. EI labels its availability message “routine” and directs the user to release notes
for safety significance. Expiration is displayed separately as a safety restriction. It does not invent
a safety-critical classification or downgrade one to optional.

Other unchanged restrictions include SignatureVerifierPlugin's revoked/illegal-signature loop gate,
ObjectivesPlugin progression gates, BG quality checks, SafetyPlugin limits and hard limits. These
are not ordinary update nagging. Existing data viewing, diagnostics and settings export are not
blocked by the new EI reminder; no critical restriction is bypassed to provide them.

## Glucose-control investigation

Relevant unchanged source:

* `core/interfaces/.../utils/HardLimits.kt`: ordinary profile low 80–180.2 mg/dL, high 90–200 mg/dL,
  overall target 80–200 mg/dL. Temporary low/high ranges are separate (low 72–180.16, high 72–270,
  overall temporary target 72–200 mg/dL). A 3.5 mmol/L target is below existing live lower limits.
* `implementation/.../utils/HardLimitsImpl.kt`, profile validity checks and profile/temp-target editors:
  range validation/conversion. Lower and upper bounds already exist independently in profiles;
  the determine-basal algorithms commonly use their average as the working target.
* `plugins/aps/.../openAPSSMB/DetermineBasalSMB.kt`: SMB enable conditions, eventual/predicted glucose,
  min/max target handling, low-glucose suspension, insulin requirement, SMB caps and temporary basal.
* `plugins/aps/.../openAPSSMB/OpenAPSSMBPlugin.kt`: algorithm invocation and constraint input.
* `plugins/aps/.../openAPSAMA/DetermineBasalAMA.kt` and
  `openAPSAutoISF/DetermineBasalAutoISF.kt`: other algorithms with distinct interactions.
* `core/keys/.../BooleanKey.kt` / `IntKey.kt` / `DoubleKey.kt`: SMB enable, always/COB/temp-target/
  after-carbs conditions, UAM, high-temp-target settings and bolus/basal limits.
* `plugins/constraints/.../safety/SafetyPlugin.kt`, BG quality, objectives and
  `plugins/constraints/.../ConstraintsCheckerImpl.kt`: independent safety constraints.

Current glucose inside a range does not mean additional insulin is exclusively preventive or safe
to withhold. The algorithms also use predicted glucose, IOB, COB, deviation/trend, sensitivity,
dynamic ISF/AutoISF, activity, insulin concentration/kinetics, scheduled basal, temp targets, SMB
limits and BG quality. SMB and basal increases must be evaluated separately. Predicted-low and
low-suspend actions must remain in a baseline trace. No live setting, hard limit or dosing code is
changed for this request.

## Offline research harness

`tools/elvin-research/analyse.mjs` runs only in Node, outside all Android/APS dependency graphs.
It accepts recorded JSON rows with timestamp/glucoseMmol (or Nightscout date/sgv), optional profile
label, baseline `proposedSmb` and `proposedBasalIncrease` booleans, and a `hypoProtection` trace.

Example:

```
node tools/elvin-research/analyse.mjs input.json output.json 3.5 6.0 --withhold-smb --withhold-basal
```

Lower/upper research bounds and two hypothetical exclusion masks are independent. Input records
are not changed. Hypoglycaemia-protection traces are preserved. Missing baseline proposals remain
unknown. Output contains descriptive sample counts by profile and labels decisions that a research
policy would exclude. It generates no insulin quantities, pump commands, treatment files or live
settings imports.

This is preparation for retrospective evaluation, not a validated physiological simulation. Recorded
glucose cannot predict the counterfactual outcome after withholding insulin. Counts are not
time-weighted TIR; profile groups are confounded by meals, activity, illness and treatment changes.
A next research stage needs a validated simulator, baseline algorithm replay, aligned IOB/COB and
insulin kinetics, trace comparison for all low-glucose protections, missing-data rules and separate
SMB/basal scenarios. No historical patient data was retrieved or fabricated for this development.

## Signing and delivery

Existing GitHub secrets and their formats are preserved. The no-APK validation workflow never
reads signing or Drive secrets. A separate no-APK preflight signed and verified a disposable JAR
using the existing key. Its certificate is not Android Debug, is valid until 1 April 2046, and has
SHA-256 fingerprint `D5:C3:03:82:2D:B9:1F:FC:52:94:12:7B:F0:04:76:6B:D3:84:7D:DC:6A:17:93:9B:1B:61:4C:12:A2:11:0B:8F`.
Certificate matching with the installed phone remains unresolved; no phone was accessed.
The read-only Drive preflight failed at OAuth refresh: HTTP 400 `invalid_grant`. The stored
refresh credential needs reauthorisation before automatic Drive APK delivery can work. GitHub
artifact delivery remains configured. In-app backup provider credentials are separate.
No signing key or repository secret was regenerated or changed. No APK was assembled or installed.
