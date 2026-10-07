from pathlib import Path
import subprocess,json,shutil
folder=Path('C:/Users/gildo/temp')
stage=Path('C:/Users/gildo/gdlapp/cam3/exports/vt407')
classes=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt/build/aiming-tests')
gson=next(Path('C:/Users/gildo/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1').glob('*/gson-2.10.1.jar'))
cp=str(stage)+';'+str(classes)+';'+str(gson)
jdk=Path('C:/Program Files/Java/jdk-21/bin')
subprocess.run([str(jdk/'javac.exe'),'-cp',cp,'-d',str(stage),str(stage/'ValidateUserConfig.java')],check=True)
cmd=[str(jdk/'java.exe'),'-cp',cp,'ValidateUserConfig',str(folder)]
subprocess.run(cmd,check=True)
p=folder/'vt_settings.json';raw=p.read_text(encoding='utf-8-sig');config=json.loads(raw)
if config.get('extraPathClearanceMetres')!=0:
 backup=folder/'vt_settings.before-407-correction.json'
 if backup.exists():raise RuntimeError('Backup already exists; refusing to overwrite it')
 shutil.copy2(p,backup)
 import re
 corrected,count=re.subn(r'("extraPathClearanceMetres"\s*:\s*)[-0-9.]+',r'\g<1>0',raw)
 assert count==1
 assert json.loads(corrected)['routePlanningAllowanceMetres']==config['routePlanningAllowanceMetres']
 p.write_text(corrected,encoding='utf-8')
 print('CORRECTED: extraPathClearanceMetres=0; original settings backed up')
subprocess.run(cmd,check=True)
