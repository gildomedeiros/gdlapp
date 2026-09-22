package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.util.Locale;
/**
 * VT 3.0 forward/backward planner plus independent ride evidence; never calls flight APIs.
 * WAITING qualifies an approach, HOLDING latches arrival, RETURNING owns navigation yaw.
 * STOPPED latches a movement timeout until a new explicit Start or manual reposition.
 * Fresh fixes update qualification and ride evidence; the 100 ms loop only calculates commands.
 */
public final class ComeToMeController {
    public enum Phase { OFF, WAITING, APPROACHING, HOLDING, RETURNING, STOPPED }
    public Phase phase=Phase.OFF;
    public String reason="off", returnReason="none", event="none", noRideTimerEvent="none";
    public double centralLat=Double.NaN,centralLon=Double.NaN,bandLat=Double.NaN,bandLon=Double.NaN;
    public double bandBearing=Double.NaN,sideways=Double.NaN,distance=Double.NaN,centralDistance=Double.NaN;
    public double speedKmh=Double.NaN,forward=0,headingError=Double.NaN,returnHeading=Double.NaN;
    public boolean riding, insideBand, captureRequired=true;
    public long qualifiedMs,slowMs,inactiveMs,attemptElapsedMs,centralGeneration;
    private long qualifySince=-1,inactiveSince=-1,attemptSince=-1,lastFix=-1;
    private boolean runEnabled;
    public double approachStartLat=Double.NaN, approachStartLon=Double.NaN;
    public double approachTargetLat=Double.NaN, approachTargetLon=Double.NaN;
    public double approachHeading=Double.NaN, approachDistance=Double.NaN, approachProgress=Double.NaN;
    public double returnStartLat=Double.NaN, returnStartLon=Double.NaN, returnBearing=Double.NaN;
    public double returnDistance=Double.NaN, returnProgress=Double.NaN, returnCompletionCentralDistance=Double.NaN;
    public boolean returnAligned;
    public boolean approachAligned, speedJumpRejected;
    public long rejectedSpeedJumps;
    public final RideDetector ride=new RideDetector();
    private ComeToMeSettings settings=ComeToMeSettings.defaults();

    /** Begin a new explicit session. Automatic excursions never recapture central. */
    public void start(ComeToMeSettings config,long now) {
        cancel(); settings=config; runEnabled=config.enabled;
        phase=runEnabled ? Phase.WAITING : Phase.OFF; reason=runEnabled ? "capture_central" : "disabled";
        inactiveSince=-1;
    }

    /** Invalidate the whole plan on Stop/control loss; no implicit flight back. */
    public void cancel() {
        phase=Phase.OFF; reason="off"; returnReason="none"; runEnabled=false; captureRequired=true;
        centralLat=centralLon=bandLat=bandLon=bandBearing=Double.NaN;
        distance=centralDistance=sideways=headingError=returnHeading=Double.NaN;
        clearApproach(); clearReturn(); speedJumpRejected=false; rejectedSpeedJumps=0;
        forward=0; riding=false; insideBand=false; qualifiedMs=slowMs=inactiveMs=attemptElapsedMs=0;
        qualifySince=inactiveSince=attemptSince=lastFix=-1; ride.reset(); speedKmh=Double.NaN;
    }

    /** Pauses retain the destination and attempt clock; manual intervention requires a new anchor. */
    public void pause(boolean manual,long now) {
        forward=0; qualifySince=-1; qualifiedMs=slowMs=0; ride.clearEvidence("pause"); speedKmh=Double.NaN;
        approachAligned=false; returnAligned=false;
        if(manual) {
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
            phase=Phase.STOPPED; reason="movement_timeout"; forward=0; return true;
        }
        return phase==Phase.STOPPED;
    }

    /** Freeze the lineup orientation until exit; changing aircraft heading does not rotate the band. */

    private void createBand(AimingSession.Inputs in) {
        bandLat=in.target.lat; bandLon=in.target.lon;
        bandBearing=YawAimingMath.bearingToTarget(in.lat,in.lon,bandLat,bandLon);
        qualifySince=in.target.time; qualifiedMs=0; sideways=0; insideBand=true;
    }

    /**
     * Fast detection interrupts approach and enables responsive aiming. Only five-second
     * confirmation cancels the no-ride timer and authorizes a later ride-end return.
     * Observe even when translation is disabled or its movement timeout has latched.
     */
    private void observeSpeed(AimingSession.Fix fix,long now) {
        boolean wasRiding=ride.riding;
        ride.observe(fix,settings);
        riding=ride.riding; speedKmh=ride.fastSpeed; slowMs=ride.slowMs;
        speedJumpRejected=ride.rejected; rejectedSpeedJumps=ride.rejectedCount;
        event=ride.event;
        if(!runEnabled || phase==Phase.STOPPED) return;
        if(!wasRiding && riding) {
            clearApproach(); qualifySince=-1; qualifiedMs=0;
            if(phase!=Phase.RETURNING) { phase=Phase.WAITING; attemptSince=-1; reason="ride_detected"; }
        }
        if(event.equals("ride_confirmed")) { inactiveSince=-1; noRideTimerEvent="cancelled_confirmed_ride"; }
    }

