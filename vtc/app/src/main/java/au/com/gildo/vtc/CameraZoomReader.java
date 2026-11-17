package au.com.gildo.vtc;
import android.net.Network;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Single camera worker; zoom/clock HTTP never blocks the aiming executor. */
final class CameraZoomReader implements AutoCloseable {
    private final CameraPollLoop loop;
    private final Supplier<Settings> settings;
    private final Supplier<Network> network;
    private final SessionLog log;
    private final Supplier<ZoomPlan.Input> input;
    private final ZoomPlan planner=new ZoomPlan();
    private volatile ZoomSample sample;
    private volatile String zoomStatus="Auto-fit off";
    private String zoomConnection="";
    private int pendingTarget=-1;private long pendingAt;
    private static final class ZoomSample {
        final int value;final long at;final String endpoint;
        ZoomSample(int value,long at,String endpoint){this.value=value;this.at=at;this.endpoint=endpoint;}
    }
    void manualObservation(String reason){
        Settings s=settings.get();ZoomSample last=sample;ZoomPlan.Input gps=input.get();long now=SystemClock.elapsedRealtime();
        String expected="http://"+s.cameraHost+":"+s.cameraPort+"/ccapi/ver100/shooting/control/zoom";
        boolean same=last!=null&&last.endpoint.equals(expected);
        log.record("manual_zoom_observation","reason",reason,"manualZoomX",Double.isFinite(s.manualZoomX)?s.manualZoomX:null,
            "automaticZoom",s.autoZoom,"usableManualObservation",!s.autoZoom&&Double.isFinite(s.manualZoomX)&&same&&now-last.at<=5000,
            "ccapiZoom",same?last.value:null,"ccapiAgeMs",same?now-last.at:null,"distanceM",Double.isFinite(gps.distance)?gps.distance:null,
            "gpsAgeMs",gps.ageMs,"gpsValid",gps.valid,"settings",s.values);
    }
    private volatile HttpURLConnection connection;
    private volatile boolean closed;
    private volatile String status="Camera disabled — enter its IP in Setup",value="";
    private volatile long receivedAt;
    private String endpoint="",clockConnection="";
    private volatile String clockStatus="Clock not synchronised yet";
    private long clockAttempt;
    private boolean clockDone;

