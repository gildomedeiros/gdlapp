package au.com.gildo.vtc;
import android.net.Network;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Own worker, one outstanding read, no commands and no dependency from the aiming loop. */
final class CameraZoomReader implements AutoCloseable {
    private final CameraPollLoop loop;
    private final Supplier<Settings> settings;
    private final Supplier<Network> network;
    private final SessionLog log;
    private volatile HttpURLConnection connection;
    private volatile boolean closed;
    private volatile String status="Camera disabled — enter its IP in Setup",value="";
    private volatile long receivedAt;
    private String endpoint="",clockConnection="";
    private volatile String clockStatus="Clock not synchronised yet";
    private long clockAttempt;
    private boolean clockDone;

    CameraZoomReader(Supplier<Settings> settings,Supplier<Network> network,SessionLog log){
        this.settings=settings;this.network=network;this.log=log;
        loop=new CameraPollLoop(this::read,2000);
    }
    String status(){long at=receivedAt;return status+"\n"+clockStatus+(at==0?"":"\nLast zoom "+value+" · age "+String.format(java.util.Locale.US,"%.1f",(SystemClock.elapsedRealtime()-at)/1000.0)+" s");}
    private void read(){
        if(closed)return;
        Settings s=settings.get();String url=s.cameraHost.isEmpty()?"":"http://"+s.cameraHost+":"+s.cameraPort+"/ccapi/ver100/shooting/control/zoom";
        if(!url.equals(endpoint)){endpoint=url;value="";receivedAt=0;}
        if(url.isEmpty()){status="Camera disabled — enter its IP in Setup";return;}
        Network wifi=network.get();
        if(wifi==null){clockDone=false;clockConnection="";status="Camera waiting for Wi-Fi connection; aiming is independent";return;}
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
            if(closed||settings.get()!=s)return;
            value=text;receivedAt=SystemClock.elapsedRealtime();status="Camera zoom available";
            log.record("camera_zoom_response","url",url,"elapsedMs",receivedAt-began,"body",body,"zoom",zoom);
            String clockKey=url+"@"+wifi.toString();
            if(!clockKey.equals(clockConnection)){clockConnection=clockKey;clockDone=false;clockAttempt=0;clockStatus="Clock awaiting synchronisation";}
            if(!clockDone&&(clockAttempt==0||SystemClock.elapsedRealtime()-clockAttempt>=60000))syncClock(wifi,s);

        }catch(Exception e){
            if(!closed){status="Camera unavailable — retrying: "+e.getMessage();log.error("camera_zoom_error",e,"url",url,"elapsedMs",SystemClock.elapsedRealtime()-began);}
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
            int code=c.getResponseCode();if(code!=200)throw new IOException("Clock HTTP "+code);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){byte[] b=new byte[1024];int n;
                while((n=in.read(b))!=-1){bytes.write(b,0,n);if(bytes.size()>8192||SystemClock.elapsedRealtime()-began>4000)throw new IOException("Clock response limit");}}
            String result=bytes.toString(StandardCharsets.UTF_8.name());
            log.record("camera_clock_http","method",method,"url",url,"request",body,"response",result,"elapsedMs",SystemClock.elapsedRealtime()-began);
            return result;
        }finally{c.disconnect();connection=null;}
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
