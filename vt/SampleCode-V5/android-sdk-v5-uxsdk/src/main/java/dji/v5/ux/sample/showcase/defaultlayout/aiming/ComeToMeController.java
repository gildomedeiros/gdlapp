package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.util.Locale;
/**
 * VT 3.0 forward/backward planner plus independent ride evidence; never calls flight APIs.
 * WAITING qualifies an approach, HOLDING latches arrival, RETURNING owns navigation yaw.
 * STOPPED latches a movement timeout until a new explicit Start or manual reposition.
 * Fresh fixes update band and ride evidence; the 100 ms loop advances qualification and commands.
 */
public final class ComeToMeController {
    public enum Phase { OFF, WAITING, APPROACHING, HOLDING, RETURNING, STOPPED }
    public Phase phase=Phase.OFF;
    public String reason="off", returnReason="none", event="none", noRideTimerEvent="none";
    public double centralLat=Double.NaN,centralLon=Double.NaN,bandLat=Double.NaN,bandLon=Double.NaN;
    public double bandBearing=Double.NaN,sideways=Double.NaN,distance=Double.NaN,centralDistance=Double.NaN;
    public double speedKmh=Double.NaN,forward=0,headingError=Double.NaN,returnHeading=Double.NaN;
    public boolean riding, insideBand, captureRequired=true;
    public long qualifiedMs,slowMs,inactiveMs,attemptElapsedMs,centralGeneration,rideRemainingMs;
    // VT 3.1: qualifySince is the last credited monotonic timer tick.
    private long qualifySince=-1,inactiveSince=-1,attemptSince=-1,lastFix=-1;
    private boolean runEnabled;
    // VT 3.1: GPS gaps count toward qualification; rides and non-GPS pauses retain their existing rules.
    public String qualificationStatus="idle", qualificationEvent="none";
    public boolean gpsPaused, hasFilmed;
    public double approachStartThreshold() {
        // VT 3.6: One configurable margin applies before and after the first filming hold.
        return settings.filmingDistance+settings.reapproachMargin;
    }
    private void resetQualification(String why) {
        qualifiedMs=0; qualifySince=-1; qualificationStatus="waiting"; qualificationEvent="reset_"+why;
    }
    private void freezeQualification(String why) {
        if(!qualificationStatus.equals("frozen_"+why)) qualificationEvent="frozen_"+why;
        qualificationStatus="frozen_"+why; qualifySince=-1;
    }
    private void advanceQualification(long now) {
        if(!runEnabled || captureRequired) return;
        if(riding) { freezeQualification("ride"); return; }
        if(qualifySince>=0 && now>=qualifySince)
            qualifiedMs=Math.min(ComeToMeSettings.QUALIFY_MS,qualifiedMs+now-qualifySince);
        qualifySince=now;
        qualificationStatus=qualifiedMs>=ComeToMeSettings.QUALIFY_MS ? "qualified" : "counting";
    }
    /** Monotonic ride expiry is independent of GPS and safety pauses. */
    public void advanceRideClock(long now) {
        if(ride.advance(now)) { riding=false; slowMs=0; speedKmh=Double.NaN; event="ride_expired"; }
        rideRemainingMs=ride.remainingMs(now);
    }
    /** No new approach without fresh GPS; the qualification clock keeps running. */
    public void pauseForGps(long now) {
        event="none"; noRideTimerEvent="none"; qualificationEvent="none"; ride.event="none";
        if(!gpsPaused) {
            ride.clearEvidence("stale_gps"); slowMs=0; speedKmh=Double.NaN;
            approachAligned=false; returnAligned=false;
        }
        advanceRideClock(now);
        gpsPaused=true; forward=right=0; advanceQualification(now);
        if(!riding && !captureRequired) qualificationStatus=qualifiedMs>=ComeToMeSettings.QUALIFY_MS
                ? "qualified_waiting_fresh_gps" : "counting_without_fresh_gps";
        expireAttempt(now);
    }

