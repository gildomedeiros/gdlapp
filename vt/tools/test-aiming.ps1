# CAM3 v2.0: Run deterministic control tests with the local JDK; never connect to aircraft.
param([string]$JavaHome = 'C:/Program Files/Java/jdk-21')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$package = 'dji/v5/ux/sample/showcase/defaultlayout/aiming'
$source = Join-Path $projectRoot "SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/$package"
$test = Join-Path $projectRoot "SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/$package/AimingSessionTest.java"
$output = Join-Path $projectRoot 'build/aiming-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
# CAM3 v2.2: Exercise the shared flight-mode classification with the state-machine tests.
& "$JavaHome/bin/javac.exe" -d $output "$source/RotationSpeedCurve.java" "$source/TranslationSpeedAllowance.java" "$source/RetreatSettings.java" "$source/RetreatController.java" "$source/ComeToMeSettings.java" "$source/ComeToMeController.java" "$source/AngleRoutePlanner.java" "$source/ShorelineGeometry.java" "$source/ShorelinePositioning.java" "$source/ShorelineCapture.java" "$source/FullLogOpenPolicy.java" "$source/YawAimingMath.java" "$source/AimingFlightModes.java" "$source/AimingSession.java" "$source/SurferYawController.java" "$source/RideDetector.java" "$source/LoRaTelemetry.java" (Join-Path (Split-Path $test) "LoRaTelemetryTest.java") $test
if ($LASTEXITCODE -ne 0) { throw 'Aiming tests did not compile' }
& "$JavaHome/bin/java.exe" -cp $output 'dji.v5.ux.sample.showcase.defaultlayout.aiming.AimingSessionTest'
if ($LASTEXITCODE -ne 0) { throw 'Aiming tests failed' }
& "$JavaHome/bin/java.exe" -cp $output 'dji.v5.ux.sample.showcase.defaultlayout.aiming.LoRaTelemetryTest'
if ($LASTEXITCODE -ne 0) { throw 'LoRa tests failed' }

# Use the exact provided SDK jar already cached for the application build.
$sdkJar = (Get-ChildItem "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.dji/dji-sdk-v5-aircraft-provided/5.18.0/*/*provided-5.18.0.jar" | Select-Object -First 1).FullName
if (!$sdkJar) { throw 'DJI 5.18.0 provided jar missing; build the application first' }
& "$JavaHome/bin/javac.exe" -cp "$output;$sdkJar" -d $output "$source/YawOnlyCommand.java"
if ($LASTEXITCODE -ne 0) { throw 'Command tests did not compile' }
# DJI's provided jar contains non-executable stubs. Inspect the compiled factory instead of
# pretending these stubs can execute in a JVM. On-aircraft command behavior needs device tests.
$bytecode = (& "$JavaHome/bin/javap.exe" -c -classpath $output 'dji.v5.ux.sample.showcase.defaultlayout.aiming.YawOnlyCommand') -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect command bytecode' }
foreach ($field in @('Roll', 'Pitch', 'VerticalThrottle')) {
    if ($bytecode -notmatch "dconst_0\s+\d+: invokestatic[^\n]+Double.valueOf[^\n]+\s+\d+: invokevirtual[^\n]+set$field`:") {
        throw "Command invariant failed: $field must be explicitly zero"
    }
}
foreach ($mode in @('RollPitchControlMode.VELOCITY', 'VerticalControlMode.VELOCITY', 'YawControlMode.ANGULAR_VELOCITY', 'FlightCoordinateSystem.BODY')) {
    if (!$bytecode.Contains($mode)) { throw "Command mode missing: $mode" }
}
Write-Output 'PASS: compiled command factory has explicit zero translation and required velocity modes (static verification)'

# CAM3 v2.1: Exercise real bounded log files, queue backpressure and export without Android/aircraft.
$diagnosticTest = Join-Path $projectRoot "SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/$package/AimingDiagnosticLoggerTest.java"
& "$JavaHome/bin/javac.exe" -cp $output -d $output "$source/AimingDiagnosticLogger.java" $diagnosticTest
if ($LASTEXITCODE -ne 0) { throw 'Diagnostic tests did not compile' }
& "$JavaHome/bin/java.exe" -cp $output 'dji.v5.ux.sample.showcase.defaultlayout.aiming.AimingDiagnosticLoggerTest' $output
if ($LASTEXITCODE -ne 0) { throw 'Diagnostic tests failed' }

