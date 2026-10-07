from pathlib import Path
root=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
p=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/ComeToMeController.java'
s=p.read_text(encoding='utf-8').replace('returnBoundaryDistance<0','returnBoundaryDistance< -1e-6').replace('boundaryAt(in)<0','boundaryAt(in)< -1e-6')
p.write_text(s,encoding='utf-8')
print('Ignore sub-micrometre plane arithmetic at an exactly-on-boundary restart')