    public double approachStartLat=Double.NaN, approachStartLon=Double.NaN;
    public double approachTargetLat=Double.NaN, approachTargetLon=Double.NaN;
    public double approachHeading=Double.NaN, approachDistance=Double.NaN, approachProgress=Double.NaN;
    public double returnStartLat=Double.NaN, returnStartLon=Double.NaN, returnBearing=Double.NaN;
    public double returnDistance=Double.NaN, returnProgress=Double.NaN, returnCompletionCentralDistance=Double.NaN;
    public boolean returnAligned;
    public boolean approachAligned, speedJumpRejected;
    public long rejectedSpeedJumps;
    public final RideDetector ride=new RideDetector();
    public ShorelinePositioning positioning;
    public double right,projectedSeparation=Double.NaN,alignmentError=Double.NaN;
    public String journeyKind="none";
    public double initialCentralLat=Double.NaN,initialCentralLon=Double.NaN;
    public String pathCheckStatus="not_checked",pathBlockReason="none";
    public String filmingSideStatus() {
        return positioning==null || !Double.isFinite(projectedSeparation) ? "unavailable"
            : projectedSeparation>0 ? "correct_side" : "wrong_side";
    }
    public long destinationFixTime=-1;
    public AngleRoutePlanner.Plan route;
    public int routeIndex;
    public double routeSurferLat=Double.NaN,routeSurferLon=Double.NaN,angleErrorDegrees=Double.NaN;
    public String routeStatus="none",clockwiseFailure="none",anticlockwiseFailure="none";
    public double[] clockwiseFailureValues,anticlockwiseFailureValues;
    public int failedSegment=-1;
    public double failedClearance=Double.NaN,failedBoundary=Double.NaN,failedExcursion=Double.NaN;
    public double waypointLat(){return route==null?approachTargetLat:route.points[routeIndex][0];}
    public double waypointLon(){return route==null?approachTargetLon:route.points[routeIndex][1];}
    public boolean angleApproach(){return positioning!=null&&positioning.diagonal();}

    public boolean presetApproach() {return positioning!=null;}
    private ComeToMeSettings settings=ComeToMeSettings.defaults();

    /** Begin a new explicit session. Automatic excursions never recapture central. */
    public void start(ComeToMeSettings config,long now) {
        cancel(); positioning=null; settings=config; runEnabled=config.enabled;
        phase=runEnabled ? Phase.WAITING : Phase.OFF; reason=runEnabled ? "capture_central" : "disabled";
        inactiveSince=-1;
    }

    /** Invalidate the whole plan on Stop/control loss; no implicit flight back. */
    public void cancel() {
        gpsPaused=false; hasFilmed=false; qualificationStatus="idle"; qualificationEvent="none";
        phase=Phase.OFF; reason="off"; returnReason="none"; runEnabled=false; captureRequired=true;
        centralLat=centralLon=bandLat=bandLon=bandBearing=Double.NaN;
        initialCentralLat=initialCentralLon=projectedSeparation=alignmentError=Double.NaN;
        pathCheckStatus="not_checked";pathBlockReason="none";
        distance=centralDistance=sideways=headingError=returnHeading=Double.NaN;
        clearApproach(); clearReturn(); speedJumpRejected=false; rejectedSpeedJumps=0;
        forward=right=0; riding=false; insideBand=false; qualifiedMs=slowMs=inactiveMs=attemptElapsedMs=0;
        qualifySince=inactiveSince=attemptSince=lastFix=-1; ride.reset(); speedKmh=Double.NaN;
    }

    /** Pauses retain the destination and attempt clock; manual intervention requires a new anchor. */
    public void pause(boolean manual,long now) {
        gpsPaused=false; forward=right=0; resetQualification(manual ? "manual" : "other_pause"); slowMs=0; ride.clearEvidence("pause"); speedKmh=Double.NaN;
        approachAligned=false; returnAligned=false;
        if(manual) {
            hasFilmed=false;
            clearApproach(); clearReturn();
            captureRequired=true; ride.reset(); riding=false; phase=runEnabled ? Phase.WAITING : Phase.OFF; attemptSince=-1;
            reason="manual_reposition"; inactiveSince=-1; lastFix=-1;
        }
        expireAttempt(now);
    }

    /** Do not restart an expired attempt automatically, including after a GPS pause. */
    private boolean expireAttempt(long now) {
        attemptElapsedMs=attemptSince<0 ? 0 : Math.max(0,now-attemptSince);
        if((phase==Phase.APPROACHING || phase==Phase.RETURNING) && attemptElapsedMs>=ComeToMeSettings.ATTEMPT_MS) {
            phase=Phase.STOPPED; reason="movement_timeout"; forward=right=0; return true;
        }
        return phase==Phase.STOPPED;
    }

    /** Freeze the lineup orientation until exit; changing aircraft heading does not rotate the band. */

    private void createBand(AimingSession.Inputs in,long now) {
        bandLat=in.target.lat; bandLon=in.target.lon;
        bandBearing=YawAimingMath.bearingToTarget(in.lat,in.lon,bandLat,bandLon);
        resetQualification("band_created"); qualifySince=now; sideways=0; insideBand=true;
    }

