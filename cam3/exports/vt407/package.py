from pathlib import Path
import hashlib,json,shutil,subprocess,zipfile
root=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
logs=Path('C:/Users/gildo/gdlapp/cam3/exports/vt407')
tests=(logs/'tests.log').read_text(encoding='utf-8-sig')
assert 'PASS: VT4.0.7 99 ' in tests
assert 'PASS: VT407 independent JSON parser' in tests
assert 'Exception' not in tests
assert 'BUILD SUCCESSFUL' in (logs/'build-final.log').read_text(encoding='utf-8-sig')
apk=root/'SampleCode-V5/android-sdk-v5-sample/build/outputs/apk/debug/sample-debug.apk'
meta=json.loads((apk.parent/'output-metadata.json').read_text())
assert meta['elements'][0]['versionName']=='4.0.7' and meta['elements'][0]['versionCode']==30
with zipfile.ZipFile(apk) as z:
 settings=json.loads(z.read('assets/vt_settings.json'))
 assert settings['returnBoundaryStandOffMetres']==10
 assert settings['routePlanningAllowanceMetres']==2 and settings['maxExcursionMetres']==300
 assert 'waveLineId' in settings and 'shorelineId' not in settings
 assert json.loads(z.read('assets/vt_wave_lines.json'))=={'version':1,'waveLines':[]}
 assert 'assets/vt_shorelines.json' not in z.namelist()
aapts=list(Path('C:/Users/gildo/AppData/Local/Android/Sdk/build-tools').glob('*/aapt.exe'))
assert aapts
badging=subprocess.check_output([str(sorted(aapts)[-1]),'dump','badging',str(apk)],text=True,encoding='utf-8')
line=badging.splitlines()[0]
assert "versionCode='30'" in line and "versionName='4.0.7'" in line
release=root/'build/releases/VT-4.0.7.apk';release.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(apk,release)
sha=hashlib.sha256(release.read_bytes()).hexdigest().upper()
verification={'version':'4.0.7','versionCode':30,'apk':str(release),'sha256':sha,'sizeBytes':release.stat().st_size,'package':line,'regressionSuite':'passed','vt407Assertions':99,'flightTest':'not performed'}
(logs/'verification.json').write_text(json.dumps(verification,indent=2),encoding='utf-8')
(release.parent/'VT-4.0.7.sha256').write_text(sha+'  VT-4.0.7.apk\n',encoding='utf-8')
for name in ['Docs/VT_4.0.7.md','detailed_design/detailed_design_v4.0.7.md']:
 p=root/name
 with p.open('a',encoding='utf-8') as f:f.write('\n\n## Release verification\n\n- Full desktop regression suite passed, including 99 VT 4.0.7 checks and independent JSONL validation.\n- Offline Android debug APK build passed; package version 4.0.7, code 30.\n- Packaged assets verified: fresh Wave line schema, return stand-off 10 m, planning allowance 2 m, excursion 300 m.\n- APK SHA-256: `'+sha+'`.\n- Aircraft flight test remains pending.\n')
print(json.dumps(verification,indent=2))
