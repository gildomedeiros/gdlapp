package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** CAM3 v2.7: Pure-Java per-cycle log adapter, exercised with real session and JSONL writer tests. */
public final class AimingCycleLog {
    private AimingCycleLog() { }
    public static void record(FullSessionLog fullLog,AimingSession session,AimingSession.Inputs observed,
                              long at,boolean usePhone,long submittedCycle,double lastCommandRate) {
        AimingSession.Inputs in=session.cycleInputs!=null ? session.cycleInputs : observed;
        AimingSession.Fix f=in.target;
        double distance=f==null ? Double.NaN : YawAimingMath.distance(in.lat,in.lon,f.lat,f.lon);
        double bearing=f==null || distance==0 ? Double.NaN : YawAimingMath.bearingToTarget(in.lat,in.lon,f.lat,f.lon);
        SurferYawController yaw=session.surferYaw;
        AimingSession.Inputs finalIn=session.cycleFinalInputs;
            fullLog.record("aiming_cycle","cycleId",session.cycleId,"session",session.sessionId(),
                    "cycleAtMs",session.cycleAt,"state",session.state().name(),"reason",session.reason(),
                    "inputProblem",in.validate(at),"source",usePhone ? "phone" : "lora_wifi",
                    "targetLatitude",f==null ? null : f.lat,"targetLongitude",f==null ? null : f.lon,
                    "targetFixMs",f==null ? null : f.time,"targetSequence",f==null || f.sequence<0 ? null : f.sequence,
                    "targetAgeMs",f==null ? null : at-f.time,"aircraftLatitude",in.lat,"aircraftLongitude",in.lon,
                    "aircraftHeadingDeg",in.heading,"aircraftFixMs",in.aircraftTime,"aircraftAgeMs",at-in.aircraftTime,
                    "distanceM",distance,"subjectBearingDeg",bearing,"relativeBearingDeg",YawAimingMath.shortestHeadingError(bearing,in.heading),
                    "targetSenderMs",f==null ? null : f.sampleTime,
                    "aimingMode",session.movement.returning() ? "return" : session.movement.approaching() ? "approach"
                            : session.movement.riding ? "riding" : "normal",
                    "requiredDirection",SurferYawController.directionName(yaw.requestedDirection),
                    "permittedDirection",SurferYawController.directionName(yaw.permittedDirection),
                    "lastPermittedDirectionMs",yaw.lastPermittedAt,"reverseBlocked",yaw.blocked,
                    "reverseBlockRemainingMs",yaw.remainingMs,"reverseBlockMs",SurferYawController.REVERSE_BLOCK_MS,
                    "desiredYawRate",yaw.desiredRate,"maxYawRate",YawAimingMath.MAX_RATE,
                    "maxYawAcceleration",YawAimingMath.MAX_ACCELERATION,
                    "finalInputProblem",finalIn==null ? null : finalIn.validate(at),
                    "finalHorizontalSpeedMps",finalIn==null ? null : finalIn.horizontalSpeed,
                    "finalVerticalSpeedMps",finalIn==null ? null : finalIn.verticalSpeed,
                    "finalHorizontalSpeedLimitMps",finalIn==null ? null : finalIn.horizontalSpeedLimit,
                    "horizontalSpeedMps",in.horizontalSpeed,"verticalSpeedMps",in.verticalSpeed,
                    "horizontalSpeedLimitMps",in.horizontalSpeedLimit,"verticalSpeedLimitMps",0.5,
                    "remainingAngleDeg",session.cycleAngle,"multiplier",session.cycleMultiplier,
                    "decision",session.cycleDecision,"requestedYawRate",session.cycleRequestedYaw,
                    "submitted",submittedCycle==session.cycleId,"submittedYawRate",submittedCycle==session.cycleId ? lastCommandRate : null);
    }
}