    /**
     * Fast detection interrupts approach and enables responsive aiming. VT 3.3 initial
     * detection cancels the no-ride timer; fixed ride expiry never authorizes return.
     * Observe even when translation is disabled or its movement timeout has latched.
     */
    private void observeSpeed(AimingSession.Fix fix,long now) {
        boolean wasRiding=ride.riding;
        if(ride.riding) return; // VT 3.3: no speed assessment while the fixed timer runs.
        ride.observe(fix,settings,now);
        riding=ride.riding; speedKmh=ride.fastSpeed; slowMs=ride.slowMs; rideRemainingMs=ride.remainingMs(now);
        speedJumpRejected=ride.rejected; rejectedSpeedJumps=ride.rejectedCount;
        event=ride.event;
        if(!runEnabled || phase==Phase.STOPPED) return;
        if(!wasRiding && riding) {
            clearApproach(); freezeQualification("ride");
            if(phase!=Phase.RETURNING) { phase=Phase.WAITING; attemptSince=-1; reason="ride_detected"; }
        }
        if(event.equals("ride_started")) { inactiveSince=-1; noRideTimerEvent="cancelled_ride_detected"; }
    }

    /** Start a return once; further ride/band events cannot reset its destination or deadline. */

    private void beginReturn(String cause,AimingSession.Inputs in,long now) {
        if(phase==Phase.RETURNING || phase==Phase.STOPPED) return;
        clearApproach(); clearReturn(); createBand(in,now); returnReason=cause; forward=right=0; inactiveSince=-1;
        noRideTimerEvent="cancelled_return_"+cause;
        // Every return starts from the current aircraft position, never from the prior excursion.
        returnStartLat=in.lat; returnStartLon=in.lon;
        returnBearing=YawAimingMath.bearingToTarget(in.lat,in.lon,centralLat,centralLon);
        returnHeading=(returnBearing+180)%360;
        returnDistance=Math.max(0,centralDistance-ComeToMeSettings.ARRIVAL_RADIUS);
        returnProgress=0;
        if(returnDistance==0) {
            finishReturn("already_near_central");
        } else { phase=Phase.RETURNING; reason=cause; attemptSince=now; }

    }

