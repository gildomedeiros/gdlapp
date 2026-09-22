package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** Structured planner evidence, joined to aiming_cycle by session and cycleId. */
public final class MovementCycleLog {
    private MovementCycleLog() { }
    public static void record(FullSessionLog log,AimingSession session,long now,long submittedCycle,double submittedForward) {
        ComeToMeController m=session.movement;
        ComeToMeSettings c=m.settings();
        log.record("movement_cycle","session",session.sessionId(),"cycleId",session.cycleId,
                "maxMovementSpeedMps",ComeToMeSettings.MAX_SPEED,"movementAccelerationMps2",ComeToMeSettings.ACCELERATION,
                "enabled",c.enabled,"filmingDistanceM",c.filmingDistance,"lineupWidthM",c.lineupWidth,
                "rideStartKmh",c.rideStartKmh,"rideEndKmh",c.rideEndKmh,"rideEndMs",c.rideEndMs,"noRideTimeoutMs",c.inactivityMs,
                "phase",m.phase.name(),"reason",m.reason,"plannerEvent",m.event,"returnReason",m.returnReason,
                "paused",session.state()==AimingSession.State.PAUSED,"centralGeneration",m.centralGeneration,
                "centralLatitude",m.centralLat,"centralLongitude",m.centralLon,
                "bandLatitude",m.bandLat,"bandLongitude",m.bandLon,"bandBearingDeg",m.bandBearing,
                "sidewaysM",m.sideways,"insideBand",m.insideBand,"qualifiedMs",m.qualifiedMs,
                "surferDistanceM",m.distance,"centralDistanceM",m.centralDistance,
                "speedKmh",m.speedKmh,"riding",m.riding,
                "confirmedRide",m.ride.confirmed,"confirmationSpeedKmh",m.ride.confirmationSpeed,
                "fastWindowMs",RideDetector.FAST_MS,"confirmationWindowMs",RideDetector.CONFIRM_MS,
                "fastSpanMs",m.ride.fastSpanMs,"confirmationSpanMs",m.ride.confirmationSpanMs,
                "rideEvent",m.ride.event,"speedEvidence",m.ride.evidence,"newRidePacket",m.ride.newPacket,
                "repeatedCoordinates",m.ride.repeatedCoordinates,"jumpSpeedKmh",m.ride.jumpSpeed,
                "jumpSpanMs",m.ride.jumpSpanMs,"jumpLimitKmh",RideDetector.JUMP_LIMIT_KMH,
                "slowResetReason",m.ride.slowResetReason,"noRideTimerEvent",m.noRideTimerEvent,"rideEndElapsedMs",m.slowMs,
                "inactivityElapsedMs",m.inactiveMs,"attemptElapsedMs",m.attemptElapsedMs,
                "headingErrorDeg",m.headingError,"returnHeadingDeg",m.returnHeading,
                "returnStartLatitude",m.returnStartLat,"returnStartLongitude",m.returnStartLon,
                "returnBearingDeg",m.returnBearing,"returnPlannedTravelM",m.returnDistance,
                "backwardProgressM",m.returnProgress,"returnAligned",m.returnAligned,
                "returnCompletionCentralDistanceM",m.returnCompletionCentralDistance,
                "approachStartLatitude",m.approachStartLat,"approachStartLongitude",m.approachStartLon,
                "approachTargetLatitude",m.approachTargetLat,"approachTargetLongitude",m.approachTargetLon,
                "approachHeadingDeg",m.approachHeading,"plannedTravelM",m.approachDistance,
                "forwardProgressM",m.approachProgress,"approachAligned",m.approachAligned,
                "noRideTimerActive",m.noRideTimerActive(),"speedJumpRejected",m.speedJumpRejected,
                "rejectedSpeedJumps",m.rejectedSpeedJumps,
                "yawPurpose",m.returning() ? "return" : m.approaching() ? "approach" : "surfer",
                "requestedForwardMps",session.cycleRequestedForward,
                "submittedForwardMps",submittedCycle==session.cycleId ? submittedForward : null);
    }
}
