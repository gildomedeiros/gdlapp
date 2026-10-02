# VT 3.5 — timed backward retreat

Version 3.5, Android versionCode 19. Implemented in the vt-28 working tree only.
Preserves the uncommitted VT 3.4 baseline. Physical flight validation remains pending.

## Configuration

The stopped-only Movement and retreat settings form adds four persisted fields:

| Setting | Default | Accepted range |
|---|---:|---:|
| Minimum retreat distance | 25 m | 1–200 m |
| Retreat duration | 10 seconds | 1–60 seconds |
| Fixed retreat speed | 3 m/s | 0.1–3 m/s |
| Come to me cooldown after retreat | 5 seconds | 0–60 seconds |

Existing stored filming, ride, yaw and gimbal settings are preserved. Missing retreat
preferences get these defaults; values are fixed for an explicit VT session. Retreat
uses the existing automatic movement enable state and requires the existing initial
hover/central capture. Retreat speed is independent of approach acceleration and arrival
slowdown. The existing 3 m/s flight envelope is retained; no higher speed is introduced.

## Behaviour

Below the minimum actual horizontal surfer–drone separation, cancel an active approach
and command body-backward velocity at the configured fixed speed. Normal surfer yaw
and automatic JSON gimbal bands remain active. Rotation therefore changes backward
travel direction. Lateral and vertical commanded velocity remain zero.

Use the last valid surfer position acquired in this session and fresh aircraft telemetry.
Ordinary packet gaps and explicit NOFIX can trigger or continue retreat. New valid fixes
replace the retained position. Never feed the cached fix as new ride evidence.
Outside retreat and its cooldown, the pre-existing explicit-NOFIX pause is unchanged.
Starting a new VT session still requires fresh GPS and the recording prerequisite.

Each period lasts the full configured duration unless an existing protection interrupts it.
At expiry, repeat immediately if still below the minimum; otherwise submit zero translation
and start the Come to me cooldown. Equality with the minimum does not trigger/repeat.
Repeated periods have no neutral tick, no acceleration restart and no cooldown between them.
The cooldown blocks forward approaches only: another retreat may start during it and the
same cooldown starts again after that last retreat. Yaw and gimbal remain available during
cooldown, including retained-GPS operation. No new approach uses an old surfer fix.

Retreat works during rides; the fixed ride clock and fresh-fix detection still run.
An existing Return to central retains its saved navigation ownership. New no-ride return
planning waits during retreat/cooldown rather than competing for the translation command.
The movement timeout latch and existing 249 m excursion stop remain effective.

Manual stick intervention, aircraft/heading/velocity/connection faults, RTH/landing,
control loss, Stop, lifecycle cancellation and loop stalls retain existing priority.
A protection pause cancels the retreat timer; it never preserves leftover seconds.
Existing recovery and manual re-enable rules decide when automation is permitted again;
then a close-distance check can start a new full period. No retreat command is submitted
with invalid aircraft telemetry. Neutral submission/release uses the existing authority
and telemetry requirements; software cancellation is not proof of physical stopping.

At the 3 m/s default, 10 seconds commands about 30 m of backward travel, not a guaranteed
increase in separation. Aircraft acceleration, braking, turns and surfer movement affect it.

## Logging

Structured full-log events: `retreat_settings`, `retreat_started`, `retreat_repeated`,
`retreat_finished`, `retreat_cancelled`, `retreat_approach_replaced`,
`retreat_cooldown_started`, `retreat_cooldown_ended`, and `retreat_cycle`.
The settings form also emits `retreat_settings_saved`.

Records include session/cycle IDs, reason, all four settings, active state, period number,
start/period times, elapsed/remaining time, cooldown remaining, start/current separation,
target coordinates/sequence/age and retained status, aircraft coordinates/heading,
requested yaw/forward velocity and actual SDK submission evidence (null when not submitted).
Join `gimbal_pitch_cycle`, `gimbal_band_switch`, `orientation_cycle` and `yaw_command` by
session/cycle for measured camera orientation and commanded motion. Gimbal command/cycle
records explicitly identify retained retreat targets. Logging failures never authorize
or prevent motion. Existing full/minimal logging controls and bounded storage remain.

## Validation

Run `tools/test-aiming.ps1` for all existing scenarios plus VT 3.5 session tests covering
fixed speed, exact timer/cooldown boundaries, repeated retreat, no early distance stop,
NOFIX/gaps, live aircraft position/heading, ride compatibility, active approach replacement,
manual/telemetry/control/late-read cancellation, recovery with a new timer, excursion limits,
settings validation and independently parsed JSONL evidence.
Build offline from `SampleCode-V5/android-sdk-v5-as` with `:sample:assembleDebug`.
Flight/framing and aircraft response to fixed-speed commands require device testing.