# CAM3 v2.5: Verify real session writes, boundaries, overflow/failure behavior and minimal policy.
$fullTest = Join-Path (Split-Path $test) 'FullSessionLogTest.java'
& "$JavaHome/bin/javac.exe" -cp $output -d $output "$source/FullSessionLog.java" "$source/MinimalLogPolicy.java" $fullTest
if ($LASTEXITCODE -ne 0) { throw 'Full log tests did not compile' }
& "$JavaHome/bin/java.exe" -cp $output 'dji.v5.ux.sample.showcase.defaultlayout.aiming.FullSessionLogTest' $output
if ($LASTEXITCODE -ne 0) { throw 'Full log tests failed' }
Get-Content (Join-Path $output 'full-log-test.jsonl') | ForEach-Object { $_ | ConvertFrom-Json | Out-Null }
Write-Output 'PASS: session JSONL parsed with independent JSON parser'

# CAM3 v2.7: Validate the production per-cycle adapter with actual JSONL serialization.
& "$JavaHome/bin/javac.exe" -cp $output -d $output "$source/AimingCycleLog.java" (Join-Path (Split-Path $test) 'AimingCycleLogTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Cycle log test compile failed' }
& "$JavaHome/bin/java.exe" -cp $output 'dji.v5.ux.sample.showcase.defaultlayout.aiming.AimingCycleLogTest' $output
if ($LASTEXITCODE -ne 0) { throw 'Cycle log test failed' }
$cycles=@(Get-Content (Join-Path $output 'aiming-cycle-test.jsonl') | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object event -eq 'aiming_cycle')
if($cycles.Count -ne 3 -or $cycles[0].targetSequence -ne 1234 -or $cycles[0].targetLatitude -ne 0.0001 -or $cycles[1].subjectBearingDeg -ne $null -or $cycles[2].submittedYawRate -ne $null){throw 'Independent cycle JSON validation failed'}
Write-Output 'PASS: independent parser validates per-cycle fields and nulls'

# VT 2.8: Movement scenarios and actual combined command factory compilation.
& "$JavaHome/bin/javac.exe" -cp "$output;$sdkJar" -d $output "$source/AimingMotionCommand.java" "$source/MovementCycleLog.java" (Join-Path (Split-Path $test) 'ComeToMeTest.java')
if ($LASTEXITCODE -ne 0) { throw 'VT 2.8 movement test compilation failed' }
& "$JavaHome/bin/java.exe" -cp $output 'dji.v5.ux.sample.showcase.defaultlayout.aiming.ComeToMeTest'
if ($LASTEXITCODE -ne 0) { throw 'VT 2.8 movement tests failed' }

# VT 2.8: Independently parse the actual production movement JSONL records.
& "$JavaHome/bin/javac.exe" -cp $output -d $output (Join-Path (Split-Path $test) 'MovementLogTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Movement log test compilation failed' }
& "$JavaHome/bin/java.exe" -cp $output 'dji.v5.ux.sample.showcase.defaultlayout.aiming.MovementLogTest' $output
if ($LASTEXITCODE -ne 0) { throw 'Movement log test failed' }
$movementRows=@(Get-Content (Join-Path $output 'movement-cycle-test.jsonl') | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object event -eq 'movement_cycle')
if($movementRows.Count -ne 2 -or $movementRows[0].phase -ne 'APPROACHING' -or $movementRows[0].submittedForwardMps -le 0 -or $movementRows[0].filmingDistanceM -ne 70 -or !$movementRows[1].paused -or $null -ne $movementRows[1].submittedForwardMps){throw 'Independent movement log validation failed'}
$motionBytecode=(& "$JavaHome/bin/javap.exe" -c -classpath $output 'dji.v5.ux.sample.showcase.defaultlayout.aiming.AimingMotionCommand') -join "`n"
if($LASTEXITCODE -ne 0 -or !$motionBytecode.Contains('YawOnlyCommand.build') -or !$motionBytecode.Contains('setRoll') -or !$motionBytecode.Contains('setPitch') -or $motionBytecode.Contains('setVerticalThrottle')){throw 'Combined command factory axis invariant failed'}
Write-Output 'PASS: independent movement JSON validation and combined command factory bytecode'

