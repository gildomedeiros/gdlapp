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
& "$JavaHome/bin/javac.exe" -d $output "$source/YawAimingMath.java" "$source/AimingFlightModes.java" "$source/AimingSession.java" "$source/LoRaTelemetry.java" (Join-Path (Split-Path $test) "LoRaTelemetryTest.java") $test
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
