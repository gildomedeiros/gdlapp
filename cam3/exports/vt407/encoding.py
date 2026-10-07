from pathlib import Path
root=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
p=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/Vt33Test.java'
s=p.read_text(encoding='utf-8');s=s.encode('cp1252').decode('utf-8');p.write_text(s,encoding='utf-8')
p=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/YawAimingController.java'
s=p.read_text(encoding='utf-8').replace('effectiveWave linesJson','effectiveWaveLinesJson');p.write_text(s,encoding='utf-8')
print('Restored test fixture UTF-8 punctuation and effectiveWaveLinesJson log key')
