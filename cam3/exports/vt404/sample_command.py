import zipfile,json
z=zipfile.ZipFile(r'C:/Users/gildo/temp/vt404test/cam3_full_2026-10-05_220638_442_05e26c81.zip')
for l in z.read('cam3_full_2026-10-05_214841_088_ebd4710f.jsonl').splitlines():
 r=json.loads(l)
 if r['event']=='yaw_command':print(r);break
