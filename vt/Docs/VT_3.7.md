# VT 3.7 — rotation JSON and accessible configuration folder

Version 3.7 / Android versionCode 21. Implemented in the vt-28 working tree only.
The separate local project was inspected (it also reports 3.6 but its movement settings differ); it was not edited.
The previous working-tree APK is preserved at `build/releases/VT-3.6.apk`.

## Configuration folder and upgrade

After installing, stop automatic control and use the overflow menu > Configuration folder.
In Android's picker, create/select Internal storage > Download > VT, then Use this folder.
Android 11+ restricts selecting the Downloads root; select the VT subfolder instead.
The selected folder can be elsewhere if the device's provider requires it; the app uses the actual user-selected URI.
No broad storage permission is requested. Persisted folder access survives ordinary upgrades.

- `vt_gimbal_bands.json`: copy the legacy app-external file byte-for-byte if present, otherwise seed the bundled asset. Never delete or rewrite the legacy file.
- `vt_rotation_speeds.json`: seed the bundled proposed rotation table only if the destination is absent.
- Existing destination files always win, including on repeated folder selection. A failed source read does not replace it with defaults.
- Newly created files are read back and verified. Failed writes are cleaned up only for the file created by that attempt. A folder is adopted only after both files are present/copied and the preference is saved.
- If setup fails or the picker is canceled, the previous folder/legacy configuration remains selected. A successfully copied first file may remain after a later setup failure and will be preserved on retry.
- If the shared gimbal file becomes missing, invalid or unreadable, the legacy gimbal loader is used, with its existing bundled fallback if necessary. Rotation falls back to the original formula when unavailable/invalid. Failures are logged.
- Edit while stopped and press Start to reload. Rotation is read before Start; gimbal retains its once-per-session loading. No live file watching.

## Rotation behavior

JSON schema version 1 contains normal and riding arrays of headingErrorDeg and speedDegPerSec.
Normal points: (0,0), (3,0), (5,1), (10,3.5), (20,8.5), (33,15).
Ride points: (0,0), (3,1.5), (5,5), (10,10), (20,20), (30,30).
Linear interpolation uses absolute heading error; the controller supplies direction. Beyond the last point hold the last speed.
For example riding at 6 degrees requests 6 degrees/s; at 4 degrees requests 3.25 degrees/s because the 3-degree point is unchanged.
The configured rotation maximum (default 15 degrees/s) still caps the result. Set it to 30 to allow the full ride curve.
The normal table holds 15 degrees/s above 33 degrees even if the configured maximum is higher; edit the JSON to raise it.
Configured acceleration/deceleration (default 8 degrees/s squared), immediate zero at normal 3-degree tolerance,
shortest-turn selection, sign-change stop and the 2-second surfer reversal block are retained.
Approach/return use normal curve toward the saved heading without the surfer reversal block.
Retreat continues surfer aiming, selecting the ride curve while riding and normal otherwise. Automatic gimbal continues unchanged.
Movement maximum/default, retreat defaults/timers, ride detection, saved navigation, retained GPS, recording,
manual/control-loss/RTH/telemetry protections and touch lock are unchanged from VT 3.6.

## Validation and logging

Rotation JSON is bounded to 64 KiB, schema version 1, 2-32 points per curve, finite numeric fields,
strictly increasing errors in 0-180 degrees, nondecreasing speeds in 0-30 degrees/s, first point (0,0),
and required normal (3,0) tolerance point. Invalid input retains the original formulas, including configured caps.
Session logs include rotation_speed_config (source, path, contents, validation error, maximum and acceleration).
Aiming cycles include rotationCurve, rotationConfigSource, desiredYawRate before ramp, requestedYawRate and submittedYawRate.
Gimbal logs retain effective JSON/source and report shared-folder failures alongside the legacy result.
Settings UI, APK metadata and full-log version report 3.7.

Run `tools/test-aiming.ps1` and offline `:sample:assembleDebug` in `SampleCode-V5/android-sdk-v5-as`.
Tests cover default normal equivalence, interpolation/caps/ramp/reversal, malformed files, immutable snapshots,
retreat/ride/NOFIX session integration, manual pause, existing-file preservation and failed migration verification,
production storage fallback with a simulated transport, and independent parsing of JSONL command evidence.
The Android build validates the real DocumentsContract and picker integration. Device folder-picker/persisted-grant,
USB editing and flight behavior require on-device verification; JVM transport tests do not simulate Android providers.
