package au.com.gildo.vtc;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Network;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Serial camera requests on a worker separate from the aiming executor. */
final class CameraZoomReader implements AutoCloseable {
    private final CameraPollLoop loop;
    private final Supplier<Settings> settings;
    private final Supplier<Network> network;
    private final SessionLog log;
    private final Supplier<ZoomPlan.Input> input;
    private final ZoomPlan planner=new ZoomPlan();
    private final CameraRecording recording = new CameraRecording();
    private volatile String recordingStatus = "Recording waiting for Start Aiming";
    private volatile ZoomSample sample;
    private record CameraConnection(Network network,String host,int port){}
    private volatile CameraConnection cameraConnection;

    boolean connected(){
        CameraConnection last=cameraConnection;
        Settings current=settings.get();
        return !closing&&!closed&&last!=null&&last.network().equals(network.get())
            &&last.host().equals(current.cameraHost)&&last.port()==current.cameraPort;
    }
    private volatile String zoomStatus="Zoom off; leaving zoom unchanged";
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
            "automaticZoom",s.autoZoom,"usableManualObservation",(!s.autoZoom||gps.aimingSession<0)&&Double.isFinite(s.manualZoomX)&&same&&now-last.at<=5000,
            "ccapiZoom",same?last.value:null,"ccapiAgeMs",same?now-last.at:null,"distanceM",Double.isFinite(gps.distance)?gps.distance:null,
            "gpsAgeMs",gps.ageMs,"gpsValid",gps.valid,"settings",s.values);
    }
    private volatile HttpURLConnection connection;
    private volatile boolean closing,closed;
    private final CompletableFuture<Void> closeComplete=new CompletableFuture<>();
    private Network recordingNetwork;
    private String recordingBase="";
    private boolean stopSent;
    private final AtomicReference<BiConsumer<Bitmap, String>> preview = new AtomicReference<>();
    private static final int PREVIEW_TIMEOUT_MS = 1500;
    private static final int MAX_PREVIEW_BYTES = 2 * 1024 * 1024;
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
    String status() {
        ZoomSample last=sample;
        Settings s=settings.get();
        ZoomPlan.Input gps=input.get();
        String expected="http://"+s.cameraHost+":"+s.cameraPort+"/ccapi/ver100/shooting/control/zoom";
        String width="Width unavailable";
        String readback="";
        if(last!=null&&last.endpoint.equals(expected)){
            double age=(SystemClock.elapsedRealtime()-last.at)/1000.0;
            readback=String.format(java.util.Locale.US,"\nLast zoom %s · age %.1f s",value,age);
            if(gps.valid&&Double.isFinite(gps.distance)&&gps.distance>0)
                width=String.format(java.util.Locale.US,"Estimated width %.1f m · zoom readback age %.1f s",
                    gps.distance*s.wideFrameFactor/s.zoomMapping.optical(last.value),age);
        }
        String controlStatus=s.autoZoom&&gps.aimingSession<0
            ? "Automatic zoom waiting for Start Aiming; reading zoom" : zoomStatus;
        return status+"\n"+controlStatus+"\n"+clockStatus+readback+"\n"+width+"\n"+recordingStatus;
    }
    private void read(){
        if(closed)return;
        Settings s=settings.get();String url=s.cameraHost.isEmpty()?"":"http://"+s.cameraHost+":"+s.cameraPort+"/ccapi/ver100/shooting/control/zoom";
        if(!url.equals(endpoint)){cameraConnection=null;endpoint=url;value="";receivedAt=0;sample=null;planner.reset();pendingTarget=-1;recording.reset();}
        if(url.isEmpty())recordingStatus="Recording unavailable · camera disabled";
        if(url.isEmpty()){log.record("camera_zoom_skipped","reason","Camera disabled","settings",s.values);status="Camera disabled — enter its IP in Setup";zoomStatus="No zoom commands: camera disabled";return;}
        Network wifi=network.get();
        if(wifi==null){cameraConnection=null;recording.reset();recordingStatus="Recording status unavailable · waiting for Wi-Fi";sample=null;planner.reset();pendingTarget=-1;log.record("camera_zoom_skipped","reason","No connected Wi-Fi","settings",s.values);clockDone=false;clockConnection="";status="Camera waiting for Wi-Fi connection; aiming is independent";return;}
        String connectionKey=url+"@"+wifi.toString();
        if(!connectionKey.equals(zoomConnection)){zoomConnection=connectionKey;planner.reset();pendingTarget=-1;sample=null;recording.reset();}
        readRecording(wifi,s);
        if(closed)return;
        long began=SystemClock.elapsedRealtime();HttpURLConnection c=null;boolean zoomRead=false;
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
            cameraConnection=new CameraConnection(wifi,s.cameraHost,s.cameraPort);zoomRead=true;
            log.record("camera_zoom_response","url",url,"elapsedMs",receivedAt-began,"body",body,"zoom",zoom,"opticalZoomX",s.zoomMapping.optical(zoom));
            if(pendingTarget>=0&&zoom==pendingTarget){log.record("camera_zoom_confirmed","requestedCcapi",pendingTarget,"readbackCcapi",zoom,"elapsedMs",receivedAt-pendingAt);pendingTarget=-1;}
            if(ability!=null)controlZoom(wifi,s,url,zoom,ability.getInt("min"),ability.getInt("max"),ability.getInt("step"));
            else {zoomStatus="Zoom waiting for camera limits";log.record("camera_zoom_skipped","reason","Camera ability missing");}
            String clockKey=url+"@"+wifi.toString();
            if(!clockKey.equals(clockConnection)){clockConnection=clockKey;clockDone=false;clockAttempt=0;clockStatus="Clock awaiting synchronisation";}
            if(!clockDone&&(clockAttempt==0||SystemClock.elapsedRealtime()-clockAttempt>=60000))syncClock(wifi,s);

        }catch(Exception e){
            if(!zoomRead)cameraConnection=null;
            if(!closed){status="Camera unavailable — retrying: "+e.getMessage();log.error("camera_zoom_error",e,"url",url,"elapsedMs",SystemClock.elapsedRealtime()-began,"settings",s.values,"zoomControlStatus",zoomStatus);}
        }finally{if(c!=null)c.disconnect();connection=null;}
    }
    private String clockRequest(Network wifi,String url,String method,String body)throws Exception {
        return clockRequest(wifi,url,method,body,()->true);
    }
    private String clockRequest(Network wifi,String url,String method,String body,
                                java.util.function.BooleanSupplier maySend)throws Exception {
        return jsonRequest(wifi,url,method,body,maySend,
                url.endsWith("/zoom")?"camera_zoom_http":"camera_clock_http");
    }

    private String jsonRequest(Network wifi, String url, String method, String body,
                               java.util.function.BooleanSupplier maySend, String logType)throws IOException {
        if (closed) throw new IOException("Camera worker closed");
        long began=SystemClock.elapsedRealtime();
        HttpURLConnection c=(HttpURLConnection)wifi.openConnection(new URL(url));connection=c;
        try {
            if(closed)throw new IOException("Camera worker closed");
            c.setRequestMethod(method);c.setConnectTimeout(1500);c.setReadTimeout(1500);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
            if(body!=null){
                if(!maySend.getAsBoolean())throw new CancellationException();
                c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
                try(OutputStream out=c.getOutputStream()){
                    if(!maySend.getAsBoolean())throw new CancellationException();
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code=c.getResponseCode();
            boolean accepted=code==200;
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=accepted?c.getInputStream():c.getErrorStream()){
                if(in!=null){
                    byte[] b=new byte[1024];int n;
                    while((n=in.read(b))!=-1){
                        if(n>65536-bytes.size()||SystemClock.elapsedRealtime()-began>4000)throw new IOException("Camera response exceeded read limit");
                        bytes.write(b,0,n);
                    }
                }
            }
            String result=bytes.toString(StandardCharsets.UTF_8.name());
            if(logType!=null)log.record(logType,"method",method,"url",url,"request",body,"response",result,
                    "httpStatus",code,"elapsedMs",SystemClock.elapsedRealtime()-began);
            if(!accepted)throw CameraRecording.HttpError.fromResponse(code,result);
            return result;
        }finally{c.disconnect();connection=null;}
    }
    private void readRecording(Network wifi, Settings settingsAtPoll) {
        String base = "http://" + settingsAtPoll.cameraHost + ":" + settingsAtPoll.cameraPort + "/ccapi/ver100";
        if(!base.equals(recordingBase)||!wifi.equals(recordingNetwork))stopSent=false;
        recordingBase=base;recordingNetwork=wifi;
        long session = input.get().aimingSession;
        try {
            String events = jsonRequest(wifi, base + "/event/polling", "GET", null, () -> true, null);
            if (!events.trim().isEmpty()) {
                String action = recording.observe(events, SystemClock.elapsedRealtime());
                if ("start".equals(action))stopSent=false;
                if (!action.isEmpty()) log.record("camera_recording_event", "action", action, "aimingSession", session);
            }
        } catch (Exception failure) {
            if (session >= 0 && !closed) log.error("camera_recording_event_error", failure);
        }

        if (closed) return;
        session = input.get().aimingSession;
        final long aimingSession = session;
        try {
            recording.update(aimingSession, settingsAtPoll.maxRecordingMinutes, SystemClock::elapsedRealtime,
                    (method, path, body) -> {
                        try {
                            if(body.equals("{\"action\":\"start\"}"))stopSent=false;
                            return jsonRequest(wifi, base + path, method, body,
                                    () -> canRecord(wifi, settingsAtPoll, aimingSession), "camera_recording_command");
                        } catch (CancellationException cancelled) {
                            throw new IOException("Aiming or camera settings changed", cancelled);
                        }
                    });
        } catch (IOException failure) {
            if (!canRecord(wifi, settingsAtPoll, aimingSession)) return;
            recordingStatus = "Recording command unconfirmed · " + failure.getMessage();
            log.error("camera_recording_error", failure, "aimingSession", aimingSession,
                    "maxRecordingMinutes", settingsAtPoll.maxRecordingMinutes);
            return;
        }

        long began = recording.startedAt();
        if (began >= 0) {
            long elapsedSeconds = (SystemClock.elapsedRealtime() - began) / 1000;
            recordingStatus = "Recording · " + elapsedSeconds + " s since start observed"
                    + (aimingSession < 0 ? " · rollover inactive"
                    : settingsAtPoll.maxRecordingMinutes == 0 ? "" : " · limit " + settingsAtPoll.values.get("maxRecordingMinutes") + " min");
        } else if (aimingSession < 0) {
            recordingStatus = "Recording waiting for Start Aiming";
        } else if (!recordingStatus.startsWith("Recording command unconfirmed")) {
            recordingStatus = "Waiting for the camera to start recording";
        }
    }

    private boolean canRecord(Network wifi, Settings requested, long aimingSession) {
        Settings current = settings.get();
        return !closing && !closed && aimingSession >= 0 && input.get().aimingSession == aimingSession
                && wifi.equals(network.get()) && current.cameraHost.equals(requested.cameraHost)
                && current.cameraPort == requested.cameraPort
                && current.maxRecordingMinutes == requested.maxRecordingMinutes;
    }

    private void controlZoom(Network wifi,Settings s,String url,int current,int min,int max,int step)throws Exception {
        long now=SystemClock.elapsedRealtime();ZoomPlan.Input gps=input.get();
        ZoomPlan.Decision decision=planner.decide(s,gps,now,min,max,step);
        decision.fields.put("currentCcapi",current);decision.fields.put("currentCcapiAgeMs",now-receivedAt);
        decision.fields.put("estimatedCurrentOpticalZoom",s.zoomMapping.optical(current));
        decision.fields.put("estimatedCurrentFrameWidthM",Double.isFinite(gps.distance)?gps.distance*s.wideFrameFactor/s.zoomMapping.optical(current):null);
        String action;
        if(!decision.enabled)action=String.valueOf(decision.fields.get("reason"));
        else if(current==decision.target)action="At requested zoom position";
        else if(ZoomPlan.withinWidth(s,gps,decision,current))action="Within 5% width tolerance";
        else if(pendingTarget>=0&&now-pendingAt<5000)action="Waiting for previous zoom readback (up to 5 seconds)";
        else action="Send zoom";
        decision.fields.put("action",action);decision.fields.put("pendingCcapi",pendingTarget<0?null:pendingTarget);
        log.record("zoom_decision","decision",decision.fields);
        zoomStatus=!decision.enabled?action:(s.zoomMode.equals("bands")?"Bands "+decision.fields.get("bandZoomX")+"x · scale "+decision.fields.get("multiplier"):"Auto-fit "+decision.fields.get("effectiveFrameWidthMetres")+" m")+" · estimated "+decision.fields.get("estimatedFrameWidthM")+" m · "+(Boolean.TRUE.equals(decision.fields.get("clamped"))?"lens/camera limit · ":"")+
            " · requested "+decision.fields.get("limitedOpticalZoom")+"× (estimated) · "+action;
        if(!action.equals("Send zoom"))return;
        java.util.function.BooleanSupplier maySend=()->!closed&&settings.get()==s&&network.get()==wifi
            &&input.get().canSendFor(gps.aimingSession);
        String cancelledReason="Aiming session, settings, network or GPS changed before zoom command";
        if(!maySend.getAsBoolean()){log.record("camera_zoom_skipped","reason",cancelledReason);return;}
        pendingTarget=decision.target;pendingAt=now;
        String body=new JSONObject().put("value",decision.target).toString();
        log.record("camera_zoom_command","requestedCcapi",decision.target,"decision",decision.fields,"method","POST","url",url,"body",body);
        // POST acknowledgement is not treated as physical arrival; next GET confirms it.
        try{String response=clockRequest(wifi,url,"POST",body,maySend);log.record("camera_zoom_ack","requestedCcapi",decision.target,"response",response,"elapsedMs",SystemClock.elapsedRealtime()-now);}
        catch(CancellationException e){pendingTarget=-1;log.record("camera_zoom_skipped","reason",cancelledReason);}
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
    void requestPreview(Callable<Network> wifiSource, BiConsumer<Bitmap, String> result) {
        if (!preview.compareAndSet(null, result)) {
            result.accept(null, "A preview request is already running.");
            return;
        }
        try {
            loop.execute(() -> readPreview(wifiSource, result));
        } catch (RejectedExecutionException stopped) {
            finishPreview(result, null, "Camera connection closed.");
        }
    }

    private void readPreview(Callable<Network> wifiSource, BiConsumer<Bitmap, String> result) {
        if (closed) return;
        long startedAt = SystemClock.elapsedRealtime();
        Settings requested = settings.get();
        try {
            if (requested.cameraHost.isEmpty()) throw new IOException("Enter the camera IP in Setup.");
            Network wifi = wifiSource.call();
            String address = "http://" + requested.cameraHost + ":" + requested.cameraPort
                    + "/ccapi/ver100/shooting/liveview";
            log.record("camera_preview_request", "url", address);
            byte[] frame = CameraSnapshot.capture((method, suffix, body) ->
                previewRequest(wifi, address + suffix, method, body));
            Settings current = settings.get();
            if (!current.cameraHost.equals(requested.cameraHost) || current.cameraPort != requested.cameraPort
                    || !wifi.equals(wifiSource.call())) {
                throw new IOException("Camera address or Wi-Fi changed. Press Update again.");
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(frame, 0, frame.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || (long) bounds.outWidth * bounds.outHeight > 2_100_000) {
                throw new IOException("Invalid or oversized camera image.");
            }
            Bitmap image = BitmapFactory.decodeByteArray(frame, 0, frame.length);
            if (image == null) throw new IOException("Could not decode camera image.");
            log.record("camera_preview_response", "bytes", frame.length, "width", image.getWidth(),
                    "height", image.getHeight(), "elapsedMs", SystemClock.elapsedRealtime() - startedAt);
            finishPreview(result, image, null);
        } catch (Exception failure) {
            if (!closed) {
                log.error("camera_preview_error", failure, "elapsedMs", SystemClock.elapsedRealtime() - startedAt);
                String detail = failure.getMessage();
                finishPreview(result, null, detail == null ? "Could not load camera image." : detail);
            }
        }
    }

    private byte[] previewRequest(Network wifi, String url, String method, String body) throws IOException {
        HttpURLConnection request = (HttpURLConnection) wifi.openConnection(new URL(url));
        connection = request;
        try {
            if (closed && !CameraSnapshot.STOP.equals(body)) throw new IOException("Camera connection closed.");
            request.setConnectTimeout(PREVIEW_TIMEOUT_MS);
            request.setReadTimeout(PREVIEW_TIMEOUT_MS);
            request.setInstanceFollowRedirects(false);
            request.setUseCaches(false);
            request.setRequestMethod(method);
            request.setRequestProperty("Accept", body == null ? "image/jpeg" : "application/json");
            if (body != null) {
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                request.setDoOutput(true);
                request.setFixedLengthStreamingMode(payload.length);
                request.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = request.getOutputStream()) {
                    out.write(payload);
                }
            }
            int status = request.getResponseCode();
            if (status != 200) throw new IOException("Camera preview HTTP " + status + ".");
            if (body != null) return new byte[0];
            long readStartedAt = SystemClock.elapsedRealtime();
            try (InputStream in = request.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (count > MAX_PREVIEW_BYTES - out.size()
                            || SystemClock.elapsedRealtime() - readStartedAt > PREVIEW_TIMEOUT_MS) {
                        throw new IOException("Camera image exceeded the read limit.");
                    }
                    out.write(buffer, 0, count);
                }
                return out.toByteArray();
            }
        } finally {
            request.disconnect();
            connection = null;
        }
    }

    private void finishPreview(BiConsumer<Bitmap, String> result, Bitmap image, String error) {
        if (preview.compareAndSet(result, null)) result.accept(image, error);
    }

    boolean awaitClosed(long millis)throws InterruptedException{return loop.awaitClosed(millis);}
    synchronized void stopRecording(String reason) {
        if(!closing)loop.execute(()->sendRecordingStop(reason));
    }

    private void sendRecordingStop(String reason) {
        if(stopSent)return;
        Settings setup=settings.get();
        Network wifi=recordingNetwork!=null?recordingNetwork:network.get();
        String base=recordingBase;
        if(base.isEmpty()&&!setup.cameraHost.isEmpty())
            base="http://"+setup.cameraHost+":"+setup.cameraPort+"/ccapi/ver100";
        if(base.isEmpty())return;
        final String url=base;
        try{
            if(wifi==null)throw new IOException("No camera Wi-Fi connection");
            recording.stop((method,path,body)->jsonRequest(wifi,url+path,method,body,()->true,"camera_recording_command"));
            stopSent=true;
            recordingStatus="Recording stopped";
            log.record("camera_recording_stop","reason",reason,"outcome","acknowledged or already stopped");
        }catch(Exception e){
            recordingStatus="Recording Stop unconfirmed";
            log.error("camera_recording_stop_failed",e,"reason",reason);
        }
    }

    synchronized void closeThen(Runnable finished) {
        closeComplete.thenRun(finished);
        if(closing)return;
        closing=true;
        HttpURLConnection active=connection;
        if(active!=null)active.disconnect();
        loop.execute(()->{
            try{sendRecordingStop("Camera session closing");}
            finally{
                closed=true;
                BiConsumer<Bitmap,String> pending=preview.getAndSet(null);
                try{if(pending!=null)pending.accept(null,"Camera connection closed.");}
                finally{closeComplete.complete(null);loop.close();}
            }
        });
    }

    public void close(){closeThen(()->{});}
}
