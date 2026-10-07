from pathlib import Path
import subprocess,json,re
source=Path('C:/Users/gildo/temp')
stage=Path('C:/Users/gildo/gdlapp/cam3/exports/vt407')
folder=stage/'corrected-config';folder.mkdir(exist_ok=True)
for name in ['vt_settings.json','vt_wave_lines.json','vt_gimbal_bands.json','vt_retreat_settings.json','vt_rotation_speeds.json']:
 raw=(source/name).read_text(encoding='utf-8-sig')
 if name=='vt_settings.json':
  raw,count=re.subn(r'("extraPathClearanceMetres"\s*:\s*)[-0-9.]+',r'\g<1>0',raw);assert count==1
 (folder/name).write_text(raw,encoding='utf-8')
classes=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt/build/aiming-tests')
gson=next(Path('C:/Users/gildo/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1').glob('*/gson-2.10.1.jar'))
cp=str(stage)+';'+str(classes)+';'+str(gson)
jdk=Path('C:/Program Files/Java/jdk-21/bin')
subprocess.run([str(jdk/'javac.exe'),'-cp',cp,'-d',str(stage),str(stage/'ValidateUserConfig.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',cp,'ValidateUserConfig',str(folder)],check=True)
print('Corrected copies: '+str(folder))
