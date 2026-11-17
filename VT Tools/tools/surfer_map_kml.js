// Export checked packets within the current filters, using logged GPS coordinates.
// Display-only layer switches do not remove one half of a selected surfer/drone pair.
function kmlCoordinate(latitude,longitude){
 return Number.isFinite(latitude)&&Math.abs(latitude)<=90&&Number.isFinite(longitude)&&Math.abs(longitude)<=180
  ?longitude+','+latitude+',0':null;
}
function selectedKmlGeometry(){
 const points=mapFilters.points.filter(p=>visiblePairs.has(p.id));
 const surfers=points.filter(p=>kmlCoordinate(p.latitude,p.longitude)!==null);
 const drones=points.filter(p=>kmlCoordinate(p.droneLatitude,p.droneLongitude)!==null);
 const waves=document.getElementById('kml-include-wave').checked?(mapFilters.waveLines||[]).filter(w=>
  w.pairs?.some(id=>visiblePairs.has(id))&&kmlCoordinate(w.pointA?.latitude,w.pointA?.longitude)!==null&&
  kmlCoordinate(w.pointB?.latitude,w.pointB?.longitude)!==null):[];
 return {points,surfers,drones,waves};
}
function updateKmlExportSelection(){
 const {points,surfers,drones,waves}=selectedKmlGeometry();
 document.getElementById('download-selected-kml').disabled=surfers.length+drones.length===0;
 const counts=surfers.length+' surfer / '+drones.length+' drone positions';
 document.getElementById('selected-kml-status').textContent=!points.length?'Select positions to export.':
  counts+(document.getElementById('kml-include-wave').checked?' · '+waves.length+' wave line'+(waves.length===1?'':'s'):'')+
  (drones.length<points.length?' · '+(points.length-drones.length)+' without drone GPS':'');
}
function buildSelectedKml(){
 const selected=selectedKmlGeometry(),ns='http://www.opengis.net/kml/2.2';
 const xml=document.implementation.createDocument(ns,'kml',null);
 function node(parent,name,text){
  const el=xml.createElementNS(ns,name);if(text!==undefined)el.textContent=String(text);parent.append(el);return el;
 }
 const doc=node(xml.documentElement,'Document');
 node(doc,'name','Selected surfer and drone positions');
 node(doc,'description','Logged GPS positions for the checked map pairs. Points are clamped to ground; no altitude is inferred.');
 for(const [id,color] of [['surfer','ff4a8014'],['drone','ffff5500'],['retreat','ff0075e8'],['unknown-drone','ff888888']]){
  const style=node(doc,'Style');style.setAttribute('id',id);
  const icon=node(style,'IconStyle');node(icon,'color',color);node(icon,'scale','0.9');
  node(node(icon,'Icon'),'href','https://maps.google.com/mapfiles/kml/shapes/placemark_circle.png');
 }
 const waveStyle=node(doc,'Style');waveStyle.setAttribute('id','wave');
 const lineStyle=node(waveStyle,'LineStyle');node(lineStyle,'color','ff987d00');node(lineStyle,'width','3');
 function folder(name){const f=node(doc,'Folder');node(f,'name',name);return f;}
 function metadata(mark,values){
  const data=node(mark,'ExtendedData');
  for(const [key,value] of Object.entries(values)){
   if(value===null||value===undefined)continue;
   const field=node(data,'Data');field.setAttribute('name',key);node(field,'value',value);
  }
 }
 function point(folder,p,isDrone){
  const id=isDrone?(p.droneId||'D'+p.id.slice(1)):p.id,paired=isDrone?p.id:(p.droneId||'D'+p.id.slice(1));
  const time=isDrone?p.droneTime:p.time,mark=node(folder,'Placemark');
  node(mark,'name',id);
  node(mark,'description',id+' paired with '+paired+(time?'\n'+(isDrone?'Drone report: ':'GPS received: ')+time:''));
  node(mark,'styleUrl','#'+(isDrone?(p.retreatActive===true?'retreat':p.retreatActive===false?'drone':'unknown-drone'):'surfer'));
  if(time)node(node(mark,'TimeStamp'),'when',time);
  metadata(mark,{pairedWith:paired,surferReceivedAt:p.time,sourceFile:p.sourceFile,
   sourceLine:isDrone?p.droneSourceLine:p.sourceLine,packet:p.sequence,distanceMetres:p.distance,
   gimbalPitchDegrees:p.pitch,...(isDrone?{retreatActive:p.retreatActive}:{speedKmh:p.speedKmh,rideDetected:p.waveRiding})});
  const geometry=node(mark,'Point');node(geometry,'altitudeMode','clampToGround');
  node(geometry,'coordinates',isDrone?kmlCoordinate(p.droneLatitude,p.droneLongitude):kmlCoordinate(p.latitude,p.longitude));
 }
 const surferFolder=folder('Surfer positions'),droneFolder=folder('Drone positions');
 selected.surfers.forEach(p=>point(surferFolder,p,false));selected.drones.forEach(p=>point(droneFolder,p,true));
 if(selected.waves.length){
  const waveFolder=folder('Wave lines');
  selected.waves.forEach(w=>{
   const mark=node(waveFolder,'Placemark');node(mark,'name',w.label||'Wave line');node(mark,'styleUrl','#wave');
   metadata(mark,{sourceFile:w.sourceFile,session:w.session});
   const line=node(mark,'LineString');node(line,'tessellate','1');node(line,'altitudeMode','clampToGround');
   node(line,'coordinates',[w.pointA,w.pointB].map(p=>kmlCoordinate(p.latitude,p.longitude)).join(' '));
  });
 }
 return '<?xml version="1.0" encoding="UTF-8"?>\n'+new XMLSerializer().serializeToString(xml);
}
function downloadSelectedKml(){
 const {points,surfers,drones}=selectedKmlGeometry();if(!surfers.length&&!drones.length)return;
 const blob=new Blob([buildSelectedKml()],{type:'application/vnd.google-earth.kml+xml;charset=utf-8'});
 const url=URL.createObjectURL(blob),link=document.createElement('a');
 link.href=url;link.download='surfer-drone-'+points[0].id+(points.length>1?'-to-'+points[points.length-1].id:'')+'.kml';
 document.body.append(link);link.click();link.remove();
 setTimeout(()=>URL.revokeObjectURL(url),1000);
}
function initializeKmlExport(){
 document.getElementById('download-selected-kml').addEventListener('click',downloadSelectedKml);
 document.getElementById('kml-include-wave').addEventListener('change',updateKmlExportSelection);
}
