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
& "$JavaHome/bin/javac.exe" -d $output "$source/ComeToMeSettings.java" "$source/ComeToMeController.java" "$source/YawAimingMath.java" "$source/AimingFlightModes.java" "$source/AimingSession.java" "$source/SurferYawController.java" "$source/RideDetector.java" "$source/LoRaTelemetry.java" (Join-Path (Split-Path $test) "LoRaTelemetryTest.java") $test
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
if($LASTEXITCODE -ne 0 -or !$motionBytecode.Contains('YawOnlyCommand.build') -or !$motionBytecode.Contains('setRoll') -or $motionBytecode.Contains('setPitch') -or $motionBytecode.Contains('setVerticalThrottle')){throw 'Combined command factory axis invariant failed'}
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
if($movementRows[0].fastWindowMs -ne 1000 -or $movementRows[0].confirmationWindowMs -ne 5000 -or $cycles[0].reverseBlockMs -ne 2000){throw 'VT 3.0 log evidence missing'}
Write-Output 'PASS: VT 3.0 detection windows and reverse block are present in serialized logs'
