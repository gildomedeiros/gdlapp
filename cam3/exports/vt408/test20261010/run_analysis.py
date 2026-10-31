from pathlib import Path
import zipfile
base=Path('C:/Users/gildo/gdlapp/cam3/exports/vt408')
out=base/'test20261010';inputs=out/'input';inputs.mkdir(parents=True,exist_ok=True)
archive=Path('C:/Users/gildo/temp/CAM3/cam3_full_2026-10-10_153736_128_a258698f.zip')
with zipfile.ZipFile(archive) as z:
 for item in z.infolist():
  if not item.filename.endswith('.jsonl'):continue
  assert item.filename==Path(item.filename).name,'Unexpected archive path'
  (inputs/item.filename).write_bytes(z.read(item))
for name in ['analyse_flights.py','audit_flights.py']:
 script=(base/name).read_text(encoding='utf-8')
 script=script.replace('C:/Users/gildo/temp/408','__FLIGHT_INPUT__')
 script=script.replace('C:/Users/gildo/gdlapp/cam3/exports/vt408',str(out).replace('\\','/'))
 script=script.replace('__FLIGHT_INPUT__',str(inputs).replace('\\','/'))
 if name=='audit_flights.py':
  script=script.replace('failures=[];metrics=', '''retreatConfig=next((json.loads(x['effectiveJson']) for x in rows if x.get('event')=='retreat_settings_config'),{})
 threshold=retreatConfig.get('minimumDistanceMetres',0) if retreatConfig.get('enabled') else 0
 failures=[];metrics=''')
  script=script.replace("m['surferDistanceM']<19-1e-5","m['surferDistanceM']<threshold-1e-5")
  script=script.replace("'configurationBlocks':[x", "'retreatThreshold':threshold,'configurationBlocks':[x")
 exec(compile(script,name,'exec'),{'__name__':'__main__'})
