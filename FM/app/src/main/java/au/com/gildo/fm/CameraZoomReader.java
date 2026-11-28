package au.com.gildo.fm;
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
final class CameraZoomReader {
    private final CameraPollLoop loop;
    private final Supplier<CameraSettings> settings;
    private final Supplier<Network> network;
    private final CameraLog log;
    private final Supplier<ZoomPlan.Input> input;
    private final ZoomPlan planner=new ZoomPlan();
    private final java.util.concurrent.atomic.AtomicLong zoomGeneration=new java.util.concurrent.atomic.AtomicLong();
    private final CameraRecording recording = new CameraRecording();
    final CameraFlags flags = new CameraFlags();
    // Written and read only on the serial Canon worker during a poll.
    private boolean currentEndpointReplied;
    private volatile String recordingStatus = "Recording waiting for Live Tracking";
    private volatile ZoomSample sample;
    private volatile String zoomStatus="Zoom off; leaving zoom unchanged";
    private String zoomConnection="";
    private int pendingTarget=-1;private long pendingAt;
    private long pendingSession=-1;
    private static final class ZoomSample {
        final int value;final long at;final String endpoint;
        ZoomSample(int value,long at,String endpoint){this.value=value;this.at=at;this.endpoint=endpoint;}
    }
    private volatile boolean closing,closed;
    private final Runnable changed;
    private volatile String display="Canon waiting for status";
    private Network recordingNetwork;
    private String recordingBase="";
    private boolean stopSent, recordingMayBeActive;
    private final AtomicReference<BiConsumer<Bitmap, String>> preview = new AtomicReference<>();
    private static final int PREVIEW_TIMEOUT_MS = 1500;
    private static final int MAX_PREVIEW_BYTES = 2 * 1024 * 1024;
    private volatile String status="Camera disabled — enter its IP in Settings",value="";
    private volatile long receivedAt;
    private String endpoint="",pollBase="",clockConnection="";
    private volatile String clockStatus="Clock not synchronised yet";
    private long clockAttempt;
    private boolean clockDone;

    CameraZoomReader(Supplier<CameraSettings> settings,Supplier<Network> network,Supplier<ZoomPlan.Input> input,CameraLog log,Runnable changed){
        this.changed=changed;
        this.settings=settings;this.network=network;this.input=input;this.log=log;
        loop=new CameraPollLoop(()->{
            try { read(); }
            catch(RuntimeException failure) { log.error("camera_poll_error",failure); }
            finally { refreshDisplay(); }
        },2000);
    }
    record MapZoom(double optical,boolean fresh) {}
    MapZoom cachedMapZoom(CameraSettings current) {
        ZoomSample last=sample;
        if(last==null || current==null || current.manual || current.cameraHost.isEmpty()) return null;
        String expected="http://"+current.cameraHost+":"+current.cameraPort+"/ccapi/ver100/shooting/control/zoom";
        if(!last.endpoint.equals(expected)) return null;
        return new MapZoom(current.zoomMapping.optical(last.value),SystemClock.elapsedRealtime()-last.at<=5000);
    }
    String status() { return display; }

    private void refreshDisplay() {
        display=buildStatus();
        changed.run();
    }

