$ErrorActionPreference='Stop'
$vtRoot='C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt'
$vtSource="$vtRoot/SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming"
$vtTests="$vtRoot/SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming"
$vtOutput="$vtRoot/build/aiming-tests"
$vtGson=(Get-ChildItem "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/*/gson-2.10.1.jar" | Select-Object -First 1).FullName
& 'C:/Program Files/Java/jdk-21/bin/javac.exe' -cp "$vtOutput;$vtGson" -d $vtOutput "$vtSource/ComeToMeController.java" "$vtSource/VtSessionConfig.java" "$vtTests/Vt40Test.java" "$vtTests/Vt404Test.java" "$vtTests/Vt407Test.java"
if($LASTEXITCODE -ne 0){throw 'Focused compile failed'}
& 'C:/Program Files/Java/jdk-21/bin/java.exe' -cp "$vtOutput;$vtGson" 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt407Test' "$vtRoot/SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets" $vtOutput
if($LASTEXITCODE -ne 0){throw 'VT407 tests failed'}
