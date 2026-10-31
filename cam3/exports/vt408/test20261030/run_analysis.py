from pathlib import Path
base=Path('C:/Users/gildo/gdlapp/cam3/exports/vt408')
out=base/'test20261030'; inputs=out/'input'
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

