package au.com.gildo.fm;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class AimAtSurfer {
    private static final double REFERENCE_PACE_KMH = 9.0;
    private static final long SAMPLE_MS = 100, STOP_TIMEOUT_MS = 30_000, RUN_MARGIN_MS = 5_000;

    enum Axis { PAN, TILT }

    enum Stage {
        LOG("Open attempt log"), GPS("Read cached GPS fix"), DRIVER("Get ready HS driver"),
        TARGET("Calculate target geometry"), TIMING("Get cached HS timing"), STATUS("Axis status reply"), BASELINE("Axis baseline reply"),
        SPEED("Choose HS speed"), PERIOD("Calculate motor step interval"), MODE("Set HS mode reply"),
        SET_PERIOD("Set speed reply"), GOTO_MODE("Set GoTo mode reply"), GOTO_TARGET("Set GoTo target reply"),
        START("Start reply"), STOP("Stop reply"),
        FINAL_ENCODER("Final encoder reply"), GAP("Calculate final gap"), PUBLISH("Publish attempt log");

        final String label;
        Stage(String label) { this.label = label; }
    }

    interface Operation<T> { T run() throws IOException; }

    record Target(int count, double rangeM, double angleDeg) {}

    static Target target(GoTo.Point fix, GoTo.Reference reference, int cpr) {
        if (!Geo.valid(fix.lat(), fix.lon())) throw new IllegalArgumentException("Invalid surfer coordinates");
        GoTo.Point pivot = reference.pivot();
        if (fix.lat() == -pivot.lat() && Math.abs(Geo.delta(fix.lon(), pivot.lon())) == 180)
            throw new IllegalArgumentException("Surfer bearing is undefined");
        double range = Geo.distance(pivot.lat(), pivot.lon(), fix.lat(), fix.lon());
        double bearing = Geo.bearing(pivot.lat(), pivot.lon(), fix.lat(), fix.lon());
        return new Target(GoTo.panTarget(bearing, reference, cpr), range, bearing);
    }

    static Target tiltTarget(double elevationM,GoTo.Reference reference,double rangeM,int cpr) {
        double angle=Geo.elevation(rangeM,reference.pivot().elevation(),elevationM);
        return new Target(GoTo.tiltTarget(angle,reference,cpr),rangeM,angle);
    }

    static int shortestStep(int target, int baseline, int cpr) {
        int forward = Math.floorMod(GoTo.delta(target, baseline), cpr);
        return forward * 2 > cpr ? forward - cpr : forward;
    }

    static double siderealForRange(double rangeM) {
        double rate = (REFERENCE_PACE_KMH / 3.6) * GoTo.SIDEREAL_DAY_SECONDS / (2 * Math.PI * rangeM);
        return Math.max(GoTo.MIN_HS_SIDEREAL, Math.min(rate, GoTo.MAX_HS_SIDEREAL));
    }

    static void run(Attempt aim, GoTo mount, GoTo.PanTiming timing, int panCpr, int tiltCpr,
                    BooleanSupplier active) throws IOException {
        if (aim.time(Stage.STATUS, () -> mount.panMoving())) throw new IOException("Pan is already moving");
        GoTo.PanSample baseline = aim.time(Stage.BASELINE, mount::readPan);
        aim.baseline = baseline.count();
        int step = shortestStep(aim.target.count(), baseline.count(), panCpr);
        aim.goal = (baseline.count() + step) & 0xffffff;
        aim.event("aim_pan_plan", "baseline", aim.baseline, "target", aim.target.count(),
            "plannedStopCount", aim.goal, "signedTravelCounts", step, "countsPerRevolution", panCpr);
        if (step == 0) {
            aim.finalCount = baseline.count();
            aim.calculateGap(panCpr);
            aim.outcome = "Already aimed";
            runTilt(aim, mount, tiltCpr, active);
            return;
        }

        aim.progress.accept("Aim at Surfer HS · Pan " + aim.baseline + " → " + aim.goal + " counts");
        aim.sidereal = aim.time(Stage.SPEED, () -> siderealForRange(aim.target.rangeM()));
        double rate = aim.sidereal * panCpr / GoTo.SIDEREAL_DAY_SECONDS;
        int period = aim.time(Stage.PERIOD, () -> mount.highSpeedPanPeriod(timing, rate));
        aim.motorPeriod = period;
        aim.event("aim_speed_selected", "rangeM", aim.target.rangeM(), "referencePaceKmh", REFERENCE_PACE_KMH,
            "countsPerSecond", rate, "sidereal", aim.sidereal, "stepPeriod", period);
        aim.progress.accept(String.format(Locale.US, "Aim at Surfer HS · Pan %.0f× at %.2f m",
            aim.sidereal, aim.target.rangeM()));
        aim.time(Stage.MODE, () -> mount.setHighSpeedMode(1, Integer.signum(step)));
        aim.time(Stage.SET_PERIOD, () -> mount.setStepPeriod(1, period));
        requireActive(active);

        long plannedMs = (long)Math.ceil(Math.abs(step) * 1000.0 / rate);
        try {
            aim.startAck = aim.time(Stage.START, () -> mount.startAxis(1, at -> {
                aim.startSent = at;
                aim.event("aim_start_sent", "atMs", at, "gpsAgeMs", at - aim.fixReceived,
                    "deadlineMs", at + plannedMs + RUN_MARGIN_MS);
            }));
            aim.progress.accept("Aim at Surfer HS · Pan Start acknowledged");
            watchPan(aim, mount, step, baseline.count(), aim.startSent + plannedMs + RUN_MARGIN_MS, active);
        } catch (IOException | RuntimeException failure) {
            aim.fail(aim.startAck == null ? "Start issue" : "Pan feedback failed", failure);
        } finally {
            if (aim.startSent != null) {
                sendStop(aim, mount);
                try {
                    if (aim.crossing != null && aim.stopAck != null) runTilt(aim, mount, tiltCpr, active);
                    else if (aim.tilt != null && aim.tilt.issue == null) {
                        aim.tilt.skip(aim.crossing == null ?
                            "Pan did not confirm target crossing" : "Pan Stop acknowledgment unavailable");
                    }
                } finally {
                    confirmPanStop(aim, mount, panCpr);
                }
            }
        }
    }

    private static void runTilt(Attempt aim, GoTo mount, int cpr, BooleanSupplier active) {
        Attempt tilt = aim.tilt;
        if (tilt == null || tilt.target == null) return;
        tilt.phaseStarted = GoTo.now();
        tilt.event("aim_tilt_dispatch", "startedMs", tilt.phaseStarted, "panStopAckMs", aim.stopAck,
            "panStopAckToTiltDispatchMs", Attempt.since(tilt.phaseStarted, aim.stopAck),
            "trigger", aim.stopAck == null ? "Pan already aimed and stopped" : "Pan Stop acknowledged");
        try {
            requireActive(active);
            tilt.progress.accept("Aim at Surfer HS · Tilt GoTo");
            if (tilt.time(Stage.STATUS, () -> mount.moving(2))) throw new IOException("Tilt is already moving");
            tilt.baseline = tilt.time(Stage.BASELINE, () -> mount.readEncoder(2)).count();
            tilt.goal = tilt.target.count();
            int step = GoTo.delta(tilt.goal, tilt.baseline);
            tilt.event("aim_tilt_plan", "baseline", tilt.baseline, "target", tilt.goal,
                "signedTravelCounts", step, "countsPerRevolution", cpr);
            if (step == 0) {
                tilt.outcome = "Already aimed";
                return;
            }

            tilt.gotoMode = GoTo.positionMode(step, cpr);
            tilt.direction = step < 0 ? 1 : 0;
            tilt.event("aim_tilt_goto_settings", "mode", tilt.gotoMode, "direction", tilt.direction,
                "targetCount", tilt.goal);
            tilt.time(Stage.GOTO_MODE, () -> mount.setPositionMode(2, tilt.gotoMode, step));
            tilt.time(Stage.GOTO_TARGET, () -> mount.setAbsoluteTarget(2, tilt.goal));
            requireActive(active);
            tilt.startAck = tilt.time(Stage.START, () -> mount.startAxis(2, at -> {
                tilt.startSent = at;
                tilt.event("aim_start_sent", "atMs", at, "gpsAgeMs", at - tilt.fixReceived);
            }));
            tilt.outcome = "Start accepted";
            tilt.progress.accept("Aim at Surfer HS · Tilt Start accepted");
        } catch (IOException | RuntimeException failure) {
            tilt.fail(tilt.startSent == null ? "Blocked before Start" : "Start unconfirmed", failure);
        } finally {
            tilt.phaseFinished = GoTo.now();
            tilt.event("aim_tilt_dispatch_finished", "finishedMs", tilt.phaseFinished, "outcome", tilt.outcome,
                "issue", tilt.issue, "startAckMs", tilt.startAck);
        }
    }

    private static void requireActive(BooleanSupplier active) throws IOException {
        if (!active.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new IOException("Aim ended before motion command");
    }

    private static void watchPan(Attempt attempt, GoTo mount, int step, int baseline, long deadline,
                                  BooleanSupplier active) throws IOException {
        int lastCount = baseline;
        long displacement = 0;
        while (GoTo.now() < deadline && active.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
            attempt.encoderPolls++;
            GoTo.PanSample sample = mount.readPan();
            displacement += GoTo.delta(sample.count(), lastCount);
            lastCount = sample.count();
            attempt.lastSampleCount = lastCount;
            attempt.event("aim_pan_sample",
                "atMs",sample.timeMs(),"count",lastCount,
                "displacementCounts", displacement);
            if (attempt.firstMotion == null && displacement != 0) {
                attempt.firstMotion = sample.timeMs();
                attempt.event("aim_first_movement", "atMs", sample.timeMs(), "count", lastCount,
                    "displacementCounts", displacement);
            }
            if (sample.timeMs() >= deadline) break;
            if (step > 0 ? displacement >= step : displacement <= step) {
                attempt.crossing = sample.timeMs();
                attempt.event("aim_crossing", "atMs", sample.timeMs(), "count", lastCount,
                    "displacementCounts", displacement);
                attempt.outcome = "Target crossed";
                return;
            }
            pause(Math.min(deadline, sample.timeMs() + SAMPLE_MS));
        }
        attempt.outcome = GoTo.now() >= deadline ? "Timed out before target crossing" : "Aim interrupted";
    }

    private static void sendStop(Attempt attempt, GoTo mount) {
        long decision = attempt.crossing == null ? GoTo.now() : attempt.crossing;
        attempt.stopDecision = decision;
        String stopReason = attempt.outcome;
        try {
            attempt.stopAck = attempt.time(Stage.STOP, () -> mount.stopAxis(1, at -> {
                attempt.stopSent = at;
                attempt.event("aim_stop_sent", "atMs", at, "decisionToStopMs", at - decision, "reason", stopReason);
            }));
        } catch (IOException | RuntimeException failure) {
            attempt.addIssue(attempt.stopSent==null?"Stop send failed":"Stop reply unusable",failure);
        }
    }

    private static void confirmPanStop(Attempt attempt,GoTo mount,int cpr) {
        long checkStarted = GoTo.now();
        attempt.event("aim_pan_stop_check", "startedMs", checkStarted, "stopAckMs", attempt.stopAck,
            "stopAckToCheckMs", Attempt.since(checkStarted, attempt.stopAck));
        attempt.progress.accept("Aim at Surfer HS · Checking pan full stop");
        long deadline = (attempt.stopSent==null?attempt.stopDecision:attempt.stopSent)+STOP_TIMEOUT_MS;
        while (GoTo.now() < deadline) {
            attempt.statusPolls++;
            boolean moving;
            try { moving = mount.panMoving(); }
            catch (IOException | RuntimeException failure) {
                attempt.addIssue("Full stop unconfirmed", failure);
                return;
            }
            if (!moving) {
                attempt.stopped = attempt.lastReplyAt;
                try {
                    attempt.finalCount = attempt.time(Stage.FINAL_ENCODER, mount::readPan).count();
                    attempt.calculateGap(cpr);
                } catch (IOException | RuntimeException failure) {
                    attempt.addIssue("Final encoder unavailable", failure);
                }
                return;
            }
            if (Thread.currentThread().isInterrupted()) break;
            pause(Math.min(deadline, attempt.lastReplyAt + SAMPLE_MS));
        }
        attempt.addIssue("Full stop unconfirmed", new IOException("Status check ended before a stopped reply"));
    }

    private static void pause(long until) {
        long wait = until - GoTo.now();
        if (wait > 0) {
            try { Thread.sleep(wait); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        }
    }


    static final class Attempt implements GoTo.Trace {
        final long press, worker;
        final Consumer<String> progress;
        final EnumMap<Stage, Long> elapsed = new EnumMap<>(Stage.class);
        GoTo.Trace log;
        Stage stage;
        String outcome = "Aim blocked", issue;
        long fixReceived, lastReplyAt;
        Long packet, ageAtSelection, startSent, startAck, firstMotion, crossing, stopDecision, stopSent, stopAck, stopped;
        int encoderPolls, statusPolls;
        Integer baseline, goal, lastSampleCount, finalCount, errorCounts, coastCounts;
        Double finalGapM;
        Target target;
        GoTo.Reference reference;
        Integer panCpr, motorPeriod;
        Double sidereal, targetElevationM;
        Attempt tilt;
        String gotoMode;
        Integer direction;
        Long phaseStarted, phaseFinished;
        private Long replyMs;

        Attempt(long press, Consumer<String> progress) { this(press,Axis.PAN,progress); }

        Attempt(long press,Axis axis,Consumer<String> progress) {
            this.press = press;
            this.worker = GoTo.now();
            if (axis == Axis.TILT) outcome = "Not sent";
            this.progress = progress;
        }

        <T> T time(Stage next, Operation<T> operation) throws IOException {
            stage = next;
            long begin = GoTo.now();
            replyMs = null;
            try {
                T value = operation.run();
                recordStage(next, begin, null);
                return value;
            } catch (IOException | RuntimeException failure) {
                recordStage(next, begin, failure);
                throw failure;
            }
        }

        private void recordStage(Stage current, long begin, Throwable failure) {
            long finished = GoTo.now();
            elapsed.put(current, replyMs == null ? finished - begin : replyMs);
            event("aim_stage", "stage", current.name(), "startedMs", begin, "finishedMs", finished,
                "durationMs", finished - begin, "replyMs", replyMs, "failure", failure == null ? null : failure.getMessage());
        }

        void fail(String reason, Throwable failure) {
            outcome = reason;
            addIssue(stage == null ? "Aim" : stage.label, failure);
        }

        void skip(String reason) {
            issue = reason;
            event("aim_tilt_skipped", "reason", reason);
        }

        void addIssue(String label, Throwable failure) {
            String message = label + " · " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            issue = issue == null ? message : issue + "\n" + message;
            event("aim_issue", "stage", label, "reason", failure.toString());
        }

        void calculateGap(int cpr) throws IOException {
            finalGapM = time(Stage.GAP, () -> {
                errorCounts = GoTo.delta(finalCount, goal);
                coastCounts = lastSampleCount == null ? null : GoTo.delta(finalCount, lastSampleCount);
                return Geo.panGapM(target.rangeM(), errorCounts, cpr);
            });
        }

        void mountEvent(String event, Object... fields) {
            for (int i = 0; i < fields.length; i += 2) {
                if ("axis".equals(fields[i]) && Integer.valueOf(2).equals(fields[i + 1]) && tilt != null) {
                    tilt.event(event, fields);
                    return;
                }
            }
            event(event, fields);
        }

        @Override public void event(String event, Object... fields) {
            if (event.equals("gti_rx")) {
                for (int i = 0; i < fields.length; i += 2) {
                    if (fields[i].equals("rxMs")) lastReplyAt = (Long)fields[i + 1];
                    if (fields[i].equals("rttMs")) replyMs = (Long)fields[i + 1];
                }
            }
            if (log != null) log.event(event, fields);
        }

        String summary() {
            StringBuilder text = new StringBuilder("Aim at Surfer HS · ").append(outcome);
            if (issue != null) text.append('\n').append(issue);
            if (packet != null) text.append(String.format(Locale.US, "\nGPS #%d · ages at press / selection / Start: %s / %s / %s",
                packet, duration(press < fixReceived ? null : press - fixReceived), duration(ageAtSelection),
                duration(since(startSent, fixReceived))));
            if (target != null) text.append(String.format(Locale.US, "\nRange %.2f m · Target pan %d counts", target.rangeM(), target.count()));
            if (baseline != null) text.append("\nPan counts · ").append(baseline).append(" → ").append(goal)
                .append(" · Final ").append(finalCount == null ? "—" : finalCount);
            if (sidereal != null) text.append(String.format(Locale.US, "\nHS speed %.0f×", sidereal));
            text.append("\nFinal pan gap · ").append(finalGapM==null?"—":String.format(Locale.US,"%.2f m",finalGapM));
            if (tilt != null) text.append(tilt.tiltSummary());
            return text.toString();
        }

        private String tiltSummary() {
            StringBuilder text = new StringBuilder("\nTilt ").append(outcome);
            if (issue != null) text.append("\nTilt issue · ").append(issue);
            if (target != null) text.append(String.format(Locale.US,
                "\nTilt target · %+.2f° at %.2f m elevation · %d counts",
                target.angleDeg(), targetElevationM, target.count()));
            if (baseline != null) text.append("\nTilt baseline · ").append(baseline).append(" counts");
            if (startSent != null) text.append("\nFinal tilt position · Unconfirmed");
            return text.toString();
        }

        String timing(Long resultMs) {
            StringBuilder report = new StringBuilder("\n\nPan Aim timing\nButton queue · ")
                .append(duration(worker - press));
            for (Stage operation : Stage.values()) {
                if (operation != Stage.GOTO_MODE && operation != Stage.GOTO_TARGET)
                    addTiming(report, operation);
            }
            report.append("\nButton to pan Start send · ").append(duration(since(startSent, press)))
                .append("\nButton to pan Start reply · ").append(duration(since(startAck, press)))
                .append("\nStart to first movement / target crossing · ").append(duration(since(firstMotion, startSent)))
                .append(" / ").append(duration(since(crossing, startSent)))
                .append("\nEncoder polls · ").append(startAck == null ? "—" : encoderPolls)
                .append("\nDecision to Stop send · ").append(duration(since(stopSent, stopDecision)))
                .append("\nStop to observed full stop · ").append(duration(since(stopped, stopSent)))
                .append("\nStop status polls · ").append(stopDecision == null ? "—" : statusPolls)
                .append("\nButton to pan fully stopped · ").append(duration(since(stopped, press)))
                .append("\nButton to first successful Start reply · ").append(duration(since(firstStartReply(), press)))
                .append("\nButton to result · ").append(duration(resultMs));
            if (tilt != null) {
                report.append("\n\nTilt GoTo timing\nPan Stop reply to tilt dispatch · ")
                    .append(duration(since(tilt.phaseStarted, stopAck)));
                for (Stage operation : new Stage[] {Stage.STATUS, Stage.BASELINE, Stage.GOTO_MODE, Stage.GOTO_TARGET, Stage.START})
                    tilt.addTiming(report, operation);
                report.append("\nButton to tilt Start send · ").append(duration(since(tilt.startSent, press)))
                    .append("\nButton to tilt Start reply · ").append(duration(since(tilt.startAck, press)));
            }
            return report.toString();
        }

        private void addTiming(StringBuilder report, Stage operation) {
            report.append('\n').append(operation.label).append(" · ").append(duration(elapsed.get(operation)));
        }

        Long firstStartReply() {
            return startAck != null || tilt == null ? startAck : tilt.startAck;
        }

        static Long since(Long end, Long begin) { return end == null || begin == null ? null : end - begin; }
        private static String duration(Long value) { return value == null ? "—" : value + " ms"; }
    }
}
