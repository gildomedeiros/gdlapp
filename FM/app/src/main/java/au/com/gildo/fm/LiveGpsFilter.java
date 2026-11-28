package au.com.gildo.fm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** GP's filtered, revisioned output for one Live Tracking session. */
final class LiveGpsFilter {
    static final int DEFAULT_HOLD_FIXES = 4;
    static final int DEFAULT_PAN_LEAD_M = 35;
    static final int DEFAULT_STATIONARY_CONFIRMATION_MS = 2_000;
    private static final int REQUIRED_DIRECTION_FIXES = 3;
    private static final double STATIONARY_RADIUS_M = 1.5;
    private static final double STATIONARY_DEPARTURE_M = 1;
    private static final double SMALL_STEP_M = 1, BEARING_ROUNDING_DEG = 1e-7;

    enum State { MOVING, STATIONARY }

    record RuleStatus(int rule, Long packet, String summary) {
        String displayText() {
            return ruleId(rule) + (packet == null ? "" : " · GPS #" + packet) + "\n" + summary;
        }
    }

    record Snapshot(LiveGpsSource.Fix fix, LiveGpsSource.Direction direction, State state,
                    long revision, long publishedAt, long freshAt, List<String> rules,
                    GoTo.Point panTarget, double panLeadM) {
        long ageMs(long now) { return Math.max(0, now - freshAt); }
    }

    private final GoTo.Point mount;
    private final int maxHeld;
    private final double maxAgeMs;
    private final int stationaryConfirmationMs;
    private final double panLeadM;
    private final GoTo.Trace log;
    private LiveGpsSource.Fix previous;
    private LiveGpsSource.Fix stationaryAnchor;
    private GoTo.Point stationaryPosition;
    private Snapshot latest;
    private RuleStatus currentRule = new RuleStatus(4, null, "Waiting for a receiver fix");
    private Double firstBearing, stationaryBearing;
    private LiveGpsSource.Direction pending, lastLateralDirection;
    private int pendingCount, heldCount;
    private boolean leadActive;
    private boolean gap;
    private long revision;

    LiveGpsFilter(GoTo.Point mount, int maxHeld, double maxAgeMs, int stationaryConfirmationMs,
                  double panLeadM, GoTo.Trace log) {
        this.mount = mount;
        this.maxHeld = maxHeld;
        this.maxAgeMs = maxAgeMs;
        this.stationaryConfirmationMs = stationaryConfirmationMs;
        this.panLeadM = panLeadM;
        this.log = log;
    }

    Snapshot latest() { return latest; }
    RuleStatus currentRule() { return currentRule; }

    void resetInput() {
        previous = null;
        stationaryAnchor = null;
        stationaryPosition = null;
        latest = null;
        firstBearing = null;
        stationaryBearing = null;
        heldCount = 0;
        gap = false;
        leadActive = false;
        lastLateralDirection = null;
        clearDirectionEvidence();
        currentRule = new RuleStatus(4, null, "Waiting for a receiver fix");
    }

    void expire(long now) {
        if (!gap && previous != null && now - previous.receivedAt() > maxAgeMs) {
            gap = true;
            clearDirectionEvidence();
            cancelStationaryCheck(previous.packet().sequence, "GPS receipt gap");
            decision(4, previous.packet().sequence, "clear_direction_evidence", "GPS gap");
            showRule(4, previous.packet().sequence, "GPS gap. Waiting for a fresh fix.");
        }
    }

    void reject(Long packet, String reason) {
        decision(4, packet, "reject", reason);
    }

