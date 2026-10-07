from pathlib import Path
import re
root=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
src=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming'
tests=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming'
for p in list(tests.glob('*.java'))+[src.parent/'DefaultLayoutActivity.java']:
 s=p.read_text(encoding='utf-8')
 def labels(m):
  v=m.group(0)
  if ' ' in v:v=re.sub(r'\bwaveLine\b','wave line',v);v=re.sub(r'\bWaveLine\b','Wave line',v)
  return v
 s=re.sub(r'"(?:[^"\\]|\\.)*"',labels,s)
 if p.name=='DefaultLayoutActivity.java':s=s.replace('maxExcursionMetres defaults to 300.','maxExcursionMetres defaults to 300. Automatic no-ride return stops at a fixed target returnBoundaryStandOffMetres (default 10 m, range 1–100) sea-side of the original boundary; retreat can interrupt return. Use fresh Wave line configuration files.')
 p.write_text(s,encoding='utf-8')
p=tests/'Vt407Test.java';s=p.read_text(encoding='utf-8').replace('new RetreatController();','new RetreatController((event,why)->{});')
p.write_text(s,encoding='utf-8')
# Fresh setup also labels its release consistently.
p=src/'WaveLineSetupUi.java';s=p.read_text(encoding='utf-8').replace('VT 4.0.1','VT 4.0.7');p.write_text(s,encoding='utf-8')
print('Updated Wave line UI/test labels and retreat test event callback')
