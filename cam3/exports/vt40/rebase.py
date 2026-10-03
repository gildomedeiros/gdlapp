from pathlib import Path
import hashlib,json
root=Path('C:/Users/gildo/.codex/worktrees/vt-28/gdlapp/vt')
out=Path(__file__).parent/'files'
manifest=[]
for p in out.rglob('*'):
 if p.is_file():
  rel=p.relative_to(out).as_posix();original=root/rel
  staged=hashlib.sha256(p.read_bytes()).hexdigest();old=hashlib.sha256(original.read_bytes()).hexdigest() if original.exists() else None
  if old!=staged:manifest.append(dict(path=rel,originalSha256=old,stagedSha256=staged))
(out.parent/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print('Changed staged files:',len(manifest))