    private String buildStatus() {
        ZoomSample last=sample;
        CameraSettings s=settings.get();
        if(s.manual)return "Manual camera";
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
            ? "Automatic zoom waiting for Live Tracking; reading zoom" : zoomStatus;
        return status+"\n"+controlStatus+"\n"+clockStatus+readback+"\n"+width+"\n"+recordingStatus;
    }
    private void read(){
        if(closing || closed || settings.get().manual)return;
        CameraSettings s=settings.get();String url=s.cameraHost.isEmpty()?"":"http://"+s.cameraHost+":"+s.cameraPort+"/ccapi/ver100/shooting/control/zoom";
        if(!url.equals(endpoint)){flags.endpointChanged(recordingMayBeActive,input.get().aimingSession>=0);endpoint=url;value="";receivedAt=0;sample=null;planner.reset();pendingTarget=-1;recording.reset();}
        if(url.isEmpty())recordingStatus="Recording unavailable · camera disabled";
        if(url.isEmpty()){flags.noNetwork(true,input.get().aimingSession>=0);status="Camera disabled — enter its IP in Settings";zoomStatus="No zoom commands: camera disabled";return;}
        Network wifi=network.get();
        if(wifi==null){flags.noNetwork(false,input.get().aimingSession>=0);recordingStatus="Recording status unavailable · waiting for Wi-Fi";sample=null;planner.reset();pendingTarget=-1;clockDone=false;clockConnection="";status="Camera waiting for Wi-Fi connection; aiming is independent";return;}
        String connectionKey=url+"@"+wifi.toString();
        if(!connectionKey.equals(zoomConnection)){flags.endpointChanged(recordingMayBeActive,input.get().aimingSession>=0);zoomConnection=connectionKey;planner.reset();pendingTarget=-1;sample=null;}
        pollBase="http://"+s.cameraHost+":"+s.cameraPort+"/ccapi/ver100";
        currentEndpointReplied=false;
        readRecording(wifi,s);
        if(closing || closed || settings.get().manual)return;
        long began=SystemClock.elapsedRealtime();HttpURLConnection c=null;
        try{
            log.record("camera_zoom_request","url",url,"method","GET");status="Reading camera zoom";
            c=(HttpURLConnection)wifi.openConnection(new URL(url));
            c.setRequestMethod("GET");c.setConnectTimeout(1500);c.setReadTimeout(1500);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
            int code=c.getResponseCode();currentEndpointReplied |= code>0;if(code!=200)throw new IOException("Camera HTTP "+code);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){
                byte[] b=new byte[1024];int n;
                while((n=in.read(b))!=-1){bytes.write(b,0,n);if(bytes.size()>8192||SystemClock.elapsedRealtime()-began>4000)throw new IOException("Camera response exceeded read limit");}
            }
            String body=bytes.toString(StandardCharsets.UTF_8.name());JSONObject result=new JSONObject(body);
            int zoom=result.getInt("value");JSONObject ability=result.optJSONObject("ability");
            String text=String.valueOf(zoom)+(ability==null?"":" (range "+ability.getInt("min")+"–"+ability.getInt("max")+", step "+ability.getInt("step")+")");
            // Discard an old endpoint's response if settings changed while it was in flight.
            if(closed||settings.get()!=s||!wifi.equals(network.get()))return;
            flags.zoom=ConnectionFlag.good("Readback OK");
            value=text;receivedAt=SystemClock.elapsedRealtime();sample=new ZoomSample(zoom,receivedAt,url);status="Camera zoom available";
            log.record("camera_zoom_response","url",url,"elapsedMs",receivedAt-began,"body",body,"zoom",zoom,"opticalZoomX",s.zoomMapping.optical(zoom));
            if(pendingTarget>=0&&zoom==pendingTarget){log.record("camera_zoom_confirmed","requestedCcapi",pendingTarget,"readbackCcapi",zoom,"elapsedMs",receivedAt-pendingAt);pendingTarget=-1;}
            if(ability!=null){
                ZoomPlan.Input gps=input.get();
                controlZoom(wifi,s,url,zoom,ability,gps,()->input.get().canSendFor(gps.aimingSession),"Live Tracking");
            }
            else {zoomStatus="Zoom waiting for camera limits";log.record("camera_zoom_skipped","reason","Camera ability missing");}
            String clockKey=url+"@"+wifi.toString();
            if(!clockKey.equals(clockConnection)){clockConnection=clockKey;clockDone=false;clockAttempt=0;clockStatus="Clock awaiting synchronisation";}
            if(!closing&&!clockDone&&(clockAttempt==0||SystemClock.elapsedRealtime()-clockAttempt>=60000))syncClock(wifi,s);

        }catch(Exception e){
            if(!closed){flags.zoom=ConnectionFlag.failed("Request failed");status="Camera unavailable — retrying: "+e.getMessage();log.error("camera_zoom_error",e,"url",url,"elapsedMs",SystemClock.elapsedRealtime()-began,"settings",s.values,"zoomControlStatus",zoomStatus);}
        }finally{
            if(c!=null)c.disconnect();
            flags.link=currentEndpointReplied?ConnectionFlag.good("Reachable"):ConnectionFlag.failed("Unreachable");
        }
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
        if (closed || settings.get().manual) throw new IOException("Camera control unavailable");
        long began=SystemClock.elapsedRealtime();
        HttpURLConnection c=(HttpURLConnection)wifi.openConnection(new URL(url));
        try {
            if(closed || settings.get().manual)throw new IOException("Camera control unavailable");
            c.setRequestMethod(method);c.setConnectTimeout(1500);c.setReadTimeout(1500);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
            if(body!=null){
                if(settings.get().manual || !maySend.getAsBoolean())throw new CancellationException();
                c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
                try(OutputStream out=c.getOutputStream()){
                    if(settings.get().manual || !maySend.getAsBoolean())throw new CancellationException();
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code=c.getResponseCode();
            if (code>0 && url.startsWith(pollBase+"/"))
                currentEndpointReplied=true;
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
        }finally{c.disconnect();}
    }
    private void readRecording(Network wifi, CameraSettings settingsAtPoll) {
        String base = pollBase;
        if (recordingMayBeActive && !base.equals(recordingBase)) {
            sendRecordingStop("Canon endpoint changed");
            if(recordingMayBeActive) {
                flags.events=ConnectionFlag.pending("Waiting for old camera Stop");
                return;
            }
        }
        if(!base.equals(recordingBase)) {
            stopSent=false;
            recording.reset();
        }
        recordingBase=base;
        recordingNetwork=wifi;
        long session = input.get().aimingSession;
        if(session<0 && recordingMayBeActive) sendRecordingStop("Live Tracking inactive");
        try {
            String events = jsonRequest(wifi, base + "/event/polling", "GET", null, () -> true, null);
            flags.events=ConnectionFlag.good("Receiving");
            if (!events.trim().isEmpty()) {
                String action = recording.observe(events, SystemClock.elapsedRealtime());
                flags.recordingEvent(action,session>=0);
                if ("start".equals(action))stopSent=false;
                if (!action.isEmpty()) log.record("camera_recording_event", "action", action, "aimingSession", session);
            }
        } catch (Exception failure) {
            flags.eventFailure();
            if (session >= 0 && !closed) log.error("camera_recording_event_error", failure);
        }

        if (closed) return;
        session = input.get().aimingSession;
        final long aimingSession = session;
        try {
            recording.update(aimingSession, settingsAtPoll.maxRecordingMinutes, SystemClock::elapsedRealtime,
                    (method, path, body) -> {
                        try {
                            if(body.equals("{\"action\":\"start\"}") && canRecord(wifi,settingsAtPoll,aimingSession)) {
                                stopSent=false;
                                recordingMayBeActive=true;
                            }
                            String response=jsonRequest(wifi, base + path, method, body,
                                    () -> canRecord(wifi, settingsAtPoll, aimingSession), "camera_recording_command");
                            if (body.equals("{\"action\":\"start\"}")) flags.startAccepted();
                            return response;
                        } catch (CancellationException cancelled) {
                            throw new IOException("Aiming or camera settings changed", cancelled);
                        }
                    });
        } catch (IOException failure) {
            if (!canRecord(wifi, settingsAtPoll, aimingSession)) return;
            flags.recording=ConnectionFlag.failed("Start unconfirmed");
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
            recordingStatus = "Recording waiting for Live Tracking";
        } else if (!recordingStatus.startsWith("Recording command unconfirmed")) {
            recordingStatus = "Waiting for the camera to start recording";
        }
    }

    private boolean canRecord(Network wifi, CameraSettings requested, long aimingSession) {
        CameraSettings current = settings.get();
        return !closing && !closed && !current.manual && aimingSession >= 0 && input.get().aimingSession == aimingSession
                && wifi.equals(network.get()) && current.cameraHost.equals(requested.cameraHost)
                && current.cameraPort == requested.cameraPort
                && current.maxRecordingMinutes == requested.maxRecordingMinutes;
    }

    void cancelOneShotZoom() { zoomGeneration.incrementAndGet(); }

    void zoomOnce(String action,long press,double distance) {
        long generation=zoomGeneration.incrementAndGet();
        CameraSettings requested=settings.get();
        if(closing || requested.manual || !requested.autoZoom || requested.cameraHost.isEmpty()) return;
        java.util.function.BooleanSupplier active=()->!closing && zoomGeneration.get()==generation
            && settings.get()==requested && input.get().aimingSession<0;
        execute(()->{
            if(!active.getAsBoolean()) return;
            try {
                Network wifi=network.get();
                if(wifi==null) throw new IOException("Camera Wi-Fi unavailable");
                String url="http://"+requested.cameraHost+":"+requested.cameraPort+"/ccapi/ver100/shooting/control/zoom";
                JSONObject zoom=new JSONObject(jsonRequest(wifi,url,"GET",null,active,"camera_one_shot_read"));
                if(active.getAsBoolean()) controlZoom(wifi,requested,url,zoom.getInt("value"),zoom.getJSONObject("ability"),
                    new ZoomPlan.Input(distance,0,true,press),active,action);
            } catch(Exception e) { log.error("camera_one_shot_zoom_failed",e,"action",action,"pressMs",press); }
            finally { refreshDisplay(); }
        });
    }

    private void controlZoom(Network wifi,CameraSettings s,String url,int current,JSONObject ability,ZoomPlan.Input gps,
                             java.util.function.BooleanSupplier active,String source)throws Exception {
        long now=SystemClock.elapsedRealtime();
        ZoomPlan.Decision decision=planner.decide(s,gps,now,ability.getInt("min"),ability.getInt("max"),ability.getInt("step"));
        decision.fields.put("source",source);
        decision.fields.put("currentCcapi",current);decision.fields.put("currentCcapiAgeMs",now-receivedAt);
        decision.fields.put("estimatedCurrentOpticalZoom",s.zoomMapping.optical(current));
        decision.fields.put("estimatedCurrentFrameWidthM",Double.isFinite(gps.distance)?gps.distance*s.wideFrameFactor/s.zoomMapping.optical(current):null);
        String action;
        if(!decision.enabled)action=String.valueOf(decision.fields.get("reason"));
        else if(current==decision.target)action="At requested zoom position";
        else if(ZoomPlan.withinWidth(s,gps,decision,current))action="Within 5% width tolerance";
        else if(pendingTarget>=0&&pendingSession==gps.aimingSession&&now-pendingAt<5000)action="Waiting for previous zoom readback (up to 5 seconds)";
        else action="Send zoom";
        decision.fields.put("action",action);decision.fields.put("pendingCcapi",pendingTarget<0?null:pendingTarget);
        log.record("zoom_decision","decision",decision.fields);
        zoomStatus=!decision.enabled?action:(s.zoomMode.equals("bands")?"Bands "+decision.fields.get("bandZoomX")+"x · scale "+decision.fields.get("multiplier"):"Auto-fit "+decision.fields.get("effectiveFrameWidthMetres")+" m")+" · estimated "+decision.fields.get("estimatedFrameWidthM")+" m · "+(Boolean.TRUE.equals(decision.fields.get("clamped"))?"lens/camera limit · ":"")+
            " · requested "+decision.fields.get("limitedOpticalZoom")+"× (estimated) · "+action;
        if(!action.equals("Send zoom"))return;
        java.util.function.BooleanSupplier maySend=()->!closed&&settings.get()==s&&wifi.equals(network.get())
            &&active.getAsBoolean();
        String cancelledReason="Aiming session, settings, network or GPS changed before zoom command";
        if(!maySend.getAsBoolean()){log.record("camera_zoom_skipped","reason",cancelledReason);return;}
        pendingTarget=decision.target;pendingAt=now;pendingSession=gps.aimingSession;
        String body=new JSONObject().put("value",decision.target).toString();
        log.record("camera_zoom_command","requestedCcapi",decision.target,"decision",decision.fields,"method","POST","url",url,"body",body);
        // POST acknowledgement is not treated as physical arrival; next GET confirms it.
        try{String response=clockRequest(wifi,url,"POST",body,maySend);log.record("camera_zoom_ack","requestedCcapi",decision.target,"response",response,"elapsedMs",SystemClock.elapsedRealtime()-now);}
        catch(CancellationException e){pendingTarget=-1;log.record("camera_zoom_skipped","reason",cancelledReason);}
        catch(Exception e){log.error("camera_zoom_command_error",e,"requestedCcapi",decision.target,"elapsedMs",SystemClock.elapsedRealtime()-now);throw e;}
    }
    private void syncClock(Network wifi,CameraSettings s){
        clockAttempt=SystemClock.elapsedRealtime();
        String url="http://"+s.cameraHost+":"+s.cameraPort+"/ccapi/ver100/functions/datetime";
        try {
            JSONObject before=new JSONObject(clockRequest(wifi,url,"GET",null));
            before.getString("datetime");before.getBoolean("dst");
            if(closed||settings.get()!=s||!wifi.equals(network.get()))return;
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
        if (closed) { finishPreview(result,null,"Camera worker closed"); return; }
        long startedAt = SystemClock.elapsedRealtime();
        CameraSettings requested = settings.get();
        try {
            if (requested.manual) throw new IOException("Manual camera");
            if (requested.cameraHost.isEmpty()) throw new IOException("Enter the camera IP in Settings.");
            Network wifi = wifiSource.call();
            String address = "http://" + requested.cameraHost + ":" + requested.cameraPort
                    + "/ccapi/ver100/shooting/liveview";
            log.record("camera_preview_request", "url", address);
            byte[] frame = CameraSnapshot.capture((method, suffix, body) ->
                previewRequest(wifi, address + suffix, method, body));
            CameraSettings current = settings.get();
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
        if(settings.get().manual) throw new IOException("Manual camera");
        HttpURLConnection request = (HttpURLConnection) wifi.openConnection(new URL(url));
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
            
        }
    }

    private void finishPreview(BiConsumer<Bitmap, String> result, Bitmap image, String error) {
        if (preview.compareAndSet(result, null)) result.accept(image, error);
    }

    synchronized void execute(Runnable work) {
        if(!closing) loop.execute(work);
    }

    synchronized void startRecording(long session) {
        cancelOneShotZoom();
        if(settings.get().manual)return;
        if(closing) return;
        flags.recording=settings.get().cameraHost.isEmpty()?ConnectionFlag.failed("Camera disabled"):ConnectionFlag.pending("Start requested");
        changed.run();
        loop.execute(()->{
            if(settings.get().manual || settings.get().cameraHost.isEmpty()) return;
            log.record("camera_tracking_requested","trackingPressMs",session);
            if(input.get().aimingSession!=session) return;
            try { read(); }
            finally { refreshDisplay(); }
        });
    }

    synchronized void stopRecording(long session) {
        if(settings.get().manual || closing) return;
        if(!settings.get().cameraHost.isEmpty()) flags.recording=ConnectionFlag.pending("Checking Stop");
        changed.run();
        loop.execute(()->{
            try {
                if(!settings.get().cameraHost.isEmpty()||recordingMayBeActive)
                    log.record("camera_tracking_ended","trackingPressMs",session);
                if(!recordingMayBeActive) flags.recording=recording.startedAt()<0?ConnectionFlag.idle("Idle"):ConnectionFlag.good("Last reported recording");
                sendRecordingStop("Live Tracking ended");
            }
            finally { refreshDisplay(); }
        });
    }

    private void sendRecordingStop(String reason) {
        if(settings.get().manual || stopSent || recordingBase.isEmpty() || !recordingMayBeActive)return;
        Network wifi=recordingNetwork!=null?recordingNetwork:network.get();
        String base=recordingBase;
        if(base.isEmpty())return;
        final String url=base;
        try{
            if(wifi==null)throw new IOException("No camera Wi-Fi connection");
            recording.stop((method,path,body)->jsonRequest(wifi,url+path,method,body,()->true,"camera_recording_command"));
            stopSent=true;
            recordingMayBeActive=false;
            flags.recording=ConnectionFlag.idle("Stopped");
            recordingStatus="Recording stopped";
            log.record("camera_recording_stop","reason",reason,"outcome","acknowledged or already stopped");
        }catch(Exception e){
            flags.stopFailed(java.net.URI.create(base).getAuthority());
            recordingStatus="Recording Stop unconfirmed";
            log.error("camera_recording_stop_failed",e,"reason",reason);
        }
    }

    void close() { close(()->{}); }

    synchronized void close(Runnable finished) {
        if(closing)return;
        closing=true;
        loop.execute(()->{
            try{sendRecordingStop("Camera session closing");}
            finally{
                closed=true;
                BiConsumer<Bitmap,String> pending=preview.getAndSet(null);
                try{if(pending!=null)pending.accept(null,"Camera connection closed.");}
                finally{loop.close(); finished.run();}
            }
        });
    }

}