    /** Start a return once; further ride/band events cannot reset its destination or deadline. */

    private void beginReturn(String cause,AimingSession.Inputs in,long now) {
        if(phase==Phase.RETURNING || phase==Phase.STOPPED) return;
        clearApproach(); clearReturn(); createBand(in); returnReason=cause; forward=0; inactiveSince=-1;
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
        event="none"; noRideTimerEvent="none"; speedJumpRejected=false; double previous=forward; forward=0;
        ride.event="none"; ride.newPacket=false; ride.rejected=false;
        boolean timedOut=expireAttempt(now); // Observation may continue; an expired move cannot be revived by a fast detection.
        if(in.target==null) return;
        boolean newFix=in.target.time!=lastFix;
        long oldFix=lastFix;
        if(newFix) { observeSpeed(in.target,now); lastFix=in.target.time; }
        if(!runEnabled) return;
        if(captureRequired) {
            if(!in.steadyHover()) { reason="waiting_for_hover"; return; }
            centralLat=in.lat; centralLon=in.lon; centralGeneration++; captureRequired=false;
            createBand(in); inactiveSince=-1; event="central_captured"; reason="lineup_qualification";
        }
        distance=YawAimingMath.distance(in.lat,in.lon,in.target.lat,in.target.lon);
        centralDistance=YawAimingMath.distance(in.lat,in.lon,centralLat,centralLon);
        inactiveMs=inactiveSince<0 ? 0 : Math.max(0,now-inactiveSince);
        if(timedOut) return;
        if(newFix) {
            if(oldFix>=0 && (lastFix<oldFix || lastFix-oldFix>3000)) qualifySince=-1;
            double north=Math.toRadians(in.target.lat-bandLat)*6371000;
            double east=Math.toRadians(YawAimingMath.shortestHeadingError(in.target.lon,bandLon))
                    *6371000*Math.cos(Math.toRadians(bandLat));
            sideways=east*Math.cos(Math.toRadians(bandBearing))-north*Math.sin(Math.toRadians(bandBearing));
            insideBand=Math.abs(sideways)<=settings.lineupWidth/2;
            if(!insideBand) {
                createBand(in);
                if(phase!=Phase.RETURNING && phase!=Phase.APPROACHING) { phase=Phase.WAITING; reason="band_exit"; attemptSince=-1; }
                if(event.equals("none")) event="band_recreated";
            } else {
                if(qualifySince<0) qualifySince=in.target.time;
                qualifiedMs=Math.max(0,in.target.time-qualifySince);
            }
            if(riding) { qualifySince=-1; qualifiedMs=0; }
            if(event.equals("ride_ended")) beginReturn("ride_ended",in,now);
        }
        if(!ride.confirmed && inactiveSince>=0 && now-inactiveSince>=settings.inactivityMs) beginReturn("no_ride_timeout",in,now);
        inactiveMs=inactiveSince<0 ? 0 : Math.max(0,now-inactiveSince);
        if(phase==Phase.RETURNING) {
            returnProgress=projectedReturnProgress(in);
            headingError=YawAimingMath.shortestHeadingError(returnHeading,in.heading);
            if(returnProgress>=returnDistance) {
                finishReturn("return_travel_completed"); return;
            }
            // Keep the transition neutral. Align once, then hold this saved heading during travel.
            if(now==attemptSince) { reason="return_alignment"; return; }
            if(!returnAligned) {
                if(!aligned(headingError)) { reason="return_alignment"; return; }
                returnAligned=true;
            }
            reason="moving_backward";
            forward=-rampedSpeed(previous,returnDistance-returnProgress,dt);
            return;
        }

        headingError=YawAimingMath.shortestHeadingError(
                YawAimingMath.bearingToTarget(in.lat,in.lon,in.target.lat,in.target.lon),in.heading);
        if(riding) { reason="ride_detected"; return; }
        if(phase==Phase.WAITING && qualifiedMs>=ComeToMeSettings.QUALIFY_MS) {
            if(distance<=settings.filmingDistance+ComeToMeSettings.FILM_TOLERANCE) {
                enterFilmingHold(now); return;
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
        if(approachProgress>=approachDistance) {
            enterFilmingHold(now); return;
        }
        if(centralDistance>=ComeToMeSettings.MAX_EXCURSION) {
            phase=Phase.HOLDING; reason="excursion_limit"; attemptSince=-1; return;
        }
        // Align once (and again after a safety pause); heading corrections continue during travel.
        if(!approachAligned) {
            if(!aligned(headingError)) { reason="approach_alignment"; return; }
            approachAligned=true;
        }
        reason="moving_forward";
        double remaining=Math.min(approachDistance-approachProgress,
                ComeToMeSettings.MAX_EXCURSION-centralDistance);
        forward=rampedSpeed(previous,remaining,dt);
    }

    /** Record arrival once. Subsequent band exits and arrivals cannot erase an active countdown. */

    private void enterFilmingHold(long now) {
        phase=Phase.HOLDING; reason="filming_distance_reached"; attemptSince=-1; forward=0;
        if(inactiveSince<0) { inactiveSince=now; noRideTimerEvent="started_filming_hold"; }
    }

    private void clearApproach() {
        approachTargetLat=approachTargetLon=Double.NaN;
        approachStartLat=approachStartLon=approachHeading=approachDistance=approachProgress=Double.NaN;
        approachAligned=false;
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
        phase=Phase.WAITING; reason=completion; event=completion; forward=0;
        attemptSince=-1; inactiveSince=-1; returnAligned=false;
        returnCompletionCentralDistance=centralDistance;
    }

    private void clearReturn() {
        returnStartLat=returnStartLon=returnBearing=returnHeading=returnDistance=returnProgress=Double.NaN;
        returnCompletionCentralDistance=Double.NaN; returnAligned=false;
    }

    public boolean approaching() { return phase==Phase.APPROACHING; }
    public boolean noRideTimerActive() { return inactiveSince>=0; }
    private static boolean aligned(double error) { return Double.isFinite(error)&&Math.abs(error)<=YawAimingMath.ALIGNMENT_DEGREES; }

    /** Increase speed gradually; any blocked movement returns zero immediately in update_state_machine(). */
    private static double rampedSpeed(double previous,double remaining,double dt) {
        double desired=ComeToMeSettings.MAX_SPEED*Math.min(1,Math.max(0,remaining)/5);
        // Avoid asymptotically approaching the stopping boundary; 0.05 m/s is the final crawl.
        desired=Math.min(ComeToMeSettings.MAX_SPEED,Math.max(0.05,desired));
        return Math.min(desired,Math.abs(previous)+ComeToMeSettings.ACCELERATION*Math.min(0.5,Math.max(0,dt)));
    }

    /** Revalidate translation against the last snapshot immediately before SDK submission. */
    public boolean permits(AimingSession.Inputs in,long now,double requested) {
        if(requested==0) return true;
        if(!runEnabled || captureRequired || in.validate(now)!=null || expireAttempt(now)) return false;
        // A newly arrived fix must pass the band/ride update before authorizing another movement.
        if(in.target.time!=lastFix) return false;
        double central=YawAimingMath.distance(in.lat,in.lon,centralLat,centralLon);
        if(requested>0) {
            return approaching() && approachAligned && !riding
                    && central<ComeToMeSettings.MAX_EXCURSION
                    && projectedProgress(in)<approachDistance;
        }
        return returning() && returnAligned && projectedReturnProgress(in)<returnDistance;
    }
    public ComeToMeSettings settings() { return settings; }
    public boolean returning() { return phase==Phase.RETURNING; }
    public String summary() {
        if(!runEnabled) return "Come to me: OFF";
        return String.format(Locale.US,"Come to me: %s · %s · surfer %.0f m / target %.0f m · central %.0f m",
                phase,reason.replace('_',' '),distance,settings.filmingDistance,centralDistance)
                +" · lineup "+qualifiedMs/1000+"/60s · ride end "+slowMs/1000+"/"+settings.rideEndMs/1000
                +"s · no ride "+inactiveMs/60000+"/"+settings.inactivityMs/60000+"min"
                +" · progress "+String.format(Locale.US,"%.1f/%.1f m",returning() ? returnProgress : approachProgress,returning() ? returnDistance : approachDistance)
                +" · no-ride timer "+(noRideTimerActive() ? "running" : "inactive")
                +" · attempt "+attemptElapsedMs/1000+"/300s";
    }
    public String details() {
        return String.format(Locale.US,"%s%nLineup %.0f m: %d/60 s · speed %.1f km/h · riding %s%nRide end %d/%d s · no ride %d/%d s · attempt %d/300 s",
                summary(),settings.lineupWidth,qualifiedMs/1000,speedKmh,riding,slowMs/1000,settings.rideEndMs/1000,
                inactiveMs/1000,settings.inactivityMs/1000,attemptElapsedMs/1000)
                +String.format(Locale.US,"%n5s speed %.1f km/h; return-qualified ride %s",ride.confirmationSpeed,ride.confirmed)
                +String.format(Locale.US,"%nReturn progress %.1f/%.1f m; completion distance to central %.1f m",
                        returnProgress,returnDistance,returnCompletionCentralDistance);
    }
}
