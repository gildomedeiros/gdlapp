from pathlib import Path
import shutil
root=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
stage=Path('C:/Users/gildo/gdlapp/cam3/exports/vt404')
pkg='dji/v5/ux/sample/showcase/defaultlayout/aiming'
src=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/java'/pkg
test=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/test/java'/pkg
p=src/'FullSessionLog.java';s=p.read_text(encoding='utf-8');s=s.replace('        if (value instanceof Boolean) return value.toString();','''        if (value instanceof Boolean) return value.toString();
        if(value.getClass().isArray()) {
            StringBuilder array=new StringBuilder("[");int n=java.lang.reflect.Array.getLength(value);
            for(int i=0;i<n;i++){if(i>0)array.append(',');array.append(json(java.lang.reflect.Array.get(value,i)));}
            return array.append(']').toString();
        }''');p.write_text(s,encoding='utf-8')
shutil.copyfile(stage/'Vt404Test.java',test/'Vt404Test.java')
p=root/'tools/test-aiming.ps1';s=p.read_text(encoding='utf-8');s+='''
# VT4.0.4: signed-angle snapshot routing, boundaries and configurable excursion.
& "$JavaHome/bin/javac.exe" -cp "$output;$gsonJar" -d $output (Join-Path (Split-Path $test) 'Vt404Test.java')
if ($LASTEXITCODE -ne 0) { throw 'VT4.0.4 tests did not compile' }
& "$JavaHome/bin/java.exe" -cp "$output;$gsonJar" 'dji.v5.ux.sample.showcase.defaultlayout.aiming.Vt404Test' (Join-Path $projectRoot 'SampleCode-V5/android-sdk-v5-uxsdk/src/main/assets') $output
if ($LASTEXITCODE -ne 0) { throw 'VT4.0.4 tests failed' }
''';p.write_text(s,encoding='utf-8')
p=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/DefaultLayoutActivity.java';s=p.read_text(encoding='utf-8').replace('VT 4.0 · JSON configuration','VT 4.0.4 · JSON configuration').replace('Both modes require filming separation >= retreat threshold + 5 m when enabled.','All modes require filming separation >= retreat threshold + 5 m when enabled. For mode diagonal, filming is direct horizontal distance; positioningAngleDegrees (-90 left, 0 shoreward, +90 right) and positioningAngleToleranceDegrees set the position. extraPathClearanceMetres adds route clearance when retreat is ON. maxExcursionMetres defaults to 300. Angle routes respect the original-central boundary.');p.write_text(s,encoding='utf-8')
shutil.copyfile(Path('C:/Users/gildo/gdlapp/cam3/Docs/VT_4.0.4_detailed_design_draft.md'),root/'detailed_design/detailed_design_v4.0.4.md')
print('VT404 tests/help/logging completed')
