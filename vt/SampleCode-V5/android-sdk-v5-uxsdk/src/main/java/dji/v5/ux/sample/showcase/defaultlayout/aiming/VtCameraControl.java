package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import dji.sdk.keyvalue.key.*;
import dji.sdk.keyvalue.value.common.*;
import dji.sdk.keyvalue.value.gimbal.*;
import dji.v5.manager.KeyManager;
import dji.v5.common.callback.CommonCallbacks;
import dji.v5.common.error.IDJIError;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/** VT 3.3: foreground-only camera observations and transition-triggered pitch commands.
 * SDK callbacks are serialized on the same worker as navigation. Never commands yaw/roll.
 */
public final class VtCameraControl {
    private final Executor worker;
    private final LongSupplier clock;
    private final FullSessionLog log;
    private final KeyManager sdk=KeyManager.getInstance();
    private final Slot<Boolean> recording=new Slot<>(KeyTools.createKey(CameraKey.KeyIsRecording,ComponentIndexType.LEFT_OR_MAIN));
    private final Slot<Attitude> attitude=new Slot<>(KeyTools.createKey(GimbalKey.KeyGimbalAttitude,ComponentIndexType.LEFT_OR_MAIN));
    private final Slot<GimbalAttitudeRange> range=new Slot<>(KeyTools.createKey(GimbalKey.KeyGimbalAttitudeRange,ComponentIndexType.LEFT_OR_MAIN));
    public final GimbalBandPolicy policy=new GimbalBandPolicy();
    private final android.content.Context context;
    private GimbalBandConfig bands;
    // VT 3.8: A validated Start snapshot replaces in-flight file loading/fallback.
    private VtSessionConfig prepared;
    private String preparedPath;
    public void prepareConfiguration(VtSessionConfig config,String path){prepared=config;preparedPath=path;}
    private String lastDeferred="none";
    private long generation,lastPoll=-1,sessionId=-1,lastLog=-1,commandAt=-1;
    private int attempts;
    private long commandId;
    private double sentTarget=Double.NaN;
    private int sentBand;
    private long sentCommandId;
    private boolean pending,commandNeeded,reachedLogged;
    private String result="none";
    private static final class Slot<T> {
        final DJIKey<T> key; T value; long at=-1,request=-1,ticket;
        Slot(DJIKey<T> key) { this.key=key; }
        boolean fresh(long now) { return value!=null && at>=0 && now>=at && now-at<=1500; }
        void clear() { value=null; at=request=-1; ticket++; }
    }
    public VtCameraControl(android.content.Context context,Executor worker,LongSupplier clock,FullSessionLog log) { this.context=context;this.worker=worker;this.clock=clock;this.log=log; }
    public void clear() {
        generation++; recording.clear();attitude.clear();range.clear();lastPoll=-1;
        policy.reset();sessionId=-1;pending=commandNeeded=false;
    }
    private <T> void read(Slot<T> s,long now) {
        if(s.request>=0 && now-s.request<1500) return;
        s.request=now;long token=++s.ticket,g= generation;
        sdk.getValue(s.key,new CommonCallbacks.CompletionCallbackWithParam<T>() {
            public void onSuccess(T v) { worker.execute(() -> { if(g==generation && token==s.ticket) {s.value=v;s.at=now;s.request=-1;} }); }
            public void onFailure(IDJIError e) { worker.execute(() -> {if(g==generation && token==s.ticket) {s.value=null;s.at=-1;s.request=-1;} }); }
        });
    }
    private void deferred(String reason) {
        if(!reason.equals(lastDeferred)) {
            log.record("gimbal_band_deferred","session",sessionId,"activeBand",policy.band+1,"reason",reason);
            lastDeferred=reason;
        }
    }
    public void poll(long now) {
        if(lastPoll>=0 && now-lastPoll<500) return;lastPoll=now;
        read(recording,now);read(attitude,now);read(range,now);
    }
    public String recordingProblem(long now) { return RecordingGate.problem(recording.value,recording.at,now); }
    public void update(AimingSession session,AimingSession.Inputs in,long now,boolean allowed,ComeToMeSettings settings) {
        if(session.sessionId()<=0) return;
        if(session.sessionId()!=sessionId) {
            sessionId=session.sessionId();policy.reset();pending=commandNeeded=false;attempts=0;commandAt=-1;result="none";lastLog=-1;lastDeferred="none";
            if(prepared==null)throw new IllegalStateException("Missing validated gimbal Start configuration");
            bands=prepared.gimbal;
            log.record("gimbal_band_config","session",sessionId,"configPath",preparedPath,"source","shared_folder",
                "fileContents",prepared.gimbalJson,"effectiveJson",prepared.gimbalJson,"validationError","none","bufferMetres",bands.bufferMetres);
        }
        if(!allowed) { deferred("control_not_allowed"); return; }
        double actual=attitude.fresh(now) ? attitude.value.getPitch() : Double.NaN;
        double distance=in.target==null ? Double.NaN : YawAimingMath.distance(in.lat,in.lon,in.target.lat,in.target.lon);
        long age=in.target==null ? -1 : now-in.target.time;
        boolean gpsFresh=age>=0 && age<=3000;
        // VT 3.5: During retreat/cooldown only, retained surfer GPS may select bands using live aircraft telemetry.
        boolean retainedRetreat=(age>3000 || session.cycleRetainedTarget) && session.retainedRetreatTarget(in,now);
        boolean gpsUsable=gpsFresh || retainedRetreat;
        deferred(!gpsUsable ? "stale_surfer_gps" : !attitude.fresh(now) ? "stale_gimbal_attitude" : !range.fresh(now) ? "stale_gimbal_range" : pending ? "pending_command" : commandAt>=0 && now-commandAt<3000 ? "command_cooldown" : "none");
        if(!pending && (commandAt<0 || now-commandAt>=2000) && range.fresh(now) && range.value.getPitch()!=null) {
            double min=range.value.getPitch().getMin(), max=range.value.getPitch().getMax();
            int oldBand=policy.band;
            boolean needsCommand=policy.update(actual,distance,gpsUsable,bands,min,max);
            if(oldBand!=policy.band) {
                // Record every crossed threshold, including multi-band jumps.
                StringBuilder thresholds=new StringBuilder();
                if(oldBand>=0) for(int i=Math.min(oldBand,policy.band);i<Math.max(oldBand,policy.band);i++) {
                    if(thresholds.length()>0)thresholds.append(",");
                    thresholds.append(bands.upper(i)+(policy.band>oldBand ? bands.bufferMetres : 0));
                }
                commandNeeded=needsCommand;attempts=0;
                log.record("gimbal_band_switch","session",sessionId,"cycleId",session.cycleId,
                    "previousBand",oldBand+1,"activeBand",policy.band+1,"distanceM",distance,"gpsAgeMs",age,
                    "crossedThresholdsM",thresholds.toString(),"reason",policy.reason,"configuredPitchDeg",bands.pitch(policy.band),
                    "targetPitchDeg",policy.target,"actualPitchDeg",actual,"commandNeeded",needsCommand);
            }
            if(needsCommand) {
                commandNeeded=true;attempts=0;result="requested";
            }
        }
        if(commandNeeded && !pending && gpsUsable && attitude.fresh(now) && range.fresh(now)
                && attempts<3 && (commandAt<0 || now-commandAt>=3000)) {
            commandAt=now;attempts++;pending=true;commandNeeded=false;
            final long token=sessionId,g=generation,id=++commandId; final double target=policy.target; sentTarget=target;sentBand=policy.band+1;sentCommandId=id;reachedLogged=false;
            GimbalAngleRotation rotation=new GimbalAngleRotation();
            rotation.setMode(GimbalAngleRotationMode.ABSOLUTE_ANGLE);
            rotation.setPitch(target); rotation.setPitchIgnored(false);
            rotation.setRollIgnored(true);rotation.setYawIgnored(true);
            rotation.setDuration(2.0);rotation.setJointReferenceUsed(false);
            log.record("gimbal_pitch_command","session",token,"cycleId",session.cycleId,"activeBand",policy.band+1,
                "commandId",id,"targetPitchDeg",target,"actualPitchDeg",actual,"bufferMetres",bands.bufferMetres,
                "distanceM",distance,"gpsAgeMs",age,"retainedRetreatTarget",retainedRetreat,"trigger",policy.reason,"attempt",attempts);
            sdk.performAction(KeyTools.createKey(GimbalKey.KeyRotateByAngle,ComponentIndexType.LEFT_OR_MAIN),rotation,
                new CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>() {
                    public void onSuccess(EmptyMsg ignored) { finish("accepted",false); }
                    public void onFailure(IDJIError error) { finish(error==null ? "unknown_error" : error.errorCode(),true); }
                    private void finish(String value,boolean failed) { worker.execute(() -> {
                        log.record("gimbal_pitch_result","session",token,"commandId",id,"targetPitchDeg",target,"result",value);
                        if(g!=generation || token!=sessionId || id!=commandId) return;
                        pending=false;result=value;if(failed) commandNeeded=true;
                    }); }
                });
        }
        if(pending && now-commandAt>5000) {
            pending=false;commandId++;result="callback_timeout";
            if(policy.target==sentTarget) commandNeeded=false;
            log.record("gimbal_pitch_result","session",sessionId,"commandId",sentCommandId,"targetPitchDeg",sentTarget,"result",result);
        }
        if(!reachedLogged && commandAt>=0 && attitude.fresh(now) && now>=commandAt+2000 && Math.abs(actual-sentTarget)<=1) {
            reachedLogged=true;
            log.record("gimbal_pitch_reached","session",sessionId,"commandId",sentCommandId,"activeBand",sentBand,
                "targetPitchDeg",sentTarget,"actualPitchDeg",actual,"elapsedMs",now-commandAt);
        }
        if(lastLog<0 || now-lastLog>=500) {
            lastLog=now;
            log.record("gimbal_pitch_cycle","session",sessionId,"cycleId",session.cycleId,
                "activeBand",policy.band+1,"targetPitchDeg",policy.target,"actualPitchDeg",actual,
                "bufferMetres",bands.bufferMetres,"deferredReason",lastDeferred,
                "distanceM",distance,"gpsAgeMs",age,"retainedRetreatTarget",retainedRetreat,"trigger",policy.reason,"commandResult",result,
                "targetReached",Double.isFinite(actual)&&Double.isFinite(policy.target)&&Math.abs(actual-policy.target)<=1,
                "pitchFresh",attitude.fresh(now),"rangeFresh",range.fresh(now),
                "recording",recording.value,"recordingAgeMs",recording.at<0 ? -1 : now-recording.at);
        }
    }
}
