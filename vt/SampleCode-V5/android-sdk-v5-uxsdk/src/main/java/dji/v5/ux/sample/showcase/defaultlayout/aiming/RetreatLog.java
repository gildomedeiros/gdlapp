package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** VT 3.5: scalar full-log evidence; join camera/angle/command records by session and cycleId. */
public final class RetreatLog {
    private RetreatLog() { }
    public static void record(FullSessionLog log,AimingSession session,long now,String event,String reason,
            long submittedCycle,double submittedForward) {
        RetreatController r=session.retreat;RetreatSettings c=r.settings;
        AimingSession.Inputs in=session.cycleInputs;
        AimingSession.Fix fix=in==null ? null : in.target;
        log.record(event,"session",session.sessionId(),"cycleId",session.cycleId,"reason",reason,
            "enabled",c.enabled,"active",r.active,"minimumDistanceM",c.minimumDistance,
            "durationMs",c.durationMs,"speedMps",c.speed,"speedKmh",c.speed*3.6,"maximumSpeedMps",RetreatSettings.MAX_SPEED,"cooldownMs",c.cooldownMs,
            "startedAtMs",r.startedAt,"periodStartedAtMs",r.periodStartedAt,"period",r.period,
            "elapsedMs",r.startedAt<0 ? 0 : Math.max(0,now-r.startedAt),"remainingMs",r.remaining(now),
            "cooldownRemainingMs",r.cooldownRemaining(now),"startDistanceM",r.startDistance,"distanceM",r.distance,
            "targetLatitude",fix==null ? null : fix.lat,"targetLongitude",fix==null ? null : fix.lon,
            "targetSequence",fix==null ? null : fix.sequence,"gpsAgeMs",fix==null ? null : now-fix.time,
            "retainedTarget",session.cycleRetainedTarget,
            "aircraftLatitude",in==null ? null : in.lat,"aircraftLongitude",in==null ? null : in.lon,
            "headingDeg",in==null ? null : in.heading,"requestedYawRate",session.cycleRequestedYaw,
            "yawPurpose",session.cycleDecision,"requestedForwardMps",session.cycleRequestedForward,
            "submittedForwardMps",submittedCycle==session.cycleId ? submittedForward : null,
            "state",session.state().name(),"riding",session.movement.riding);
    }
}