# VT 2.9/3.0: Validate navigation independently of the ride-speed sampling policy.
if($movementRows[0].yawPurpose -ne 'approach' -or $movementRows[0].plannedTravelM -lt 29.9 -or $movementRows[0].plannedTravelM -gt 30.1 -or $null -eq $movementRows[0].approachTargetLatitude -or $movementRows[0].noRideTimerActive){throw 'VT 2.9 navigation evidence validation failed'}
Write-Output 'PASS: VT 2.9 approach snapshot, planned travel, yaw ownership and timer evidence'

# Fixed return: independently verify real planner snapshots and completion miss distance.
$returnRows=@(Get-Content (Join-Path $output 'return-cycle-test.jsonl') | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object event -eq 'movement_cycle')
if($returnRows.Count -ne 2 -or $returnRows[0].yawPurpose -ne 'return' -or [Math]::Abs($returnRows[0].returnPlannedTravelM-27) -gt 0.01 -or $returnRows[1].reason -ne 'return_travel_completed' -or $returnRows[1].backwardProgressM -lt 27.9 -or $returnRows[1].returnCompletionCentralDistanceM -lt 10 -or $returnRows[1].yawPurpose -ne 'surfer'){throw 'Fixed return log validation failed'}
Write-Output 'PASS: fixed return origin, planned travel, progress and completion miss distance'

# VT 3.0: Independent evidence for fast/confirmed rides and all-aiming reverse blocking.
& "$JavaHome/bin/javac.exe" -cp $output -d $output (Join-Path (Split-Path $test) 'Vt30Test.java')
if ($LASTEXITCODE -ne 0) { throw 'VT 3.0 tests did not compile' }
& "$JavaHome/bin/java.exe" -cp $output 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt30Test'
if ($LASTEXITCODE -ne 0) { throw 'VT 3.0 tests failed' }
if($movementRows[0].fastWindowMs -ne 1000 -or $movementRows[0].ridePolicy -ne "fixed_duration" -or $movementRows[0].rideDurationMs -ne 90000 -or $movementRows[0].rideConfirmationRequired -ne $false -or $cycles[0].reverseBlockMs -ne 2000){throw 'VT 3.0 log evidence missing'}
Write-Output 'PASS: VT 3.3 ride policy and retained reverse block are present in serialized logs'

# VT 3.1: Exercise retained yaw, preserved qualification and re-approach hysteresis.
& "$JavaHome/bin/javac.exe" -cp $output -d $output (Join-Path (Split-Path $test) 'Vt31Test.java')
if ($LASTEXITCODE -ne 0) { throw 'VT 3.1 tests did not compile' }
& "$JavaHome/bin/java.exe" -cp $output 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt31Test'
if ($LASTEXITCODE -ne 0) { throw 'VT 3.1 tests failed' }
if($movementRows[0].qualificationRequiredMs -ne 0 -or $movementRows[0].reapproachMarginM -ne 5 -or !$movementRows[1].gpsMovementPaused){throw 'VT 3.1 log evidence missing'}
Write-Output 'PASS: VT 3.1 settings and GPS pause evidence independently parsed'

# VT 3.2: Arrival boundaries, late telemetry vetoes and same-cycle yaw handoff.
& "$JavaHome/bin/javac.exe" -cp $output -d $output (Join-Path (Split-Path $test) 'Vt32Test.java')
if ($LASTEXITCODE -ne 0) { throw 'VT 3.2 tests did not compile' }
& "$JavaHome/bin/java.exe" -cp $output 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt32Test'
if ($LASTEXITCODE -ne 0) { throw 'VT 3.2 tests failed' }
if($movementRows[0].completionToleranceM -ne 1 -or $movementRows[0].excursionStopDistanceM -ne 249 -or [Math]::Abs($movementRows[0].approachRemainingM-($movementRows[0].plannedTravelM-$movementRows[0].forwardProgressM)) -gt 0.00001){throw 'VT 3.2 serialized completion evidence failed'}
if($movementRows[0].maxMovementSpeedMps -ne 4 -or $null -ne $movementRows[0].movementAccelerationMps2 -or $movementRows[0].movementAccelerationRampEnabled -ne $false -or $movementRows[0].firstApproachUsesSameMargin -ne $true -or $movementRows[0].movementSlowdownDistanceM -ne 20 -or $movementRows[0].arrivalSpeedPerMetre -ne 0.2){throw 'VT 3.2 movement speed profile log evidence failed'}
Write-Output 'PASS: VT 3.2 completion tolerance, excursion threshold, speed profile and residual travel independently parsed'

