$ErrorActionPreference='Stop'
$project='C:/Users/gildo/.codex/worktrees/vt-28/gdlapp/vt'
$out=Join-Path $project 'build/aiming-tests'
$test=Join-Path $project 'SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/Vt40Test.java'
$gson=(Get-ChildItem 'C:/Users/gildo/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/*/gson-2.10.1.jar' | Select-Object -First 1).FullName
& 'C:/Program Files/Java/jdk-21/bin/javac.exe' -cp "$out;$gson" -d $out $test
if($LASTEXITCODE -ne 0){throw 'VT40 test compile failed'}
& 'C:/Program Files/Java/jdk-21/bin/java.exe' -cp "$out;$gson" dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt40Test (Join-Path $project 'SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets') $out
if($LASTEXITCODE -ne 0){throw 'VT40 tests failed'}
