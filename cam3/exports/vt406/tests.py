from pathlib import Path
r=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
t=r/'SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming'
(t/'Vt406Test.java').write_text(Path('C:/Users/gildo/gdlapp/cam3/exports/vt406/Vt406Test.java').read_text(encoding='utf-8'),encoding='utf-8')
p=r/'tools/test-aiming.ps1';s=p.read_text(encoding='utf-8');s+='''
# VT 4.0.6: distinct planning/stop clearances, whole-route allowance and recovery status.
& "$JavaHome/bin/javac.exe" -cp "$output;$gsonJar" -d $output (Join-Path (Split-Path $test) 'Vt406Test.java')
if ($LASTEXITCODE -ne 0) { throw 'VT406 compile failed' }
& "$JavaHome/bin/java.exe" -cp "$output;$gsonJar" 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt406Test' (Join-Path $projectRoot 'SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets') $output
if ($LASTEXITCODE -ne 0) { throw 'VT406 tests failed' }
$v406=@(Get-Content (Join-Path $output 'vt406-log.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
$row406=@($v406 | Where-Object event -eq 'movement_cycle')[0]
if($row406.requiredPathClearanceMetres -ne 18 -or $row406.requiredPlanningClearanceMetres -ne 20 -or $row406.routePlanningAllowanceMetres -ne 2 -or $row406.routeRecoveryStatus -ne 'none' -or $null -ne $row406.projectedSeparationM){throw 'VT406 independent planning/stop log validation failed'}
Write-Output 'PASS: VT406 independent JSON parser verifies separate planning and execution limits'
''';p.write_text(s,encoding='utf-8')
print('VT406 tests installed')