# VT 3.2: No qualification dwell and no return/band recreation on ride end.
& "$JavaHome/bin/javac.exe" -cp $output -d $output (Join-Path (Split-Path $test) 'Vt32RidePolicyTest.java')
if ($LASTEXITCODE -ne 0) { throw 'VT 3.2 ride-policy tests did not compile' }
& "$JavaHome/bin/java.exe" -cp $output 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt32RidePolicyTest'
if ($LASTEXITCODE -ne 0) { throw 'VT 3.2 ride-policy tests failed' }
if($movementRows[0].qualificationWaitEnabled -ne $false -or $movementRows[0].rideEndTriggersReturn -ne $false){throw 'VT 3.2 ride-policy log evidence failed'}
Write-Output 'PASS: zero-wait and ride-end-return policy independently parsed from production logs'

# VT 3.3: clock expiry without GPS, fresh rearm, pitch hysteresis and recording prerequisite.
& "$JavaHome/bin/javac.exe" -cp $output -d $output "$source/HeightReading.java" "$source/GimbalPitchPolicy.java" "$source/RecordingGate.java" (Join-Path (Split-Path $test) 'Vt33Test.java')
if ($LASTEXITCODE -ne 0) { throw 'VT 3.3 tests did not compile' }
& "$JavaHome/bin/java.exe" -cp $output 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt33Test'
if ($LASTEXITCODE -ne 0) { throw 'VT 3.3 tests failed' }

# VT 3.4: compile and exercise the production JSON parser and distance-band policy.
$gsonJar = (Get-ChildItem "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/*/gson-2.10.1.jar" | Select-Object -First 1).FullName
if (!$gsonJar) { throw 'Gson 2.10.1 jar missing' }
& "$JavaHome/bin/javac.exe" -cp "$output;$gsonJar" -d $output "$source/StrictConfigJson.java" "$source/GimbalBandConfig.java" "$source/GimbalBandPolicy.java" (Join-Path (Split-Path $test) 'Vt34Test.java')
if ($LASTEXITCODE -ne 0) { throw 'VT 3.4 tests did not compile' }
& "$JavaHome/bin/java.exe" -cp "$output;$gsonJar" 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt34Test' (Join-Path $projectRoot 'SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets/vt_gimbal_bands.json')
if ($LASTEXITCODE -ne 0) { throw 'VT 3.4 tests failed' }

# JVM-only Android storage seams; compile production loader without aircraft/Android runtime.
& "$JavaHome/bin/javac.exe" -cp "$output;$gsonJar" -d $output (Join-Path $projectRoot 'tools/gimbal-test-stubs/Context.java') (Join-Path $projectRoot 'tools/gimbal-test-stubs/AssetManager.java') (Join-Path $projectRoot 'tools/gimbal-test-stubs/SharedConfigStorage.java') "$source/GimbalBandStorage.java" (Join-Path $projectRoot 'tools/gimbal-test-stubs/GimbalBandStorageTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Gimbal configuration storage tests did not compile' }
& "$JavaHome/bin/java.exe" -cp "$output;$gsonJar" 'dji.v5.ux.sample.showcase.defaultlayout.aiming.GimbalBandStorageTest' (Join-Path $projectRoot 'SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets/vt_gimbal_bands.json') $output
if ($LASTEXITCODE -ne 0) { throw 'Gimbal configuration storage tests failed' }

