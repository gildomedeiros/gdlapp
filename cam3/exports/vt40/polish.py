from pathlib import Path
out=Path(__file__).parent/'files'
pk=out/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming'
p=pk/'VtSettingsConfig.java';s=p.read_text(encoding='utf-8').replace('"yawAccelerationDegreesPerSecondSquared",1,30','"yawAccelerationDegreesPerSecondSquared",.5,30');p.write_text(s,encoding='utf-8')
p=pk/'ComeToMeController.java';s=p.read_text(encoding='utf-8');needle='''        centralDistance=YawAimingMath.distance(in.lat,in.lon,centralLat,centralLon);''';s=s.replace(needle,needle+'''
        if(positioning!=null) {double[] measures=positioning.measures(in);projectedSeparation=measures[0];alignmentError=measures[1];}''')
needle='''        if(!runEnabled) return "Come to me: OFF";''';s=s.replace(needle,needle+'''
        if(positioning!=null)return String.format(Locale.US,
            "Come to me %s · %s · %s\\nProjected %s separation %.1f m (setting %.1f m; start > %.1f m) · Come-to-me %s alignment error %.1f m\\nDirect horizontal surfer distance %.1f m · central %.1f m · fixed destination remaining %.1f m\\nRide %d/%d s · no ride %d/%d s",
            positioning.mode,phase,reason.replace('_',' '),positioning.mode.equals("front")?"shoreward":"alongshore",
            projectedSeparation,settings.filmingDistance,approachStartThreshold(),positioning.mode.equals("front")?"alongshore":"shore-normal",
            alignmentError,distance,centralDistance,approachDistance-approachProgress,rideRemainingMs/1000,settings.rideDurationMs/1000,inactiveMs/1000,settings.inactivityMs/1000);''');p.write_text(s,encoding='utf-8')
p=pk/'YawAimingController.java';s=p.read_text(encoding='utf-8').replace('    private String movementConfigError;\n','').replace('movementScreen=movementConfig.enabled ? "Come to me: ON — waiting for Start" : "Come to me: OFF";','movementScreen="VT 4.0 · JSON configuration loads on Start";');p.write_text(s,encoding='utf-8')
p=out/'Docs/VT_4.0.md';s=p.read_text(encoding='utf-8').replace('yaw acceleration 1–30°/s²','yaw acceleration 0.5–30°/s²');p.write_text(s,encoding='utf-8')
p=out/'detailed_design/detailed_design_v4.0.md';p.parent.mkdir(parents=True,exist_ok=True);p.write_text('''# Detailed design — VT 4.0

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
''',encoding='utf-8')
print('Polished labels, immutable tuning ranges and design documentation')
