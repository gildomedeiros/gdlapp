package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** CAM3 v2.7: Pure-Java per-cycle log adapter, exercised with real session and JSONL writer tests. */
public final class AimingCycleLog {
    private AimingCycleLog() { }
    public static void record(FullSessionLog fullLog,AimingSession session,AimingSession.Inputs observed,
                              long at,boolean nearbyEnabled,boolean usePhone,long submittedCycle,double lastCommandRate) {
        AimingSession.Inputs in=session.cycleInputs!=null ? session.cycleInputs : observed;
        AimingSession.Fix f=in.target;
        double distance=f==null ? Double.NaN : YawAimingMath.distance(in.lat,in.lon,f.lat,f.lon);
        double bearing=f==null || distance==0 ? Double.NaN : YawAimingMath.bearingToTarget(in.lat,in.lon,f.lat,f.lon);
        DominantDirectionTracker t=session.directionTracker;
        NearbyDirectionLock lock=session.nearbyLock;
            fullLog.record("aiming_cycle","cycleId",session.cycleId,"session",session.sessionId(),
                    "cycleAtMs",session.cycleAt,"state",session.state().name(),"reason",session.reason(),
                    "inputProblem",in.validate(at),"source",usePhone ? "phone" : "lora_wifi",
                    "targetLatitude",f==null ? null : f.lat,"targetLongitude",f==null ? null : f.lon,
                    "targetFixMs",f==null ? null : f.time,"targetSequence",f==null || f.sequence<0 ? null : f.sequence,
                    "targetAgeMs",f==null ? null : at-f.time,"aircraftLatitude",in.lat,"aircraftLongitude",in.lon,
                    "aircraftHeadingDeg",in.heading,"aircraftFixMs",in.aircraftTime,"aircraftAgeMs",at-in.aircraftTime,
                    "distanceM",distance,"subjectBearingDeg",bearing,"relativeBearingDeg",YawAimingMath.shortestHeadingError(bearing,in.heading),
                    "newVoteFix",t.newFix,"voteFixMs",observed.target==null ? null : observed.target.time,
                    "newVote",t.lastVote,"bearingChangeDeg",t.bearingChange,"rightVotes",t.rightVotes(),"leftVotes",t.leftVotes(),
                    "dominantDirection",DominantDirectionTracker.name(t.calculateDominantDirection()),"defaultRight",t.defaultRight(),
                    "nearbyEnabled",nearbyEnabled,"under60m",Double.isFinite(distance)&&distance<60,
                    "lockedDirection",DominantDirectionTracker.name(lock.direction()),"lockAgeMs",lock.age(at),
                    "remainingAngleDeg",session.cycleAngle,"multiplier",session.cycleMultiplier,
                    "decision",session.cycleDecision,"requestedYawRate",session.cycleRequestedYaw,
                    "submitted",submittedCycle==session.cycleId,"submittedYawRate",submittedCycle==session.cycleId ? lastCommandRate : null);
    }
}