    /**
     * Called only with validated inputs and confirmed control. Alignment is checked independently
     * of the yaw controller; zero yaw is never evidence that forward movement is permitted.
     */
    public void update_state_machine(AimingSession.Inputs in,long now,double dt) {
        update_state_machine(in,now,dt,in.target!=null && now>=in.target.time && now-in.target.time<=3000);
    }
    // Only existing navigation may run with old surfer GPS. All aircraft checks stay active.
    public void update_state_machine(AimingSession.Inputs in,long now,double dt,boolean freshSurfer) {
        update_state_machine(in,now,dt,freshSurfer,false);
    }
    public void update_state_machine(AimingSession.Inputs in,long now,double dt,boolean freshSurfer,boolean retreatBlocked) {
        if(!freshSurfer && !approaching() && !returning()) { pauseForGps(now); return; }
        qualificationEvent="none";
        if(gpsPaused) { gpsPaused=false; qualificationEvent="resumed_fresh_gps"; }
        event="none"; noRideTimerEvent="none"; speedJumpRejected=false; forward=right=0;
        pathCheckStatus="not_checked";pathBlockReason="none";
        ride.event="none"; ride.newPacket=false; ride.rejected=false;
        advanceRideClock(now);
        boolean timedOut=expireAttempt(now); // Observation may continue; an expired move cannot be revived by a fast detection.
        if(in.target==null) return;
        boolean newFix=freshSurfer && in.target.time!=lastFix;
        long oldFix=lastFix;
        if(newFix) {
            if(!event.equals("ride_expired")) observeSpeed(in.target,now);
            lastFix=in.target.time;
        }
        if(!runEnabled) return;
        if(captureRequired) {
            if(!in.steadyHover()) { reason="waiting_for_hover"; return; }
            centralLat=in.lat; centralLon=in.lon; centralGeneration++; captureRequired=false;
            if(!Double.isFinite(initialCentralLat)) {initialCentralLat=in.lat;initialCentralLon=in.lon;}
            createBand(in,now); inactiveSince=-1; event="central_captured"; reason="lineup_qualification";
        }
        distance=YawAimingMath.distance(in.lat,in.lon,in.target.lat,in.target.lon);
        centralDistance=YawAimingMath.distance(in.lat,in.lon,centralLat,centralLon);
        if(positioning!=null) {double[] measures=positioning.measures(in);projectedSeparation=measures[0];alignmentError=measures[1];}
        inactiveMs=inactiveSince<0 ? 0 : Math.max(0,now-inactiveSince);
        if(timedOut) return;
        if(newFix) {
            if(oldFix>=0 && lastFix<oldFix) resetQualification("clock_reset");

            double north=Math.toRadians(in.target.lat-bandLat)*6371000;
            double east=Math.toRadians(YawAimingMath.shortestHeadingError(in.target.lon,bandLon))
                    *6371000*Math.cos(Math.toRadians(bandLat));
            sideways=east*Math.cos(Math.toRadians(bandBearing))-north*Math.sin(Math.toRadians(bandBearing));
            insideBand=Math.abs(sideways)<=settings.lineupWidth/2;
            if(!insideBand) {
                createBand(in,now);
                if(phase!=Phase.RETURNING && phase!=Phase.APPROACHING) { phase=Phase.WAITING; reason="band_exit"; attemptSince=-1; }
                if(event.equals("none")) event="band_recreated";
            }
            if(riding) freezeQualification("ride");
            // VT 3.2: Ride end only releases the ride gate. It neither returns to central
            // nor recreates the band; ordinary distance/GPS/control gates decide the next approach.
        }
        advanceQualification(now);
        // Retreat/cooldown suppress new navigation without suppressing fresh ride evidence.
        if(retreatBlocked && !returning()) { reason="retreat_or_cooldown"; return; }
        if(!ride.confirmed && inactiveSince>=0 && now-inactiveSince>=settings.inactivityMs) beginReturn("no_ride_timeout",in,now);
        inactiveMs=inactiveSince<0 ? 0 : Math.max(0,now-inactiveSince);
        if(phase==Phase.RETURNING) {
            returnProgress=projectedReturnProgress(in);
            headingError=YawAimingMath.shortestHeadingError(returnHeading,in.heading);
            // VT 3.2: Release navigation ownership before the sub-metre crawl.
            if(returnDistance-returnProgress<=ComeToMeSettings.COMPLETION_TOLERANCE) {
                finishReturn(returnProgress>=returnDistance ? "return_travel_completed" : "return_arrival_tolerance"); return;
            }
            // Keep the transition neutral. Align once, then hold this saved heading during travel.
            if(now==attemptSince) { reason="return_alignment"; return; }
            if(!returnAligned) {
                if(!aligned(headingError)) { reason="return_alignment"; return; }
                returnAligned=true;
            }
            reason="moving_backward";
            forward=-arrivalSpeed(returnDistance-returnProgress);
            return;
        }

        headingError=YawAimingMath.shortestHeadingError(
                YawAimingMath.bearingToTarget(in.lat,in.lon,in.target.lat,in.target.lon),in.heading);
        if(riding) { reason="ride_detected"; return; }
        if(positioning!=null) { updatePreset(in,now,freshSurfer);return; }
        // VT 3.1: A holding tripod may re-approach only beyond the configurable margin.
        // Keep the no-ride timer running; small distance changes never seize yaw ownership.
        if((phase==Phase.WAITING || (phase==Phase.HOLDING && !reason.equals("excursion_limit")))
                && qualifiedMs>=ComeToMeSettings.QUALIFY_MS) {
            // VT 3.6: Ignore sub-micrometre geodesic rounding at the shared boundary.
            if(distance<=approachStartThreshold()+0.000001) {
                if(!hasFilmed) enterFilmingHold(now);
                else { phase=Phase.HOLDING; reason="within_reapproach_margin"; }
                return;
            }
            phase=Phase.APPROACHING; attemptSince=now;
            approachStartLat=in.lat; approachStartLon=in.lon;
            approachTargetLat=in.target.lat; approachTargetLon=in.target.lon;
            approachHeading=YawAimingMath.bearingToTarget(in.lat,in.lon,in.target.lat,in.target.lon);
            approachDistance=distance-settings.filmingDistance;
            approachProgress=0; approachAligned=false;
        }
        if(phase!=Phase.APPROACHING) return;
        // Progress is net displacement along the frozen heading, never accumulated GPS steps.
        approachProgress=projectedProgress(in);
        headingError=YawAimingMath.shortestHeadingError(approachHeading,in.heading);
        // VT 3.2: Saved projected travel, not live surfer distance, defines arrival.
        if(approachDistance-approachProgress<=ComeToMeSettings.COMPLETION_TOLERANCE) {
            enterFilmingHold(now);
            if(approachProgress<approachDistance) reason="approach_arrival_tolerance";
            return;
        }
        // VT 3.2: Stop 1 m inside the hard 250 m boundary instead of crawling toward it.
        if(centralDistance>=settings.excursionStop()) {
            phase=Phase.HOLDING; reason="excursion_limit"; attemptSince=-1; return;
        }
        // Align once (and again after a safety pause); heading corrections continue during travel.
        if(!approachAligned) {
            if(!aligned(headingError)) { reason="approach_alignment"; return; }
            approachAligned=true;
        }
        reason="moving_forward";
        double remaining=Math.min(approachDistance-approachProgress,
                settings.maxExcursionMetres-centralDistance);
        forward=arrivalSpeed(remaining);
    }


