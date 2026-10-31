function gpsDistance(a,b){
 const rad=Math.PI/180, lat1=Number(a.dataset.lat)*rad,lat2=Number(b.dataset.lat)*rad;
 const dl=(Number(b.dataset.lon)-Number(a.dataset.lon))*rad;
 const h=Math.sin((lat2-lat1)/2)**2+Math.cos(lat1)*Math.cos(lat2)*Math.sin(dl/2)**2;
 return 12742000*Math.asin(Math.sqrt(Math.min(1,Math.max(0,h))));
}

const d=gpsDistance({dataset:{lat:0,lon:0}},{dataset:{lat:0,lon:.001}});if(Math.abs(d-111.1949266)>.0001)throw Error(d);console.log('PASS: horizontal GPS distance');