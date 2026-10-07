from pathlib import Path
r=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
p=r/'SampleCode-V5/android-sdk-v5-sample/build.gradle'
s=p.read_text(encoding='utf-8');assert 'versionCode 30' in s and 'versionName "4.0.7"' in s
p.write_text(s.replace('versionCode 30','versionCode 31').replace('versionName "4.0.7"','versionName "4.0.8"'),encoding='utf-8')
p=r/'ENHANCEMENTS.md'
s=p.read_text(encoding='utf-8')
s+='''\n\n## VT 4.0.8 — boundary recovery\n\nDiagonal Come-to-me plans from beachward origins and validates every recovery leg, preserving S0/P3, planning/execution clearances and deadline. Arrival and final BODY submission use the same boundary recovery rules. Automatic Return to central may recover directly toward its fixed stand-off target; a beachward start uses the nearest point on the stand-off line. Retreat priority is unchanged. New boundary-flow diagnostics and deterministic logged-geometry/controller/session regressions. See Docs/VT_4.0.8.md. No new JSON settings.\n'''
p.write_text(s,encoding='utf-8')
