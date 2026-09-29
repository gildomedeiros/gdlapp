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
    public final GimbalPitchPolicy policy=new GimbalPitchPolicy();
    private long generation,lastPoll=-1,sessionId=-1,lastLog=-1,commandAt=-1;
    private int attempts;
    private long commandId;
    private double sentTarget=Double.NaN;
    private boolean pending,commandNeeded;
    private String result="none";
    private static final class Slot<T> {
        final DJIKey<T> key; T value; long at=-1,request=-1,ticket;
        Slot(DJIKey<T> key) { this.key=key; }
        boolean fresh(long now) { return value!=null && at>=0 && now>=at && now-at<=1500; }
        void clear() { value=null; at=request=-1; ticket++; }
    }
    public VtCameraControl(Executor worker,LongSupplier clock,FullSessionLog log) { this.worker=worker;this.clock=clock;this.log=log; }
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
    public void poll(long now) {
        if(lastPoll>=0 && now-lastPoll<500) return;lastPoll=now;
        read(recording,now);read(attitude,now);read(range,now);
    }
    public String recordingProblem(long now) { return RecordingGate.problem(recording.value,recording.at,now); }
    public void update(AimingSession session,AimingSession.Inputs in,long now,boolean allowed,ComeToMeSettings settings) {
        if(session.sessionId()!=sessionId) {
            sessionId=session.sessionId();policy.reset();pending=commandNeeded=false;attempts=0;commandAt=-1;result="none";
        }
        if(!allowed) return;
        double actual=attitude.fresh(now) ? attitude.value.getPitch() : Double.NaN;
        double distance=in.target==null ? Double.NaN : YawAimingMath.distance(in.lat,in.lon,in.target.lat,in.target.lon);
        long age=in.target==null ? -1 : now-in.target.time;
        boolean gpsFresh=age>=0 && age<=3000;
        if(range.fresh(now) && range.value.getPitch()!=null) {
            double min=range.value.getPitch().getMin(), max=range.value.getPitch().getMax();
            if(policy.update(actual,distance,gpsFresh,settings.closeRangePitchDeg,min,max)) {
                commandNeeded=true;attempts=0;result="requested";
            }
        }
        if(commandNeeded && !pending && gpsFresh && attitude.fresh(now) && range.fresh(now)
                && attempts<3 && (commandAt<0 || now-commandAt>=3000)) {
            commandAt=now;attempts++;pending=true;commandNeeded=false;
            final long token=sessionId,g=generation,id=++commandId; final double target=policy.target; sentTarget=target;
            GimbalAngleRotation rotation=new GimbalAngleRotation();
            rotation.setMode(GimbalAngleRotationMode.ABSOLUTE_ANGLE);
            rotation.setPitch(target); rotation.setPitchIgnored(false);
            rotation.setRollIgnored(true);rotation.setYawIgnored(true);
            rotation.setDuration(2.0);rotation.setJointReferenceUsed(false);
            log.record("gimbal_pitch_command","session",token,"cycleId",session.cycleId,"originalPitchDeg",policy.original,
                "targetPitchDeg",target,"actualPitchDeg",actual,"closeRangePitchDeg",settings.closeRangePitchDeg,
                "distanceM",distance,"gpsAgeMs",age,"trigger",policy.reason,"attempt",attempts);
            sdk.performAction(KeyTools.createKey(GimbalKey.KeyRotateByAngle,ComponentIndexType.LEFT_OR_MAIN),rotation,
                new CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>() {
                    public void onSuccess(EmptyMsg ignored) { finish("accepted",false); }
                    public void onFailure(IDJIError error) { finish(error==null ? "unknown_error" : error.errorCode(),true); }
                    private void finish(String value,boolean failed) { worker.execute(() -> {
                        log.record("gimbal_pitch_result","session",token,"targetPitchDeg",target,"result",value);
                        if(g!=generation || token!=sessionId || id!=commandId) return;
                        pending=false;result=value;if(failed) commandNeeded=true;
                    }); }
                });
        }
        if(pending && now-commandAt>5000) {
            pending=false;commandId++;result="callback_timeout";
            if(policy.target==sentTarget) commandNeeded=false;
            log.record("gimbal_pitch_result","session",sessionId,"targetPitchDeg",sentTarget,"result",result);
        }
        if(lastLog<0 || now-lastLog>=500) {
            lastLog=now;
            log.record("gimbal_pitch_cycle","session",sessionId,"cycleId",session.cycleId,
                "originalPitchDeg",policy.original,"targetPitchDeg",policy.target,"actualPitchDeg",actual,
                "closeRangePitchDeg",settings.closeRangePitchDeg,"closeRange",policy.close,
                "distanceM",distance,"gpsAgeMs",age,"trigger",policy.reason,"commandResult",result,
                "targetReached",Double.isFinite(actual)&&Double.isFinite(policy.target)&&Math.abs(actual-policy.target)<=1,
                "pitchFresh",attitude.fresh(now),"rangeFresh",range.fresh(now),
                "recording",recording.value,"recordingAgeMs",recording.at<0 ? -1 : now-recording.at);
        }
    }
}
