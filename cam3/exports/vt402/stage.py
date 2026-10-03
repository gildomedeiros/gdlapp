from pathlib import Path
import json,hashlib
root=Path('C:/Users/gildo/.codex/worktrees/vt-28/gdlapp/vt')
out=Path(__file__).parent/'files'
pk='SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/'
entries=[]
def save(name,text):
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');old=root/name
 entries.append(dict(path=name,originalSha256=hashlib.sha256(old.read_bytes()).hexdigest() if old.exists() else None,stagedSha256=hashlib.sha256(p.read_bytes()).hexdigest()))
n=pk+'SharedConfigStorage.java';s=(root/n).read_text(encoding='utf-8-sig');start=s.index('    /** Provider-safe backup');end=s.index('    public static void select(',start)
s=s[:start]+'''    /** Direct overwrite, then read-back verification. No backup or rollback file is created. */
    public static void writeVerified(Context c,String name,String content)throws IOException {
        if(!SETTINGS.equals(name)&&!SHORELINES.equals(name))throw new IOException("Unsupported shoreline file");
        if(folder(c)==null)throw new IOException("Choose Configuration folder first");
        try {overwrite(c,name,content);}catch(Exception e) {
            throw new IOException("Save failed: "+name+" — "+e.getMessage(),e);
        }
    }
'''+s[end:];save(n,s)
n=pk+'ShorelineSetupUi.java';s=(root/n).read_text(encoding='utf-8-sig').replace('// Validate selection write before publishing the profile. Each file is backed up and verified.','// Validate settings first. Saves directly overwrite each file and verify its contents.');save(n,s)
n='SampleCode-V5/android-sdk-v5-sample/build.gradle';s=(root/n).read_text(encoding='utf-8-sig');assert 'versionCode 24' in s;s=s.replace('versionCode 24','versionCode 25').replace('versionName "4.0.1"','versionName "4.0.2"');save(n,s)
n=pk+'FullSessionLog.java';s=(root/n).read_text(encoding='utf-8-sig').replace('"version", "4.0.1"','"version", "4.0.2"');save(n,s)
test='SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/'
for p in (root/test).glob('*.java'):
 s=p.read_text(encoding='utf-8-sig')
 if '\\"version\\":\\"4.0.1\\"' in s:save(test+p.name,s.replace('\\"version\\":\\"4.0.1\\"','\\"version\\":\\"4.0.2\\"'))
save('Docs/VT_4.0.2.md','''# VT 4.0.2 — direct shoreline/configuration saves

VersionName 4.0.2 / versionCode 25. At the user's request, shoreline library and selected-ID writes directly overwrite the existing JSON file. No `.backup` file is created and no rollback write is attempted. Read-back verification remains; missing files, folder permission problems and mismatched contents are reported as save failures. Existing backup files are left untouched.

This removes the backup-creation path that blocked shoreline saving. First-time folder setup remains absent-only and preserves existing configurations. Flight behavior, JSON schema and all VT 4.0.1 UI/logging fixes remain unchanged.
''')
(out.parent/'manifest.json').write_text(json.dumps(entries,indent=2),encoding='utf-8')
print('Staged',len(entries),'files')