    CameraZoomReader(Supplier<Settings> settings,Supplier<Network> network,Supplier<ZoomPlan.Input> input,SessionLog log){
        this.settings=settings;this.network=network;this.input=input;this.log=log;
        loop=new CameraPollLoop(this::read,2000);
    }
    String status(){long at=receivedAt;return status+"\n"+zoomStatus+"\n"+clockStatus+(at==0?"":"\nLast zoom "+value+" · age "+String.format(java.util.Locale.US,"%.1f",(SystemClock.elapsedRealtime()-at)/1000.0)+" s");}
    private void read(){
        if(closed)return;
        Settings s=settings.get();String url=s.cameraHost.isEmpty()?"":"http://"+s.cameraHost+":"+s.cameraPort+"/ccapi/ver100/shooting/control/zoom";
        if(!url.equals(endpoint)){endpoint=url;value="";receivedAt=0;sample=null;planner.reset();pendingTarget=-1;}
        if(url.isEmpty()){log.record("camera_zoom_skipped","reason","Camera disabled","settings",s.values);status="Camera disabled — enter its IP in Setup";zoomStatus="No zoom commands: camera disabled";return;}
        Network wifi=network.get();
        if(wifi==null){sample=null;planner.reset();pendingTarget=-1;log.record("camera_zoom_skipped","reason","No connected Wi-Fi","settings",s.values);clockDone=false;clockConnection="";status="Camera waiting for Wi-Fi connection; aiming is independent";return;}
        String connectionKey=url+"@"+wifi.toString();
        if(!connectionKey.equals(zoomConnection)){zoomConnection=connectionKey;planner.reset();pendingTarget=-1;sample=null;}
        long began=SystemClock.elapsedRealtime();HttpURLConnection c=null;
        try{
            log.record("camera_zoom_request","url",url,"method","GET");status="Reading camera zoom";
            c=(HttpURLConnection)wifi.openConnection(new URL(url));connection=c;
            c.setRequestMethod("GET");c.setConnectTimeout(1500);c.setReadTimeout(1500);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
            int code=c.getResponseCode();if(code!=200)throw new IOException("Camera HTTP "+code);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){
                byte[] b=new byte[1024];int n;
                while((n=in.read(b))!=-1){bytes.write(b,0,n);if(bytes.size()>8192||SystemClock.elapsedRealtime()-began>4000)throw new IOException("Camera response exceeded read limit");}
            }
            String body=bytes.toString(StandardCharsets.UTF_8.name());JSONObject result=new JSONObject(body);
            int zoom=result.getInt("value");JSONObject ability=result.optJSONObject("ability");
            String text=String.valueOf(zoom)+(ability==null?"":" (range "+ability.getInt("min")+"–"+ability.getInt("max")+", step "+ability.getInt("step")+")");
            // Discard an old endpoint's response if settings changed while it was in flight.
            if(closed||settings.get()!=s||network.get()!=wifi)return;
            value=text;receivedAt=SystemClock.elapsedRealtime();sample=new ZoomSample(zoom,receivedAt,url);status="Camera zoom available";
            log.record("camera_zoom_response","url",url,"elapsedMs",receivedAt-began,"body",body,"zoom",zoom);
            if(pendingTarget>=0&&zoom==pendingTarget){log.record("camera_zoom_confirmed","requestedCcapi",pendingTarget,"readbackCcapi",zoom,"elapsedMs",receivedAt-pendingAt);pendingTarget=-1;}
            if(ability!=null)controlZoom(wifi,s,url,zoom,ability.getInt("min"),ability.getInt("max"),ability.getInt("step"));
            else {zoomStatus="Auto-fit waiting for camera limits";log.record("camera_zoom_skipped","reason","Camera ability missing");}
            String clockKey=url+"@"+wifi.toString();
            if(!clockKey.equals(clockConnection)){clockConnection=clockKey;clockDone=false;clockAttempt=0;clockStatus="Clock awaiting synchronisation";}
            if(!clockDone&&(clockAttempt==0||SystemClock.elapsedRealtime()-clockAttempt>=60000))syncClock(wifi,s);

        }catch(Exception e){
            if(!closed){status="Camera unavailable — retrying: "+e.getMessage();log.error("camera_zoom_error",e,"url",url,"elapsedMs",SystemClock.elapsedRealtime()-began,"settings",s.values,"zoomControlStatus",zoomStatus);}
        }finally{if(c!=null)c.disconnect();connection=null;}
    }
    private String clockRequest(Network wifi,String url,String method,String body)throws Exception {
        long began=SystemClock.elapsedRealtime();
        HttpURLConnection c=(HttpURLConnection)wifi.openConnection(new URL(url));connection=c;
        try {
            if(closed)throw new IOException("Camera worker closed");
            c.setRequestMethod(method);c.setConnectTimeout(1500);c.setReadTimeout(1500);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
            if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
                try(OutputStream out=c.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}}
            int code=c.getResponseCode();if(code!=200)throw new IOException("Camera HTTP "+code);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){byte[] b=new byte[1024];int n;
                while((n=in.read(b))!=-1){bytes.write(b,0,n);if(bytes.size()>8192||SystemClock.elapsedRealtime()-began>4000)throw new IOException("Clock response limit");}}
            String result=bytes.toString(StandardCharsets.UTF_8.name());
            log.record(url.endsWith("/zoom")?"camera_zoom_http":"camera_clock_http","method",method,"url",url,"request",body,"response",result,"elapsedMs",SystemClock.elapsedRealtime()-began);
            return result;
        }finally{c.disconnect();connection=null;}
    }
    private void controlZoom(Network wifi,Settings s,String url,int current,int min,int max,int step)throws Exception {
        long now=SystemClock.elapsedRealtime();ZoomPlan.Input gps=input.get();
        ZoomPlan.Decision decision=planner.decide(s,gps,now,min,max,step);
        decision.fields.put("currentCcapi",current);decision.fields.put("currentCcapiAgeMs",now-receivedAt);
        decision.fields.put("estimatedCurrentOpticalZoom",s.zoomMapping.optical(current));
        decision.fields.put("estimatedCurrentFrameWidthM",Double.isFinite(gps.distance)?gps.distance*s.wideFrameFactor/s.zoomMapping.optical(current):null);
        String action;
        if(!decision.enabled){action=String.valueOf(decision.fields.get("reason"));pendingTarget=-1;}
        else if(current==decision.target||ZoomPlan.withinWidth(s,gps,decision,current))action="Within 5% width tolerance or at nearest zoom position";
        else if(pendingTarget>=0&&now-pendingAt<5000)action="Waiting for previous zoom readback (up to 5 seconds)";
        else action="Send zoom";
        decision.fields.put("action",action);decision.fields.put("pendingCcapi",pendingTarget<0?null:pendingTarget);
        log.record("zoom_decision","decision",decision.fields);
        zoomStatus=!decision.enabled?action:"Auto-fit "+decision.fields.get("effectiveFrameWidthMetres")+" m · estimated "+decision.fields.get("estimatedFrameWidthM")+" m · "+(Boolean.TRUE.equals(decision.fields.get("clamped"))?"lens/camera limit · ":"")+
            " · requested "+decision.fields.get("limitedOpticalZoom")+"× (estimated) · "+action;
        if(!action.equals("Send zoom"))return;
        if(closed||settings.get()!=s||network.get()!=wifi){log.record("camera_zoom_skipped","reason","Settings or network changed before command");return;}
        ZoomPlan.Input fresh=input.get();if(!fresh.valid){log.record("camera_zoom_skipped","reason","GPS expired before command");return;}
        pendingTarget=decision.target;pendingAt=now;
        String body=new JSONObject().put("value",decision.target).toString();
        log.record("camera_zoom_command","requestedCcapi",decision.target,"decision",decision.fields,"method","POST","url",url,"body",body);
        // POST acknowledgement is not treated as physical arrival; next GET confirms it.
        try{String response=clockRequest(wifi,url,"POST",body);log.record("camera_zoom_ack","requestedCcapi",decision.target,"response",response,"elapsedMs",SystemClock.elapsedRealtime()-now);}
        catch(Exception e){log.error("camera_zoom_command_error",e,"requestedCcapi",decision.target,"elapsedMs",SystemClock.elapsedRealtime()-now);throw e;}
    }
    private void syncClock(Network wifi,Settings s){
        clockAttempt=SystemClock.elapsedRealtime();
        String url="http://"+s.cameraHost+":"+s.cameraPort+"/ccapi/ver100/functions/datetime";
        try {
            JSONObject before=new JSONObject(clockRequest(wifi,url,"GET",null));
            before.getString("datetime");before.getBoolean("dst");
            if(closed||settings.get()!=s||network.get()!=wifi)return;
            java.util.TimeZone zone=java.util.TimeZone.getDefault();long now=System.currentTimeMillis();
            boolean dst=zone.inDaylightTime(new java.util.Date(now));
            JSONObject body=new JSONObject().put("datetime",CameraClock.format(now,zone)).put("dst",dst);
            clockRequest(wifi,url,"PUT",body.toString());
            JSONObject after=new JSONObject(clockRequest(wifi,url,"GET",null));
            long difference=CameraClock.parse(after.getString("datetime"))-System.currentTimeMillis();
            boolean verified=Math.abs(difference)<=5000&&after.getBoolean("dst")==dst
                &&after.getString("datetime").endsWith(body.getString("datetime").substring(body.getString("datetime").length()-5));
            log.record("camera_clock_verified","before",before.toString(),"requested",body.toString(),"readback",after.toString(),
                "differenceMs",difference,"verified",verified,"phoneTimezone",zone.getID());
            if(!verified)throw new IOException("Clock readback differs from phone");
            clockDone=true;clockStatus="Clock synchronised to phone ("+zone.getID()+")";
        }catch(Exception e){clockStatus="Clock sync unavailable; retrying in 60 s";log.error("camera_clock_sync_failed",e);}
    }
    public void close(){closed=true;loop.close();HttpURLConnection c=connection;if(c!=null)c.disconnect();}
}
