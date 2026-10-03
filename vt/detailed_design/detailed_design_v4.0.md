# Detailed design — VT 4.0

See [VT 4.0 behavior and configuration](../Docs/VT_4.0.md) for the complete settings, capture workflow, movement/retreat arbitration, validation and device limits.

## Components and data flow

1. `SharedConfigStorage.ensure40` performs one-time absent-only seeding. `VtSessionConfig.load40` validates retreat, rotation, gimbal, movement and shoreline JSON before returning an immutable session snapshot. No movement preferences are loaded. Start publishes the snapshot only after rechecking screen/intent cancellation, then uses the existing acquisition prerequisites.
2. `VtSettingsConfig` validates all movement fields and the mode-independent retreat-plus-5 minimum. `ShorelineLibrary` validates profiles and resolves the selected ID. `ShorelineGeometry` establishes the A/B alongshore unit vector and explicit sea-normal unit vector. `ShorelinePositioning` derives mode axes, signed projections, one-time destination and initial segment clearance.
3. `ComeToMeController` retains central/ride/no-ride/return state. Production receives an immutable shoreline preset on session start. A fresh valid planning fix creates either a projected reapproach or an alignment-only fixed endpoint. Target coordinates and planning fix time stay unchanged until arrival, ride interruption, retreat replacement, manual reposition or Stop. Only live aircraft telemetry updates the travel vector. Legacy constructors remain for existing regressions; production movement never starts without a valid selected preset.
4. `AimingSession` selects surfer yaw during a preset approach and existing navigation yaw during a return. Translation consists of forward plus right BODY velocity; both are zeroed on veto. Retreat overrides forward and sets right to zero. The final snapshot rejects any changed position/heading that makes the calculated vector obsolete.
5. `YawAimingController` serializes control, capture polling and storage operations on the existing executor. The final Android adapter repeats authority/input checks and records actual axes only after SDK submission. `AimingMotionCommand` maps BODY forward to velocity-mode roll and BODY right to velocity-mode pitch, retaining neutral vertical velocity.
6. `ShorelineSetupUi` provides stopped-only library operations and capture/manual drafts. `ShorelineCapture` collects distinct accepted fresh LoRa packets, requires stationary scatter and handles NOFIX visibly. A draft preview draws straight A/B and a sea-side arrow. External Maps opens the real A/B geographic points; no embedded Maps SDK or API key is introduced.
7. `MovementCycleLog` records preset mode, shoreline ID, projected separation, other-axis alignment error, journey kind, frozen planning-fix timestamp, fixed destination and both requested/submitted axes. `AimingCycleLog` records surfer yaw ownership while translating.

## Retained behavior

Return bearing/backward progress, the 249 m excursion stop, fixed ride timer, speed-jump rejection, no ride-end return, arrival braking, late authority cancellation, control release confirmation, recording prerequisite, gimbal bands and bounded stale-GPS retreat allowance remain in the existing components.

## Storage failure model

No silently repaired malformed configuration. Missing files after setup block Start. User-authorized shoreline mutations retain backups, verify content and attempt rollback. SAF does not provide a general multi-file atomic rename/transaction, so a successful profile write with failed selection is reported as partial success and recoverable through Select. JSON tuning is never independently copied into UI preferences.
