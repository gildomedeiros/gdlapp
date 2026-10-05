from pathlib import Path
root=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
tests=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming'
p=tests/'MovementLogTest.java';s=p.read_text().replace('"4.0.3"','"test-version"').replace('\\"4.0.3\\"','\\"test-version\\"');s=s.replace('error->{throw new AssertionError(error);});','error->{throw new AssertionError(error);},"test-version");');p.write_text(s)
p=tests/'Vt404Test.java';s=p.read_text().replace('c.clearance>=20-1e-6','c.clearance>=18-1e-6').replace('whole polygon segment20m clearance','whole polygon segment18m clearance (VT405 zero extra buffer)');p.write_text(s)
(tests/'Vt405Test.java').write_text(Path('C:/Users/gildo/gdlapp/cam3/exports/vt405/Vt405Test.java').read_text())
p=root/'tools/test-aiming.ps1';s=p.read_text();s+='''
# VT 4.0.5: production route recovery, frozen snapshots, timeout, escape and logging.
& "$JavaHome/bin/javac.exe" -cp "$output;$gsonJar" -d $output (Join-Path (Split-Path $test) 'Vt405Test.java')
if ($LASTEXITCODE -ne 0) { throw 'VT405 compile failed' }
& "$JavaHome/bin/java.exe" -cp "$output;$gsonJar" 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt405Test' $output
if ($LASTEXITCODE -ne 0) { throw 'VT405 tests failed' }
$v405=@(Get-Content (Join-Path $output 'vt405-log.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
$header405=@($v405 | Where-Object event -eq 'session_start')[0]
$row405=@($v405 | Where-Object event -eq 'movement_cycle')[0]
if($header405.version -ne '4.0.5-test' -or $null -ne $row405.projectedSeparationM -or $null -ne $row405.comeToMeAlignmentErrorM -or $row405.extraPathClearanceMetres -ne 0 -or $row405.requiredPathClearanceMetres -ne 18 -or $null -eq $row405.capturedSurferDirectDistanceM){throw 'VT405 independent log validation failed'}
Write-Output 'PASS: VT405 independent JSON parser validates runtime version, zero buffer and explicit direct distance references'
''';p.write_text(s)
print('VT405 tests installed')
