import math,datetime
lat=-27.95319480790852;lon=153.42940800981407
lat2=-27.95438891732664;lon2=153.42937045888743
p1,p2=map(math.radians,[lat,lat2]);dl=math.radians(lon2-lon);b=math.degrees(math.atan2(math.sin(dl)*math.cos(p2),math.cos(p1)*math.sin(p2)-math.sin(p1)*math.cos(p2)*math.cos(dl)))%360
hr=7+13/60;day=datetime.date(2026,10,4).timetuple().tm_yday;g=2*math.pi/365*(day-1+(hr-12)/24)
eq=229.18*(.000075+.001868*math.cos(g)-.032077*math.sin(g)-.014615*math.cos(2*g)-.040849*math.sin(2*g))
dec=.006918-.399912*math.cos(g)+.070257*math.sin(g)-.006758*math.cos(2*g)+.000907*math.sin(2*g)-.002697*math.cos(3*g)+.00148*math.sin(3*g)
h=math.radians((hr*60+eq+4*lon-60*10)/4-180)
el=math.degrees(math.asin(math.sin(p1)*math.sin(dec)+math.cos(p1)*math.cos(dec)*math.cos(h)))
az=(math.degrees(math.atan2(math.sin(h),math.cos(h)*math.sin(p1)-math.tan(dec)*math.cos(p1)))+180)%360
print('A to B bearing',b,'solar approx azimuth',az,'elevation',el)
