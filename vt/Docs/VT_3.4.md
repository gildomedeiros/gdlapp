# VT 3.4 — JSON-configured gimbal distance bands

Version 3.4, Android versionCode 18. Replaces VT 3.3 long/close gimbal selection only.

## Configuration

Bundled file: `SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets/vt_gimbal_bands.json`.
At each VT Start, load the override from `getExternalFilesDir(null)/vt_gimbal_bands.json`.
Typical phone path: `/storage/emulated/0/Android/data/com.dji.sampleV5.aircraft/files/vt_gimbal_bands.json`.
On first use, seed a missing file from the bundled defaults. Edit the JSON while VT is stopped;
restart VT to load changes. No in-flight hot reload and no pitch settings in the UI.
Access to Android/data depends on Android/device; use ADB or edit the bundled asset and rebuild.
An existing override takes precedence over a changed APK asset.
Invalid or unreadable override falls back to bundled defaults without overwriting the bad file.
Maximum file size 64 KiB; 1–32 bands; finite numeric values; increasing positive boundaries up to
10000 m; final boundary must be null; pitch -90..0 degrees; buffer 0..100 m.

| Initial distance | Pitch | Outward switch | Inward switch |
|---|---|---|---|
| 0–20 m | -35° | >25 m | — |
| >20–30 m | -25° | >35 m | <20 m |
| >30–40 m | -19° | >45 m | <30 m |
| >40–50 m | -16° | >55 m | <40 m |
| >50 m, unlimited | -12° | — | <50 m |

Startup chooses the nominal band from fresh actual drone–surfer horizontal distance.
Subsequent switching retains pitch in a 5 m buffer above each boundary. Equality does not
cross a boundary. A jump 15→35 m selects -25°; 15→35.01 m selects -19° directly.
Large jumps can skip multiple bands. Selected pitch is clamped to fresh hardware pitch limits.
The final band extends beyond 60 m. Bands assume roughly 10.5 m height, do not compensate for
height, and do not include a separate Top preference. Filming distance is independent.
An approach ending just above 20 m may retain -25° until it crosses below 20 m.

## Retained behaviour

Only pitch is commanded; yaw/roll are ignored. Retain the two-second gimbal transition and
three-second submission throttle, pending-callback handling, bounded retries, control/telemetry
checks and fresh-surfer-GPS requirement. Outages retain the selected target; they do not trigger
new bands. Existing queued commands may finish. Band changes do not continuously override the
RC wheel. Manual-control pauses defer band evaluation until control is eligible again.
Come to me, saved navigation, yaw aiming, ride policy, recording prerequisite, touch lock,
height display/logging and movement settings are unchanged. Height is manually controlled.
Legacy long/close preferences remain stored for compatibility but are not used by the new policy.

## Logs

- `gimbal_band_config`: session, file path/source, full input file contents (when readable),
  effective JSON, validation/fallback error, buffer. Oversize/unreadable files log their error.
- `gimbal_band_switch`: previous band (0 means no selection), active band (one-based), distance,
  GPS age, all crossed thresholds, startup/increase/decrease reason, configured and clamped pitch,
  actual pitch and whether a command is needed.
- `gimbal_band_deferred`: changed reason for unavailable control, stale GPS/attitude/range,
  pending command or cooldown; `none` means deferral cleared.
- `gimbal_pitch_command/result`: command ID, target, acceptance/failure; command includes band,
  distance, buffer, GPS age, trigger and retry count.
- `gimbal_pitch_reached`: command ID/band, target and actual pitch, elapsed time; within 1°
  after at least 2 s with fresh attitude. Acceptance alone does not prove target reached.
- `gimbal_pitch_cycle`: active band, target/actual, fresh flags, distance, result and deferral.

## Validation

Run `tools/test-aiming.ps1` and offline `:sample:assembleDebug`. VT 3.4 tests exercise production
JSON parsing, custom buffers, boundaries, skipped bands, stale inputs, hardware limits and reset.
VT 3.3 legacy pitch tests remain as historical regression coverage; production uses GimbalBandPolicy.
Physical flight/framing validation remains required; 30/40/50/60 m angles are estimates.