    void accept(LiveGpsSource.Fix input, long now) {
        long packet = input.packet().sequence;
        GoTo.Point point = input.target();
        double bearing;
        try {
            if (point == null) throw new IllegalArgumentException("Offset position has no mount bearing");
            bearing = bearing(point);
        } catch (IllegalArgumentException failure) {
            clearDirectionEvidence();
            gap = true;
            cancelStationaryCheck(packet, "Unusable GPS target");
            reject(packet, failure.getMessage());
            showRule(4, packet, failure.getMessage());
            return;
        }
        expire(input.receivedAt());
        if (previous == null) {
            previous = input;
            firstBearing = bearing;
            showRule(4, packet, "Waiting to establish a direction");
            decision(4, packet, "set_startup_reference", "Waiting for direction",
                "referenceLatitude", point.lat(), "referenceLongitude", point.lon(), "referenceBearingDeg", bearing);
            return;
        }

        GoTo.Point prior = previous.target();
        boolean continuous = !gap && ((packet - previous.packet().sequence) & 0xffffffffL) == 1;
        if (!continuous) {
            clearDirectionEvidence();
            cancelStationaryCheck(packet, "GPS sequence gap");
            decision(4, packet, "clear_direction_evidence", "GPS receipt or sequence gap");
        }
        gap = false;
        previous = input;
        decision(4, packet, "accept", "Valid distinct GPS input");
        boolean enteredStationary = continuous && stationaryBearing == null && confirmStationary(input, prior);
        if (enteredStationary) {
            leadActive = false;
            clearDirectionEvidence();
            decision(7, packet, "retain", "Stationary retains the published target and direction",
                "direction", directionOfLatest(), "state", State.STATIONARY.name());
            refresh(packet, State.STATIONARY, null, 2, 7);
            showRule(2, packet, "Confirmed within 1.5 m for " + stationaryConfirmationMs + " ms");
        }

        double publishedBearing = latest == null ? firstBearing : bearing(latest.fix().target());
        double priorBearing = bearing(prior);
        double reference = stationaryBearing == null ? priorBearing : stationaryBearing;
        LiveGpsSource.Direction candidate = direction(reference, bearing);
        boolean agreesWithLastStep = continuous && candidate != null && candidate == lastLateralDirection;
        lastLateralDirection = continuous && stationaryBearing == null ? candidate : null;
        boolean directionReady = stationaryBearing == null && confirmMovingDirection(packet, candidate, reference);

        double stepM = Geo.distance(prior.lat(), prior.lon(), point.lat(), point.lon());
        if (stepM <= SMALL_STEP_M && heldCount < maxHeld) {
            heldCount++;
            decision(0, packet, "hold", "Small change between consecutive GPS readings",
                "distanceM", stepM, "heldCount", heldCount, "holdLimit", maxHeld);
            refresh(packet, state(), input.receivedAt(), 0);
            if (!enteredStationary) {
                showRule(0, packet, "Holding a change of 1 m or less (" + heldCount + "/" + maxHeld + ")");
            }
            return;
        }
        decision(0, packet, "eligible", stepM > SMALL_STEP_M ? "Change over 1 m" : "Small-change hold limit reached",
            "distanceM", stepM, "heldCount", heldCount, "holdLimit", maxHeld);
        if (enteredStationary) return;

        if (stationaryBearing != null && candidate != null) {
            double departureM = Geo.distance(stationaryPosition.lat(), stationaryPosition.lon(), point.lat(), point.lon());
            if (departureM <= STATIONARY_DEPARTURE_M) {
                decision(3, packet, "hold", "Fix within 1 m of the Stationary reference",
                    "distanceFromStationaryM", departureM, "thresholdM", STATIONARY_DEPARTURE_M);
                refresh(packet, State.STATIONARY, input.receivedAt(), 3, 7);
                showRule(3, packet, "Holding a fix within 1 m of the Stationary reference");
                return;
            }
            decision(3, packet, "confirm", "First eligible bearing change beyond 1 m from Stationary",
                "referenceBearingDeg", stationaryBearing, "direction", candidate.name(),
                "distanceFromStationaryM", departureM, "thresholdM", STATIONARY_DEPARTURE_M);
            stationaryBearing = null;
            stationaryPosition = null;
            publish(input, candidate, now, 4, 0, 3, 6);
            // The first eligible departure also counts as a step for the next Rule 1 fix.
            if (continuous) lastLateralDirection = direction(priorBearing, bearing);
            showRule(3, packet, "Left Stationary. Published " + directionText(candidate) + " direction.");
        } else if (directionReady) {
            if (panLeadM > 0 && !leadActive && latest != null && latest.direction() == candidate && agreesWithLastStep) {
                double sidewaysM = Geo.distance(mount.lat(), mount.lon(), point.lat(), point.lon())
                    * Math.abs(Math.toRadians(Geo.delta(bearing, priorBearing)));
                leadActive = sidewaysM > SMALL_STEP_M;
            }
            publish(input, candidate, now, 4, 1, 0, 6);
            showRule(6, packet, "Published new target · " + directionText(candidate));
        } else if (candidate == null
                && direction(latest == null && stationaryBearing != null ? reference : publishedBearing, bearing) == null) {
            rangeOnly(input, now);
        } else if (stationaryBearing != null) {
            showRule(7, packet, "Keeping the last published target and direction");
        }
    }