# VT 3.5: production retreat/session integration and independent structured log parsing.
& "$JavaHome/bin/javac.exe" -cp $output -d $output "$source/RetreatLog.java" (Join-Path (Split-Path $test) 'Vt35Test.java')
if ($LASTEXITCODE -ne 0) { throw 'VT 3.5 tests did not compile' }
& "$JavaHome/bin/java.exe" -cp $output 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt35Test' $output
if ($LASTEXITCODE -ne 0) { throw 'VT 3.5 tests failed' }
$retreatRows=@(Get-Content (Join-Path $output 'retreat-test.jsonl') | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object event -like 'retreat_*')
if($retreatRows.Count -ne 2 -or $retreatRows[0].minimumDistanceM -ne 25 -or $retreatRows[0].speedMps -ne 3 -or $retreatRows[0].durationMs -ne 10000 -or $retreatRows[0].cooldownMs -ne 5000 -or $retreatRows[0].submittedForwardMps -ne -3 -or !$retreatRows[1].retainedTarget -or $null -ne $retreatRows[1].submittedForwardMps -or $null -eq $retreatRows[1].targetLatitude){throw 'VT 3.5 structured retreat evidence failed'}
Write-Output 'PASS: independent JSON parser verifies retreat settings, retained position and submission evidence'