    private void updatePreset(AimingSession.Inputs in,long now,boolean freshSurfer) {
        if(angleApproach()){updateAngle(in,now,freshSurfer);return;}
        double[] measures=positioning.measures(in);projectedSeparation=measures[0];alignmentError=measures[1];
        if(!approaching()) {
            if(phase!=Phase.WAITING&&phase!=Phase.HOLDING)return;
            if(!freshSurfer) {reason="waiting_fresh_planning_fix";return;}
            if(projectedSeparation<=0) {reason="wrong_filming_side_hold";return;}
            boolean close=projectedSeparation>approachStartThreshold()+.000001;
            boolean align=Math.abs(alignmentError)>positioning.tolerance;
            if(!close&&!align) {if(!hasFilmed)enterFilmingHold(now);else {phase=Phase.HOLDING;reason="within_projected_margin";}return;}
            double separation=close?settings.filmingDistance:projectedSeparation;
            double[] target=positioning.destination(in,separation);
            pathBlockReason=positioning.pathBlockReason(in,target[0],target[1],centralLat,centralLon);
            pathCheckStatus=pathBlockReason.equals("none")?"allowed":"blocked";
            if(!pathBlockReason.equals("none")) {reason="alignment_path_or_destination_blocked";return;}
            approachStartLat=in.lat;approachStartLon=in.lon;approachTargetLat=target[0];approachTargetLon=target[1];
            approachHeading=YawAimingMath.bearingToTarget(in.lat,in.lon,target[0],target[1]);
            approachDistance=YawAimingMath.distance(in.lat,in.lon,target[0],target[1]);approachProgress=0;
            destinationFixTime=in.target.time;journeyKind=close?"projected_reapproach":"alignment_only_preserve_projection";
            approachAligned=true;phase=Phase.APPROACHING;attemptSince=now;event="fixed_destination_planned";
            reason=journeyKind;return; // Neutral transition; yaw continues aiming at the surfer.
        }
        double remaining=YawAimingMath.distance(in.lat,in.lon,approachTargetLat,approachTargetLon);
        approachProgress=approachDistance-remaining;
        if(remaining<=ComeToMeSettings.COMPLETION_TOLERANCE) {enterFilmingHold(now);reason="fixed_destination_arrived";return;}
        if(centralDistance>=settings.excursionStop()) {phase=Phase.HOLDING;reason="excursion_limit";attemptSince=-1;return;}
        double[] v=positioning.velocity(in,approachTargetLat,approachTargetLon,arrivalSpeed(Math.min(remaining,settings.maxExcursionMetres-centralDistance)));
        forward=v[0];right=v[1];reason="moving_to_fixed_destination_aiming_surfer";
    }
    private boolean angleLegAllowed(AimingSession.Inputs in) {
        AngleRoutePlanner.Check c=AngleRoutePlanner.check(positioning,in.lat,in.lon,waypointLat(),waypointLon(),
            routeSurferLat,routeSurferLon,initialCentralLat,initialCentralLon,centralLat,centralLon);
        if(!c.reason.equals("none")){pathBlockReason=c.reason;failedSegment=routeIndex;
            failedClearance=c.clearance;failedBoundary=c.boundary;failedExcursion=c.excursion;return false;}
        return true;
    }
    private void updateAngle(AimingSession.Inputs in,long now,boolean freshSurfer) {
        projectedSeparation=Double.NaN;alignmentError=Double.NaN;
        angleErrorDegrees=positioning.angleError(in);
        if(!approaching()) {
            if(phase!=Phase.WAITING&&phase!=Phase.HOLDING)return;
            if(!freshSurfer){reason="waiting_fresh_planning_fix";return;}
            if(distance<1e-6){reason="coincident_position_hold";return;}
            boolean close=distance>approachStartThreshold()+.000001;
            if(!close&&Math.abs(angleErrorDegrees)<=positioning.angleTolerance+.000001){
                if(!hasFilmed)enterFilmingHold(now);else {phase=Phase.HOLDING;reason="within_distance_and_angle_tolerance";}return;}
            double radius=close?settings.filmingDistance:distance;
            double[] target=positioning.destination(in,radius);
            AngleRoutePlanner.Plan p=AngleRoutePlanner.plan(positioning,in,target,initialCentralLat,initialCentralLon,centralLat,centralLon);
            clockwiseFailure=p.clockwiseFailure;anticlockwiseFailure=p.anticlockwiseFailure;
            clockwiseFailureValues=p.clockwiseFailureValues;anticlockwiseFailureValues=p.anticlockwiseFailureValues;
            failedSegment=p.failedSegment;failedClearance=p.failedClearance;failedBoundary=p.failedBoundary;failedExcursion=p.failedExcursion;
            pathCheckStatus=p.reason.equals("none")?"allowed":"blocked";pathBlockReason=p.reason;
            if(!p.reason.equals("none")){reason=p.reason;routeStatus="blocked";return;}
            route=p;routeIndex=0;routeSurferLat=in.target.lat;routeSurferLon=in.target.lon;
            approachStartLat=in.lat;approachStartLon=in.lon;approachTargetLat=target[0];approachTargetLon=target[1];
            approachHeading=YawAimingMath.bearingToTarget(in.lat,in.lon,target[0],target[1]);
            approachDistance=p.length;approachProgress=0;destinationFixTime=in.target.time;
            journeyKind=close?"direct_distance_reapproach":"angle_alignment_preserve_direct_distance";
            routeStatus="planned";approachAligned=true;phase=Phase.APPROACHING;attemptSince=now;event="fixed_route_planned";reason=journeyKind;return;
        }
        double remaining=YawAimingMath.distance(in.lat,in.lon,waypointLat(),waypointLon());
        if(remaining<=ComeToMeSettings.COMPLETION_TOLERANCE){
            if(routeIndex==route.points.length-1){routeStatus="arrived";enterFilmingHold(now);reason="fixed_destination_arrived";return;}
            routeIndex++;routeStatus="waypoint_transition";reason="waypoint_transition";return;
        }
        if(centralDistance>=settings.excursionStop()){phase=Phase.HOLDING;reason="excursion_limit";routeStatus="blocked";attemptSince=-1;return;}
        if(!angleLegAllowed(in)){reason=pathBlockReason;routeStatus="blocked";return;}
        double total=remaining;for(int i=routeIndex+1;i<route.points.length;i++)total+=YawAimingMath.distance(route.points[i-1][0],route.points[i-1][1],route.points[i][0],route.points[i][1]);
        approachProgress=approachDistance-total;routeStatus="traveling";
        double[] v=positioning.velocity(in,waypointLat(),waypointLon(),arrivalSpeed(Math.min(remaining,settings.maxExcursionMetres-centralDistance)));
        // No positioning reserve/horizon: veto a shoreward command if already on the boundary line.
        double normal=v[0]*(Math.cos(Math.toRadians(in.heading))*positioning.shore.seaNorth+Math.sin(Math.toRadians(in.heading))*positioning.shore.seaEast)
            +v[1]*(-Math.sin(Math.toRadians(in.heading))*positioning.shore.seaNorth+Math.cos(Math.toRadians(in.heading))*positioning.shore.seaEast);
        if(positioning.boundaryDistance(in.lat,in.lon,initialCentralLat,initialCentralLon)<=0&&normal< -1e-6){reason="route_central_boundary";return;}
        forward=v[0];right=v[1];reason="moving_saved_route_aiming_surfer";
    }
    /** Record arrival once. Subsequent band exits and arrivals cannot erase an active countdown. */

