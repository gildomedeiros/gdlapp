from pathlib import Path
r=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
text=Path('C:/Users/gildo/gdlapp/cam3/exports/vt405/VT_4.0.5.md').read_text(encoding='utf-8')
(r/'Docs/VT_4.0.5.md').write_text(text,encoding='utf-8')
(r/'detailed_design/detailed_design_v4.0.5.md').write_text(text.replace('# VT 4.0.5','# VT 4.0.5 detailed change design',1))
p=r/'ENHANCEMENTS.md';p.write_text(p.read_text(encoding='utf-8')+'\n\n'+text,encoding='utf-8')
print('VT405 release notes and detailed change design saved')

