package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import java.util.function.BiConsumer;

/** VT 3.5: body-backward timed command; surfer yaw remains the yaw owner. */
public final class RetreatController {
    public RetreatSettings settings=RetreatSettings.disabled();
    public boolean active;
    public long startedAt=-1, periodStartedAt=-1, deadline=-1, cooldownUntil=-1, period;
    public double distance=Double.NaN, startDistance=Double.NaN;
    public String reason="idle";
    // VT 3.8: Reset only for a new session or newly received valid fix, never on cooldown/pause.
    public int startsWithoutFreshGps,previousStartsWithoutFreshGps;
    public boolean gpsFresh,staleLimitBlocked;
    public long lastFreshFixTime=-1;
    private final BiConsumer<String,String> events;
    public void observeGps(boolean fresh,AimingSession.Fix fix) {
        gpsFresh=fresh;
        if(fresh && fix!=null && fix.time>lastFreshFixTime) {
            lastFreshFixTime=fix.time;previousStartsWithoutFreshGps=startsWithoutFreshGps;
            boolean restore=startsWithoutFreshGps>0||staleLimitBlocked;
            startsWithoutFreshGps=0;staleLimitBlocked=false;
            if(restore)emit("retreat_gps_allowance_reset","fresh_valid_fix");
        }
    }
    private boolean allowStart() {
        if(gpsFresh)return true;
        if(startsWithoutFreshGps>=settings.maxStartsWithoutFreshGps) {
            if(!staleLimitBlocked){staleLimitBlocked=true;emit("retreat_start_blocked","stale_gps_retreat_limit");}
            return false;
        }
        startsWithoutFreshGps++;return true;
    }
    public RetreatController(BiConsumer<String,String> events) { this.events=events; }
    private void emit(String event,String why) {
        reason=why;
        try { events.accept(event,why); } catch(RuntimeException ignored) { /* Logging never controls flight. */ }
    }
    public void start(RetreatSettings config) {
        startsWithoutFreshGps=previousStartsWithoutFreshGps=0;gpsFresh=staleLimitBlocked=false;lastFreshFixTime=-1;
        settings=config;active=false;startedAt=periodStartedAt=deadline=cooldownUntil=-1;period=0;
        distance=startDistance=Double.NaN;reason="idle";
    }
    public boolean cooling(long now) { return cooldownUntil>=0 && now<cooldownUntil; }
    public long remaining(long now) { return active ? Math.max(0,deadline-now) : 0; }
    public long cooldownRemaining(long now) { return Math.max(0,cooldownUntil-now); }
    public boolean eligible(ComeToMeController m) {
        return settings.enabled && m.settings().enabled && !m.captureRequired
                && !m.returning() && m.phase!=ComeToMeController.Phase.STOPPED
                && m.phase!=ComeToMeController.Phase.OFF;
    }
    public boolean close(AimingSession.Inputs in) {
        return in.target!=null && YawAimingMath.distance(in.lat,in.lon,in.target.lat,in.target.lon)<settings.minimumDistance;
    }
    public boolean ownsRetainedTarget(ComeToMeController m,AimingSession.Inputs in,long now) {
        return eligible(m) && (active || cooling(now) || close(in));
    }
    public boolean blocksApproach(ComeToMeController m,AimingSession.Inputs in,long now) {
        return settings.enabled && m.settings().enabled && (active || cooling(now) || (!m.returning() && close(in)));
    }
    public void update(ComeToMeController m,AimingSession.Inputs in,long now) {
        distance=in.target==null ? Double.NaN : YawAimingMath.distance(in.lat,in.lon,in.target.lat,in.target.lon);
        if(cooldownUntil>=0 && now>=cooldownUntil) {
            cooldownUntil=-1;emit("retreat_cooldown_ended","elapsed");
        }
        if(!eligible(m)) { cancel(now,"movement_unavailable"); return; }
        double central=YawAimingMath.distance(in.lat,in.lon,m.centralLat,m.centralLon);
        if(central>=ComeToMeSettings.EXCURSION_STOP) {
            cancel(now,"excursion_limit");return;
        }
        if(active && now>=deadline) {
            if(close(in)) {
                if(!allowStart()) {
                    active=false;deadline=-1;emit("retreat_finished","stale_gps_retreat_limit");beginCooldown(now);return;
                }
                period++;periodStartedAt=now;deadline=now+settings.durationMs;
                emit("retreat_repeated","still_close");
            } else {
                active=false;deadline=-1;emit("retreat_finished","distance_sufficient");
                beginCooldown(now);return;
            }
        }
        if(!active && close(in)) {
            if(!allowStart())return;
            if(cooldownUntil>=0) { cooldownUntil=-1;emit("retreat_cooldown_ended","new_retreat"); }
            active=true;startedAt=periodStartedAt=now;deadline=now+settings.durationMs;period=1;startDistance=distance;
            if(m.approaching()) emit("retreat_approach_replaced","too_close");
            m.replaceApproachForRetreat();
            emit("retreat_started","too_close");
        }
    }
    private void beginCooldown(long now) {
        cooldownUntil=now+settings.cooldownMs;emit("retreat_cooldown_started","last_retreat_ended");
    }
    public void cancel(long now,String why) {
        if(!active) return;
        active=false;deadline=-1;emit("retreat_cancelled",why);beginCooldown(now);
    }
    public void stop(long now,String why) {
        cancel(now,why);
        if(cooldownUntil>=0) { cooldownUntil=-1;emit("retreat_cooldown_ended","session_stopped"); }
    }
    public boolean permits(ComeToMeController m,AimingSession.Inputs in,long now,double forward) {
        return active && now<deadline && eligible(m) && in.validate(now,true)==null
                && forward==-settings.speed
                && YawAimingMath.distance(in.lat,in.lon,m.centralLat,m.centralLon)<ComeToMeSettings.EXCURSION_STOP;
    }
    public String summary(long now) {
        return active ? String.format(java.util.Locale.US,"Retreat: %.1f m/s backward · %.1f s left · separation %.1f m",
                settings.speed,remaining(now)/1000.0,distance)
                : cooling(now) ? String.format(java.util.Locale.US,"Come to me cooldown: %.1f s",cooldownRemaining(now)/1000.0) : staleLimitBlocked ? "Retreat blocked: fresh surfer GPS required (allowance used)" : "";
    }
}
