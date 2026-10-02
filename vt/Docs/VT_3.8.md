# VT 3.8 — bounded retreat without fresh GPS and JSON retreat settings

Version 3.8 / Android versionCode 22. Implemented in the vt-28 working tree only.
The previous VT 3.7 APK is preserved at `build/releases/VT-3.7.apk`. The separate local project is not updated.

## Retreat settings file

`vt_retreat_settings.json` is added to the already selected configuration folder, normally Internal storage / Download / VT.
No new folder selection is required for an existing VT 3.7 selection. On the first Start attempt after upgrade,
the new file is created with defaults only if absent. Existing retreat, rotation and gimbal files are never overwritten.
No saved UI retreat values are migrated. Old retreat preferences are ignored. Selecting a new folder also seeds the new file if missing.
After successful setup, deleting the retreat file blocks subsequent Start attempts; it is not silently recreated on every Start.
The existing Configuration folder action can explicitly set up missing files again.

```json
{
  "version": 1,
  "enabled": true,
  "minimumDistanceMetres": 23,
  "durationSeconds": 3,
  "speedMetresPerSecond": 5,
  "comeToMeCooldownSeconds": 5,
  "maxStartsWithoutFreshGps": 1
}
```

Retreat tuning fields are removed from the settings form. Other movement/ride/rotation settings remain in the UI.
Allowed values: boolean enabled; separation 1–200 m; duration 1–60 s; speed 0.1–5 m/s;
cooldown 0–60 s; whole-number bad-GPS starts 0–100. Zero disables starts without fresh GPS.
Come to me still needs to be enabled for retreat eligibility, as before.

## Bad-GPS retreat allowance

At the existing retreat arbitration, reuse the fresh-input validation result. A fresh target is valid and no older than
the existing 3-second window, with valid drone/control inputs; explicit NOFIX is unavailable immediately.
No extra GPS check is added merely because a period starts. A fresh valid fix with a new reception timestamp restores the allowance.

- Fresh GPS: start/repeat whenever the normal distance and protection gates allow it; does not spend the stale allowance.
- Stale GPS or NOFIX: start from the retained target only while startsWithoutFreshGps is below the configured maximum; increment on each start/repeat.
- Signal lost during a running period: complete its timer without reclassifying its start. Existing protection pauses still cancel the timer.
- Exhausted allowance: finish the active period, stop backward translation and block new starts until fresh GPS restores the allowance.
- Cooldown, protection pause, recovery and period completion do not reset the count. A new explicit session starts a new count, but existing Start prerequisites require fresh target data.
- Fresh valid fixes can restore the count during recovery; a pause itself cannot.

Example default: good GPS starts a three-second retreat, then LoRa disappears. That period completes. If the retained
position still indicates less than 23 m, one further three-second period can start. After that period, no more retreat
starts are allowed during a 90-second outage. Sufficient separation can stop repeats earlier. No physical distance or
obstacle-clearance guarantee is implied by the time/count limit.

## Start-blocking validation

All three JSON files are read and validated together before aircraft control is acquired. A valid immutable snapshot
is applied only after every file succeeds. Missing/unreadable/malformed/invalid files block Start with a persistent
dialog naming the file/problem and asking the user to correct the file or folder access. Errors are logged.
No fallback to bundled JSON, legacy gimbal files or the previous session is used by the VT 3.8 Start path.
Duplicate JSON keys, trailing content, oversized input and excessive nesting are rejected rather than silently resolved.
Retreat unknown fields and invalid types/ranges are rejected. Saved non-retreat movement preference validation errors
also block Start until explicitly corrected/saved in the UI.
The default retreat file's one-time creation is intentional setup, not a validation fallback.

Start loads rotation and gimbal snapshots before activation. Gimbal no longer opens files from its control-cycle update.
Edits during a session do not alter that session. STOP or screen loss during file loading still vetoes activation.

## Logging

- retreat_config_created: one-time default file creation.
- retreat_settings_config: effective JSON, selected folder, source and upcoming session.
- retreat_started / retreat_repeated: existing events now include gpsFresh, gpsAgeMs, separation,
  maxStartsWithoutFreshGps, startsWithoutFreshGps and staleLimitBlocked.
- retreat_start_blocked with reason stale_gps_retreat_limit: once per blocked episode, not every tick.
- retreat_gps_allowance_reset: fresh_valid_fix plus previousStartsWithoutFreshGps and reset count.
- Existing completion/cancellation/cooldown/submission evidence remains; the allowance also appears in cycle logs.
- configuration_start_blocked: visible Start failure also recorded in logs.
- Full-log header, settings title and APK metadata report 3.8.

## Preserved behavior and verification

Approach/return speed profile, saved navigation, filming distance, ride detection/timer, retreat speed/duration/cooldown
defaults, aiming and gimbal behavior during retreat, manual/control-loss/RTH/telemetry protections and touch lock remain.
The intended behavioral differences are the bad-GPS start limit and strict preflight configuration handling.

Run `tools/test-aiming.ps1` and offline `:sample:assembleDebug` from `SampleCode-V5/android-sdk-v5-as`.
Regression tests include actual-session NOFIX and ordinary 90-second packet gaps, 0/1/2/3 allowances, no early timer
cancellation, fresh-GPS repeats, exhausted allowance across pause/cooldown, fresh-fix reset, settings defaults/types/ranges,
missing/invalid files, duplicate keys, permission failures and independent JSONL block/reset evidence.
Historic repeat fixtures explicitly use a large configured allowance to retain their old repeat/cancellation coverage.
Android folder-provider/device behavior and flight performance still need device validation; no installation/flight is performed here.
