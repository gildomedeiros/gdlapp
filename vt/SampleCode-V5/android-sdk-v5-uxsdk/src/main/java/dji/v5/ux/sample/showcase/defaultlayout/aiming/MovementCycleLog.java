package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** Structured planner evidence, joined to aiming_cycle by session and cycleId. */
public final class MovementCycleLog {
    private MovementCycleLog() { }
    public static void record(FullSessionLog log,AimingSession session,long now,long submittedCycle,double submittedForward) {
        ComeToMeController m=session.movement;
        ComeToMeSettings c=m.settings();
        log.record("movement_cycle","session",session.sessionId(),"cycleId",session.cycleId,
                // VT 3.5: Distinguish independent retreat translation from saved navigation.
                "translationPurpose",session.retreat.active ? "retreat" : m.returning() ? "return" : m.approaching() ? "approach" : "none",
                "retreatActive",session.retreat.active,"retreatCooldownRemainingMs",session.retreat.cooldownRemaining(now),
                "maxMovementSpeedMps",c.maxMovementSpeed,"maxMovementSpeedKmh",c.maxMovementSpeed*3.6,"movementAccelerationMps2",null,
                // VT 3.6: Do not report the retired software ramp as an active setting.
                "movementAccelerationRampEnabled",false,"movementSpeedPolicy","distance_limited_immediate",
                "firstApproachUsesSameMargin",true,
                // VT 3.2: Separate cruise speed from the preserved arrival slope.
                "movementSlowdownDistanceM",c.maxMovementSpeed/ComeToMeSettings.ARRIVAL_SPEED_PER_METRE,
                "arrivalSpeedPerMetre",ComeToMeSettings.ARRIVAL_SPEED_PER_METRE,
                // VT 3.1: Preserve qualification and margin evidence on every cycle.
                "qualificationWaitEnabled",ComeToMeSettings.QUALIFY_MS>0,"rideEndTriggersReturn",false,
                "qualificationRequiredMs",ComeToMeSettings.QUALIFY_MS,"qualificationStatus",m.qualificationStatus,
                "qualificationEvent",m.qualificationEvent,"gpsMovementPaused",m.gpsPaused,
                "waitingForFreshApproachFix",m.qualificationStatus.equals("qualified_waiting_fresh_gps"),
                "savedNavigationWithOldGps",session.cycleRetainedTarget && (m.approaching() || m.returning()),
                "maxExcursionM",ComeToMeSettings.MAX_EXCURSION,
                // VT 3.2: Signed residuals show early arrival versus overshoot; reasons identify the transition.
                "completionToleranceM",ComeToMeSettings.COMPLETION_TOLERANCE,
                "excursionStopDistanceM",ComeToMeSettings.EXCURSION_STOP,
                "approachRemainingM",m.approachDistance-m.approachProgress,
                "returnRemainingM",m.returnDistance-m.returnProgress,
                "excursionRemainingM",ComeToMeSettings.EXCURSION_STOP-m.centralDistance,
                "reapproachMarginM",c.reapproachMargin,"hasFilmed",m.hasFilmed,
                "approachStartThresholdM",m.approachStartThreshold(),
                "enabled",c.enabled,"filmingDistanceM",c.filmingDistance,"lineupWidthM",c.lineupWidth,
                "rideStartKmh",c.rideStartKmh,"rideDurationMs",c.rideDurationMs,
                "rideStartedAtMs",m.ride.startedAt,"rideExpiresAtMs",m.ride.expiresAt,
                "rideRemainingMs",m.ride.remainingMs(now),"ridePolicy","fixed_duration","noRideTimeoutMs",c.inactivityMs,
                "phase",m.phase.name(),"reason",m.reason,"plannerEvent",m.event,"returnReason",m.returnReason,
                "paused",session.state()==AimingSession.State.PAUSED,"centralGeneration",m.centralGeneration,
                "centralLatitude",m.centralLat,"centralLongitude",m.centralLon,
                "bandLatitude",m.bandLat,"bandLongitude",m.bandLon,"bandBearingDeg",m.bandBearing,
                "sidewaysM",m.sideways,"insideBand",m.insideBand,"qualifiedMs",m.qualifiedMs,
                "surferDistanceM",m.distance,"centralDistanceM",m.centralDistance,
                "speedKmh",m.speedKmh,"riding",m.riding,
                "rideAccepted",m.ride.confirmed,"speedAssessmentActive",!m.riding,
                "fastWindowMs",RideDetector.FAST_MS,"rideConfirmationRequired",false,
                "fastSpanMs",m.ride.fastSpanMs,
                "rideEvent",m.ride.event,"speedEvidence",m.ride.evidence,"newRidePacket",m.ride.newPacket,
                "repeatedCoordinates",m.ride.repeatedCoordinates,"jumpSpeedKmh",m.ride.jumpSpeed,
                "jumpSpanMs",m.ride.jumpSpanMs,"jumpLimitKmh",RideDetector.JUMP_LIMIT_KMH,
                "noRideTimerEvent",m.noRideTimerEvent,
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
