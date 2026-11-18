package au.com.gildo.vtc;
import java.util.*;
final class CalibrationMapData {
    static String coords(double lat,double lon){return String.format(Locale.US,"%.9f,%.9f,0",lon,lat);}
    static String kml(SavedLandmark mark){
        Settings s=mark.saved.settings;
        String camera=coords(s.latitude,s.longitude),landmark=coords(s.landmarkLat,s.landmarkLon);
        double distance=Geo.distance(s.latitude,s.longitude,s.landmarkLat,s.landmarkLon);
        String description=String.format(Locale.US,"Saved calibration: %s. Distance %.2f m; bearing / camera heading at calibration %.3f degrees true; elevation %.3f degrees. Camera height %.2f m and landmark height %.2f m above configured water level. Heading is calculated, not visually verified. Positions shown clamped to ground; heights are not GPS altitude.",java.time.Instant.ofEpochMilli(mark.savedAtEpochMs),distance,mark.saved.bearing,mark.saved.elevation,s.height,s.landmarkHeight);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>VTC calibration</name><description>"+description+"</description>"
            +"<Style id=\"heading\"><LineStyle><color>ff00a5ff</color><width>4</width></LineStyle></Style>"
            +"<Placemark><name>Camera</name><Point><coordinates>"+camera+"</coordinates></Point></Placemark>"
            +"<Placemark><name>Landmark / heading endpoint</name><Point><coordinates>"+landmark+"</coordinates></Point></Placemark>"
            +"<Placemark><name>Camera to landmark — heading at calibration</name><styleUrl>#heading</styleUrl><LineString><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode><coordinates>"+camera+" "+landmark+"</coordinates></LineString></Placemark></Document></kml>";
    }
    static String html(SavedLandmark mark,String key){
        return html(mark.saved.settings,key);
    }
    static String html(Settings s,String key){
        double dx=Geo.delta(s.landmarkLon,s.longitude)*Math.cos(Math.toRadians(s.latitude)),dy=s.landmarkLat-s.latitude;
        double length=Math.max(1e-12,Math.hypot(dx,dy)),x=200+dx/length*110,y=160-dy/length*110;
        String fallback=String.format(Locale.US,"<svg viewBox='0 0 400 320' width='100%%' height='100%%'><defs><marker id='arrow' markerWidth='8' markerHeight='8' refX='7' refY='3' orient='auto'><path d='M0,0 L0,6 L8,3 z' fill='orange'/></marker></defs><text x='12' y='24' fill='white'>North ↑ · direction diagram, no map tiles</text><line x1='200' y1='160' x2='%.2f' y2='%.2f' stroke='orange' stroke-width='3' marker-end='url(#arrow)'/><circle cx='200' cy='160' r='7' fill='cyan'/><text x='210' y='178' fill='white'>Camera</text><circle cx='%.2f' cy='%.2f' r='7' fill='orange'/><text x='%.2f' y='%.2f' fill='white'>Landmark</text></svg>",x,y,x,y,x-35,y-12);
        String doc="<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>html,body{margin:0;background:#102030;color:white;font:14px sans-serif}#map{height:340px}#status{padding:8px}</style></head><body><div id='status'>"+(key.isEmpty()?"Add a Google Maps JavaScript API key to load Google Maps.":"Loading Google Maps… internet required. The direction diagram remains if loading fails.")+"</div><div id='map'>"+fallback+"</div>";
        if(!key.isEmpty()){
            String a=String.format(Locale.US,"{lat:%.9f,lng:%.9f}",s.latitude,s.longitude),b=String.format(Locale.US,"{lat:%.9f,lng:%.9f}",s.landmarkLat,s.landmarkLon);
            doc+="<script>window.gm_authFailure=()=>document.getElementById('status').textContent='Google Maps authentication failed: check key, API, billing and restrictions.';window.initMap=function(){const a="+a+",b="+b+";const map=new google.maps.Map(document.getElementById('map'),{mapTypeId:'hybrid',gestureHandling:'cooperative'});new google.maps.Marker({position:a,map:map,label:'C',title:'Camera'});new google.maps.Marker({position:b,map:map,label:'L',title:'Landmark'});new google.maps.Polyline({path:[a,b],map:map,strokeColor:'#ff9900',strokeWeight:4,icons:[{icon:{path:google.maps.SymbolPath.FORWARD_CLOSED_ARROW},offset:'100%'}]});const bounds=new google.maps.LatLngBounds();bounds.extend(a);bounds.extend(b);map.fitBounds(bounds,50);document.getElementById('status').textContent='Current saved coordinates · C camera → L landmark · geographic bearing';};</script><script async src='https://maps.googleapis.com/maps/api/js?key="+key+"&amp;callback=initMap&amp;loading=async' onerror=\"document.getElementById('status').textContent='Google Maps unavailable. Connect internet Wi-Fi and tap Refresh map.'\"></script>";
        }
        return doc+"</body></html>";
    }
}