    private void enterFilmingHold(long now) {
        hasFilmed=true;
        phase=Phase.HOLDING; reason="filming_distance_reached"; attemptSince=-1; forward=right=0;
        if(inactiveSince<0) { inactiveSince=now; noRideTimerEvent="started_filming_hold"; }
    }

    private void clearApproach() {
        route=null;routeIndex=0;routeStatus="none";routeSurferLat=routeSurferLon=angleErrorDegrees=Double.NaN;
        clockwiseFailure=anticlockwiseFailure="none";clockwiseFailureValues=anticlockwiseFailureValues=null;failedSegment=-1;failedClearance=failedBoundary=failedExcursion=Double.NaN;
        approachTargetLat=approachTargetLon=Double.NaN;
        approachStartLat=approachStartLon=approachHeading=approachDistance=approachProgress=Double.NaN;
        approachAligned=false;right=0;journeyKind="none";destinationFixTime=-1;
    }

    /** Local north/east projection from the saved drone origin; lateral travel contributes zero. */
    private double projectedProgress(AimingSession.Inputs in) {
        double north=Math.toRadians(in.lat-approachStartLat)*6371000;
        double east=Math.toRadians(YawAimingMath.shortestHeadingError(in.lon,approachStartLon))
                *6371000*Math.cos(Math.toRadians(approachStartLat));
        return north*Math.cos(Math.toRadians(approachHeading))+east*Math.sin(Math.toRadians(approachHeading));
    }

