from pathlib import Path
import hashlib,json,shutil,subprocess,zipfile
r=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt');w=Path('C:/Users/gildo/gdlapp/cam3/exports/vt408')
apk=r/'SampleCode-V5/android-sdk-v5-sample/build/outputs/apk/debug/sample-debug.apk'
meta=json.loads((apk.parent/'output-metadata.json').read_text(encoding='utf-8'))
assert meta['elements'][0]['versionName']=='4.0.8' and meta['elements'][0]['versionCode']==31
with zipfile.ZipFile(apk) as z:
 settings=json.loads(z.read('assets/vt_settings.json'))
 assert settings['returnBoundaryStandOffMetres']==10 and settings['routePlanningAllowanceMetres']==2
 assert settings['maxExcursionMetres']==300 and 'waveLineId' in settings and 'shorelineId' not in settings
 assert json.loads(z.read('assets/vt_wave_lines.json'))=={'version':1,'waveLines':[]}
aapts=sorted(Path('C:/Users/gildo/AppData/Local/Android/Sdk/build-tools').glob('*/aapt.exe'));assert aapts
badging=subprocess.check_output([str(aapts[-1]),'dump','badging',str(apk)],text=True,encoding='utf-8')
line=badging.splitlines()[0];assert "versionCode='31'" in line and "versionName='4.0.8'" in line
release=r/'build/releases/VT-4.0.8.apk';release.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(apk,release)
sha=hashlib.sha256(release.read_bytes()).hexdigest().upper()
info={'version':'4.0.8','versionCode':31,'apk':str(release),'sha256':sha,'bytes':release.stat().st_size,'package':line,'regressionSuite':'passed','newSuiteAssertions':5728,'loggedRecoveryGeometries':27,'independentJsonValidation':'passed','androidBuild':'passed','aircraftFlightTest':'not performed'}
(w/'verification.json').write_text(json.dumps(info,indent=2),encoding='utf-8')
(release.parent/'VT-4.0.8.sha256').write_text(sha+'  VT-4.0.8.apk\n',encoding='utf-8')
for name in ['Docs/VT_4.0.8.md','detailed_design/detailed_design_v4.0.8.md']:
 with (r/name).open('a',encoding='utf-8') as f:f.write('\n## Release verification\n\nFull desktop regression suite and independent JSON checks passed. New tests include 27 logged recovery geometries, controller/session submission and return/retreat interactions. Offline Android build succeeded. Embedded APK version 4.0.8, code 31 and bundled defaults verified. APK SHA-256: `'+sha+'`. Aircraft flight test not performed.\n')
print(json.dumps(info,indent=2))