# VT 3.6: immediate distance-limited movement, unified margin and scoped 5 m/s retreat envelope.
& "$JavaHome/bin/javac.exe" -cp $output -d $output (Join-Path (Split-Path $test) 'Vt36Test.java')
if ($LASTEXITCODE -ne 0) { throw 'VT 3.6 tests did not compile' }
& "$JavaHome/bin/java.exe" -cp $output 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt36Test' $output
if ($LASTEXITCODE -ne 0) { throw 'VT 3.6 tests failed' }
$vt36=@(Get-Content (Join-Path $output 'vt36-test.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
$r36=@($vt36 | Where-Object event -eq 'retreat_cycle')[0]
$m36=@($vt36 | Where-Object event -eq 'movement_cycle')[0]
if($r36.minimumDistanceM -ne 23 -or $r36.durationMs -ne 3000 -or $r36.speedMps -ne 5 -or $r36.speedKmh -ne 18 -or $r36.maximumSpeedMps -ne 5 -or $r36.submittedForwardMps -ne -5 -or $r36.cooldownMs -ne 5000 -or $m36.approachStartThresholdM -ne 35 -or $m36.movementAccelerationRampEnabled -ne $false -or $null -ne $m36.movementAccelerationMps2 -or $m36.movementSpeedPolicy -ne 'distance_limited_immediate'){throw 'VT 3.6 serialized settings/policy mismatch'}
Write-Output 'PASS: independent JSON parser verifies VT 3.6 retreat defaults and movement policy'

# VT 3.7: Use production parser, rotation math, migration policy and actual session/logging.
& "$JavaHome/bin/javac.exe" -cp "$output;$gsonJar" -d $output "$source/RotationSpeedConfig.java" "$source/RotationSpeedStorage.java" "$source/ConfigFileMigration.java" (Join-Path (Split-Path $test) 'Vt37Test.java')
if ($LASTEXITCODE -ne 0) { throw 'VT 3.7 tests did not compile' }
& "$JavaHome/bin/java.exe" -cp "$output;$gsonJar" 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt37Test' (Join-Path $projectRoot 'SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets/vt_rotation_speeds.json') $output
if ($LASTEXITCODE -ne 0) { throw 'VT 3.7 tests failed' }
$vt37=@(Get-Content (Join-Path $output 'vt37-test.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
$cycle37=@($vt37 | Where-Object event -eq 'aiming_cycle')[0]
$config37=@($vt37 | Where-Object event -eq 'rotation_speed_config')[0]
if($cycle37.rotationCurve -ne 'riding' -or $cycle37.rotationConfigSource -ne 'json' -or [Math]::Abs($cycle37.desiredYawRate + 6) -gt 0.000001 -or $cycle37.submitted -ne $true -or $null -eq $cycle37.submittedYawRate){throw 'VT 3.7 rotation log evidence mismatch'}
$parsedRotation=$config37.fileContents | ConvertFrom-Json
if($parsedRotation.riding[-1].speedDegPerSec -ne 30){throw 'VT 3.7 config snapshot missing'}
Write-Output 'PASS: independent JSON parser verifies rotation config, selected curve, desired and submitted commands'

# VT 3.8: Default stale-GPS limit and no-fallback preflight configuration transaction.
& "$JavaHome/bin/javac.exe" -cp "$output;$gsonJar" -d $output "$source/RetreatJsonConfig.java" "$source/VtJsonFields.java" "$source/VtSettingsConfig.java" "$source/ShorelineLibrary.java" "$source/VtSessionConfig.java" (Join-Path (Split-Path $test) 'Vt38Test.java')
if ($LASTEXITCODE -ne 0) { throw 'VT 3.8 tests did not compile' }
& "$JavaHome/bin/java.exe" -cp "$output;$gsonJar" 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt38Test' (Join-Path $projectRoot 'SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets') $output
if ($LASTEXITCODE -ne 0) { throw 'VT 3.8 tests failed' }
$v38=@(Get-Content (Join-Path $output 'vt38-test.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
$blocked38=@($v38 | Where-Object event -eq 'retreat_start_blocked')[0]
$reset38=@($v38 | Where-Object event -eq 'retreat_gps_allowance_reset')[0]
if($blocked38.reason -ne 'stale_gps_retreat_limit' -or $blocked38.gpsFresh -ne $false -or $blocked38.maxStartsWithoutFreshGps -ne 1 -or $blocked38.startsWithoutFreshGps -ne 1 -or $blocked38.active -ne $false -or $reset38.gpsFresh -ne $true -or $reset38.startsWithoutFreshGps -ne 0 -or $reset38.previousStartsWithoutFreshGps -ne 1){throw 'VT 3.8 allowance log evidence mismatch'}
Write-Output 'PASS: independent JSON parser verifies stale-GPS block and fresh-fix allowance reset'

# VT 4.0: exercise production fixed shoreline destinations and both body velocity axes.
& "$JavaHome/bin/javac.exe" -cp "$output;$gsonJar" -d $output (Join-Path (Split-Path $test) 'Vt40Test.java')
if ($LASTEXITCODE -ne 0) { throw 'VT 4.0 tests did not compile' }
& "$JavaHome/bin/java.exe" -cp "$output;$gsonJar" 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt40Test' (Join-Path $projectRoot 'SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets') $output
if ($LASTEXITCODE -ne 0) { throw 'VT 4.0 tests failed' }
$v40=@(Get-Content (Join-Path $output 'vt40-test.jsonl') | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object event -eq 'movement_cycle')
if($v40.Count -ne 1 -or $v40[0].positioningMode -ne 'front' -or $v40[0].yawPurpose -ne 'surfer' -or $v40[0].submittedRightMps -ge 0 -or $v40[0].fixedDestinationFixTime -le 0 -or $v40[0].shorelineId -ne 'beach'){throw 'VT40 independent log validation failed'}
Write-Output 'PASS: VT 4.0 independent parser validates preset, fixed destination, lateral submission and surfer yaw'

# VT4.0.4: signed-angle snapshot routing, boundaries and configurable excursion.
& "$JavaHome/bin/javac.exe" -cp "$output;$gsonJar" -d $output (Join-Path (Split-Path $test) 'Vt404Test.java')
if ($LASTEXITCODE -ne 0) { throw 'VT4.0.4 tests did not compile' }
& "$JavaHome/bin/java.exe" -cp "$output;$gsonJar" 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt404Test' (Join-Path $projectRoot 'SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets') $output
if ($LASTEXITCODE -ne 0) { throw 'VT4.0.4 tests failed' }

foreach($routeType in @('direct','around')) {
    $angleRows=@(Get-Content (Join-Path $output "vt404-$routeType.jsonl") | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object event -eq 'movement_cycle')
    $row=$angleRows[0]
    if($angleRows.Count -ne 1 -or $row.routeKind -ne $routeType -or $row.distanceMeaning -ne 'direct_horizontal' -or $null -ne $row.projectedSeparationM -or $row.maxExcursionM -ne 300 -or $row.excursionStopDistanceM -ne 299 -or $row.routeWaypoints.Count -lt 1 -or $row.routeWaypoints[0].Count -ne 2 -or $row.fixedDestinationFixTime -le 0 -or $row.yawPurpose -ne 'surfer' -or $null -eq $row.submittedRightMps){throw 'VT404 independent route log verification failed'}
}
Write-Output 'PASS: independent JSON parser validates direct/around waypoint arrays, snapshot, distance meaning, excursion and actual BODY submission'