    /** Signed progress toward central along the frozen return bearing; sideways drift is excluded. */
    private double projectedReturnProgress(AimingSession.Inputs in) {
        double north=Math.toRadians(in.lat-returnStartLat)*6371000;
        double east=Math.toRadians(YawAimingMath.shortestHeadingError(in.lon,returnStartLon))
                *6371000*Math.cos(Math.toRadians(returnStartLat));
        return north*Math.cos(Math.toRadians(returnBearing))+east*Math.sin(Math.toRadians(returnBearing));
    }

    /** Latch travel completion and preserve its actual GPS miss distance for later analysis. */
    private void finishReturn(String completion) {
        phase=Phase.WAITING; reason=completion; event=completion; forward=right=0;
        attemptSince=-1; inactiveSince=-1; returnAligned=false;
        returnCompletionCentralDistance=centralDistance;
    }

    private void clearReturn() {
        returnStartLat=returnStartLon=returnBearing=returnHeading=returnDistance=returnProgress=Double.NaN;
        returnCompletionCentralDistance=Double.NaN; returnAligned=false;
    }

    // VT 3.5: Cancel only the approach, preserving central, ride and no-ride bookkeeping.
    public void replaceApproachForRetreat() {
        clearApproach();attemptSince=-1;forward=right=0;
        phase=Phase.WAITING;reason="retreat";
    }
    public boolean approaching() { return phase==Phase.APPROACHING; }
    public boolean noRideTimerActive() { return inactiveSince>=0; }
    private static boolean aligned(double error) { return Double.isFinite(error)&&Math.abs(error)<=YawAimingMath.ALIGNMENT_DEGREES; }

    /** VT 3.6: Immediate distance-limited command; blocked movement still returns zero. */
    private double arrivalSpeed(double remaining) {
        // VT 3.2: The arrival slope is independent of cruise speed: 5 m -> 1 m/s,
        // 2 m -> 0.4 m/s. At 3 m/s cruise, slowdown therefore begins at 15 m.
        double desired=Math.min(settings.maxMovementSpeed,
                Math.max(0,remaining)*ComeToMeSettings.ARRIVAL_SPEED_PER_METRE);
        // VT 3.2: Retain the existing speed profile; callers now finish within 1 m, before the final crawl.
        desired=Math.min(settings.maxMovementSpeed,Math.max(0.05,desired));
        return desired; // VT 3.6: Remove the software acceleration ramp, retain arrival braking.
    }

    /** Revalidate translation against the last snapshot immediately before SDK submission. */
    public boolean permits(AimingSession.Inputs in,long now,double requested) {
        if(requested==0) return true;
        // VT 3.6: Enforce the configured approach/return cap independently of retreat speed.
        if(!Double.isFinite(requested) || Math.abs(requested)>settings.maxMovementSpeed) return false;
        if(!runEnabled || captureRequired || in.validate(now,approaching() || returning())!=null || expireAttempt(now)) return false;
        // A newly arrived fix must pass the band/ride update before authorizing another movement.
        if(in.target.time!=lastFix) return false;
        double central=YawAimingMath.distance(in.lat,in.lon,centralLat,centralLon);
        if(requested>0) {
            return approaching() && approachAligned && !riding
                    && central<settings.excursionStop()
                    && approachDistance-projectedProgress(in)>ComeToMeSettings.COMPLETION_TOLERANCE;
        }
        // VT 3.2: Submission must use the same arrival boundary as the planner.
        return returning() && returnAligned && returnDistance-projectedReturnProgress(in)>ComeToMeSettings.COMPLETION_TOLERANCE;
    }