    private boolean confirmStationary(LiveGpsSource.Fix input, GoTo.Point prior) {
        long packet = input.packet().sequence;
        GoTo.Point point = input.target();
        if (stationaryAnchor == null) {
            if (samePosition(prior, point)) {
                stationaryAnchor = input;
                decision(2, packet, "start_check", "Consecutive fixes repeat the same coordinates",
                    "referenceLatitude", point.lat(), "referenceLongitude", point.lon(),
                    "startedMs", input.receivedAt(), "confirmationMs", stationaryConfirmationMs,
                    "radiusM", STATIONARY_RADIUS_M);
            }
            return false;
        }

        GoTo.Point anchor = stationaryAnchor.target();
        double distanceM = Geo.distance(anchor.lat(), anchor.lon(), point.lat(), point.lon());
        if (distanceM > STATIONARY_RADIUS_M) {
            cancelStationaryCheck(packet, "Fix outside the stationary radius");
            return false;
        }
        long elapsedMs = input.receivedAt() - stationaryAnchor.receivedAt();
        if (elapsedMs < stationaryConfirmationMs) return false;

        stationaryPosition = anchor;
        stationaryBearing = bearing(anchor);
        stationaryAnchor = null;
        decision(2, packet, "stationary", "Consecutive fixes stayed within the stationary radius",
            "referenceLatitude", anchor.lat(), "referenceLongitude", anchor.lon(),
            "referenceBearingDeg", stationaryBearing, "elapsedMs", elapsedMs,
            "confirmationMs", stationaryConfirmationMs, "radiusM", STATIONARY_RADIUS_M);
        return true;
    }

    private void cancelStationaryCheck(long packet, String reason) {
        if (stationaryAnchor == null) return;
        decision(2, packet, "cancel_check", reason, "anchorPacket", stationaryAnchor.packet().sequence);
        stationaryAnchor = null;
    }

    private boolean confirmMovingDirection(long packet, LiveGpsSource.Direction candidate, double reference) {
        if (candidate == null) {
            clearDirectionEvidence();
            decision(1, packet, "clear_direction_evidence", "The bearing is unchanged",
                "referenceBearingDeg", reference, "count", 0);
            return false;
        }
        if (latest != null && latest.direction() == candidate) {
            clearDirectionEvidence();
            decision(1, packet, "continue", "Direction already published",
                "candidateDirection", candidate.name(), "referenceBearingDeg", reference, "count", 0);
            return true;
        }
        pendingCount = pending == candidate ? Math.min(pendingCount + 1, REQUIRED_DIRECTION_FIXES) : 1;
        pending = candidate;
        boolean ready = pendingCount == REQUIRED_DIRECTION_FIXES;
        showRule(1, packet, "Confirming " + directionText(candidate) + " direction ("
            + pendingCount + "/" + REQUIRED_DIRECTION_FIXES + ")");
        decision(1, packet, ready ? "confirm" : "hold", "Three consecutive bearing changes in the same direction",
            "candidateDirection", candidate.name(), "referenceBearingDeg", reference, "count", pendingCount);
        return ready;
    }

