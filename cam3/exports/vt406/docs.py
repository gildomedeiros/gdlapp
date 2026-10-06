from pathlib import Path
r=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
text=Path('C:/Users/gildo/gdlapp/cam3/exports/vt406/VT_4.0.6.md').read_text(encoding='utf-8')
(r/'Docs/VT_4.0.6.md').write_text(text,encoding='utf-8')
(r/'detailed_design/detailed_design_v4.0.6.md').write_text(text.replace('# VT 4.0.6','# VT 4.0.6 detailed change design',1),encoding='utf-8')
p=r/'ENHANCEMENTS.md';s=p.read_text(encoding='utf-8')
if '# VT 4.0.6' not in s:p.write_text(s+'\n\n'+text,encoding='utf-8')
print('VT406 documentation saved')