    public boolean permits(AimingSession.Inputs in,long now,double requestedForward,double requestedRight) {
        if(requestedForward==0&&requestedRight==0)return true;
        if(!presetApproach()||returning())return requestedRight==0&&permits(in,now,requestedForward);
        double speed=Math.hypot(requestedForward,requestedRight);
        if(!Double.isFinite(speed)||speed>settings.maxMovementSpeed+.000001||!runEnabled||captureRequired||!approaching()||riding||
                in.validate(now,true)!=null||expireAttempt(now)||in.target.time!=lastFix)return false;
        double central=YawAimingMath.distance(in.lat,in.lon,centralLat,centralLon);
        double remaining=YawAimingMath.distance(in.lat,in.lon,waypointLat(),waypointLon());
        if(angleApproach()&&(route==null||!angleLegAllowed(in)))return false;
        if(angleApproach()&&positioning.boundaryDistance(in.lat,in.lon,initialCentralLat,initialCentralLon)<=0){
            double h=Math.toRadians(in.heading);
            double normal=requestedForward*(Math.cos(h)*positioning.shore.seaNorth+Math.sin(h)*positioning.shore.seaEast)
                +requestedRight*(-Math.sin(h)*positioning.shore.seaNorth+Math.cos(h)*positioning.shore.seaEast);
            if(normal< -1e-6)return false;
        }
        if(central>=settings.excursionStop()||remaining<=ComeToMeSettings.COMPLETION_TOLERANCE)return false;
        double[] v=positioning.velocity(in,waypointLat(),waypointLon(),arrivalSpeed(Math.min(remaining,settings.maxExcursionMetres-central)));
        // A changed heading/position must not rotate an already calculated body command toward another point.
        return Math.abs(v[0]-requestedForward)<=.01&&Math.abs(v[1]-requestedRight)<=.01;
    }
    public ComeToMeSettings settings() { return settings; }
    public boolean returning() { return phase==Phase.RETURNING; }
    public String summary() {
        if(!runEnabled) return "Come to me: OFF";
        if(angleApproach())return String.format(Locale.US,"Come to me angle %.1f° · %s · %s\nDirect horizontal %.1f m (filming %.1f m; approach > %.1f m) · angle error %.1f° (tolerance %.1f°)\nRoute %s / %s · waypoint %d/%d · excursion %.1f/%.1f m",
            positioning.angleDegrees,phase,reason.replace('_',' '),distance,settings.filmingDistance,approachStartThreshold(),angleErrorDegrees,positioning.angleTolerance,
            route==null?"none":route.kind,routeStatus,route==null?0:routeIndex+1,route==null?0:route.points.length,centralDistance,settings.maxExcursionMetres);
        if(positioning!=null)return String.format(Locale.US,
            "Come to me %s · %s · %s\nProjected %s separation %.1f m (setting %.1f m; start > %.1f m) · Come-to-me %s alignment error %.1f m\nDirect horizontal surfer distance %.1f m · central %.1f m · fixed destination remaining %.1f m\nRide %d/%d s · no ride %d/%d s",
            positioning.mode,phase,reason.replace('_',' '),positioning.mode.equals("front")?"shoreward":"alongshore",
            projectedSeparation,settings.filmingDistance,approachStartThreshold(),positioning.mode.equals("front")?"alongshore":"shore-normal",
            alignmentError,distance,centralDistance,approachDistance-approachProgress,rideRemainingMs/1000,settings.rideDurationMs/1000,inactiveMs/1000,settings.inactivityMs/1000);
        return (gpsPaused ? "GPS stale · movement paused · " : "")+String.format(Locale.US,"Come to me: %s · %s · surfer %.0f m / target %.0f m · central %.0f m",
                phase,reason.replace('_',' '),distance,settings.filmingDistance,centralDistance)
                +" · lineup "+qualifiedMs/1000+"/"+ComeToMeSettings.QUALIFY_MS/1000+"s · "+qualificationStatus.replace('_',' ')+" · ride remaining "+rideRemainingMs/1000+"/"+settings.rideDurationMs/1000
                +"s · no ride "+inactiveMs/60000+"/"+settings.inactivityMs/60000+"min"
                +" · progress "+String.format(Locale.US,"%.1f/%.1f m",returning() ? returnProgress : approachProgress,returning() ? returnDistance : approachDistance)
                +" · no-ride timer "+(noRideTimerActive() ? "running" : "inactive")
                +" · re-approach > "+String.format(Locale.US,"%.0f m",settings.filmingDistance+settings.reapproachMargin)
                +" · attempt "+attemptElapsedMs/1000+"/300s";
    }
    public String details() {
        return String.format(Locale.US,"%s%nLineup %.0f m: %d/%d s · speed %.1f km/h · riding %s%nRide remaining %d/%d s · no ride %d/%d s · attempt %d/300 s",
                summary(),settings.lineupWidth,qualifiedMs/1000,ComeToMeSettings.QUALIFY_MS/1000,speedKmh,riding,rideRemainingMs/1000,settings.rideDurationMs/1000,
                inactiveMs/1000,settings.inactivityMs/1000,attemptElapsedMs/1000)
                +"\nRide policy: fixed duration; initial detection accepted immediately"
                +String.format(Locale.US,"%nReturn progress %.1f/%.1f m; completion distance to central %.1f m",
                        returnProgress,returnDistance,returnCompletionCentralDistance);
    }
}