    private void rangeOnly(LiveGpsSource.Fix input, long now) {
        long packet = input.packet().sequence;
        if (latest == null) {
            decision(5, packet, "hold", "GP has yet to publish a direction");
            showRule(5, packet, "Bearing unchanged. Waiting for a fix that establishes direction.");
        } else if (samePosition(latest.fix().target(), input.target())) {
            decision(5, packet, "refresh", "Coordinates unchanged");
            refresh(packet, state(), input.receivedAt(), 5);
            showRule(5, packet, "Coordinates unchanged. Keeping the published target.");
        } else {
            decision(5, packet, "retain_direction", "Range change at the published bearing",
                "direction", latest.direction().name());
            publish(input, latest.direction(), now, 4, 0, 5, 6);
            showRule(5, packet, "Published a range change. Kept " + directionText(latest.direction()) + " direction.");
        }
    }

    private State state() { return stationaryBearing == null ? State.MOVING : State.STATIONARY; }
    private String directionOfLatest() { return latest == null ? null : latest.direction().name(); }

    private void publish(LiveGpsSource.Fix input, LiveGpsSource.Direction direction, long now, int... rules) {
        if (latest != null && latest.direction() != direction) leadActive = false;
        double appliedLeadM = leadActive ? panLeadM : 0;
        GoTo.Point panTarget = input.target();
        if (appliedLeadM > 0) {
            double signedLeadM = direction == LiveGpsSource.Direction.CLOCKWISE ? appliedLeadM : -appliedLeadM;
            panTarget = LiveGpsSource.rotateAroundMount(mount, panTarget, signedLeadM);
        }
        latest = new Snapshot(input, direction, state(), ++revision, now, input.receivedAt(), ruleIds(rules),
            panTarget, appliedLeadM);
        heldCount = 0;
        clearDirectionEvidence();
        decision(6, input.packet().sequence, "publish", "New position and GP direction",
            "direction", direction.name(), "state", latest.state().name());
    }

    private void refresh(long packet, State state, Long freshAt, int... rules) {
        if (latest == null) return;
        long time = freshAt == null ? latest.freshAt() : Math.max(freshAt, latest.freshAt());
        if (state == latest.state() && time == latest.freshAt()) return;
        latest = new Snapshot(latest.fix(), latest.direction(), state, latest.revision(), latest.publishedAt(),
            time, ruleIds(rules), latest.panTarget(), latest.panLeadM());
        decision(6, packet, "refresh", "Position revision and direction retained", "state", state.name());
    }

    private double bearing(GoTo.Point point) {
        return Geo.bearing(mount.lat(), mount.lon(), point.lat(), point.lon());
    }

    private static boolean samePosition(GoTo.Point a, GoTo.Point b) {
        return a.lat() == b.lat() && a.lon() == b.lon();
    }

    private static LiveGpsSource.Direction direction(double from, double to) {
        double delta = Geo.delta(to, from);
        if (Math.abs(delta) < BEARING_ROUNDING_DEG) return null;
        return delta > 0 ? LiveGpsSource.Direction.CLOCKWISE : LiveGpsSource.Direction.COUNTERCLOCKWISE;
    }

    private void clearDirectionEvidence() {
        pending = null;
        pendingCount = 0;
    }

    private void showRule(int rule, long packet, String summary) {
        currentRule = new RuleStatus(rule, packet, state() == State.STATIONARY ? "Stationary · " + summary : summary);
    }

    private static String directionText(LiveGpsSource.Direction direction) {
        return direction.name().toLowerCase(Locale.ROOT);
    }

    private static String ruleId(int rule) { return "GPS_DATA_FILTER_RULE_" + rule; }

    private static List<String> ruleIds(int[] rules) {
        List<String> ids = new ArrayList<>(rules.length);
        for (int rule : rules) ids.add(ruleId(rule));
        return Collections.unmodifiableList(ids);
    }

    private void decision(int rule, Long packet, String action, String reason, Object... detail) {
        Object[] fields = {"ruleId", ruleId(rule), "packet", packet, "action", action, "reason", reason,
            "positionRevision", latest == null ? null : latest.revision()};
        Object[] all = Arrays.copyOf(fields, fields.length + detail.length);
        System.arraycopy(detail, 0, all, fields.length, detail.length);
        log.event("live_gps_filter_decision", all);
    }
}

