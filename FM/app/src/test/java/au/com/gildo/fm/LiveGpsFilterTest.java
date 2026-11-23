package au.com.gildo.fm;

import java.util.ArrayList;
import java.util.Locale;
import org.junit.Test;

import static au.com.gildo.fm.LiveGpsSource.Direction.*;
import static org.junit.Assert.*;

public class LiveGpsFilterTest {
    private static final GoTo.Point MOUNT = new GoTo.Point(-28.079233, 153.401026, 4);
    private final LiveGpsSource source = new LiveGpsSource(0);
    private final ArrayList<String> decisions = new ArrayList<>();
    private LiveGpsFilter filter = newFilter(LiveGpsFilter.DEFAULT_STATIONARY_CONFIRMATION_MS);

    private LiveGpsFilter newFilter(int confirmationMs) {
        return new LiveGpsFilter(MOUNT, 4, 2000, confirmationMs, (event, fields) -> {
            if (event.equals("live_gps_filter_decision")) decisions.add(fields[1] + " " + fields[5]);
        });
    }

    @Test public void startupAndMovingReversalRequireThreeConsecutiveFixes() {
        input(1, 0, 100);
        input(2, 2, 100);
        input(3, 4, 100);
        assertNull(filter.latest());
        input(4, 6, 100);
        assertEquals(CLOCKWISE, filter.latest().direction());
        assertEquals(4, filter.latest().fix().packet().sequence);

        input(5, 5, 100);
        input(6, 4, 100);
        assertEquals(CLOCKWISE, filter.latest().direction());
        assertEquals(1, filter.latest().revision());
        input(7, 3, 100);
        assertEquals(COUNTERCLOCKWISE, filter.latest().direction());
        assertEquals(2, filter.latest().revision());
        assertTrue(decisions.contains("GPS_DATA_FILTER_RULE_1 confirm"));
        assertTrue(decisions.contains("GPS_DATA_FILTER_RULE_6 publish"));
    }

    @Test public void forwardSpikeDoesNotReverseThreeCorrectedClockwiseFixes() {
        startClockwise();
        input(5, 22, 100);
        input(6, 8, 100);
        assertRule(1, 6, "counterclockwise direction (1/3)");
        assertEquals(5, filter.latest().fix().packet().sequence);

        input(7, 10, 100);
        assertEquals(7, filter.latest().fix().packet().sequence);
        assertEquals(CLOCKWISE, filter.latest().direction());
        input(8, 12, 100);
        assertEquals(8, filter.latest().fix().packet().sequence);
        assertEquals(CLOCKWISE, filter.latest().direction());
    }

    @Test public void aReversalCanConfirmAtTheOldTargetAfterHeldSmallMoves() {
        startClockwise();
        input(5, 6.5, 100);
        input(6, 7, 100);
        input(7, 7.5, 100);
        input(8, 7, 100);
        input(9, 6.5, 100);
        assertEquals(4, filter.latest().fix().packet().sequence);
        assertRule(1, 9, "counterclockwise direction (2/3)");

        input(10, 6, 100);
        assertEquals(10, filter.latest().fix().packet().sequence);
        assertEquals(COUNTERCLOCKWISE, filter.latest().direction());
        assertEquals(2, filter.latest().revision());
    }

    @Test public void stationaryKeepsThePublishedTargetThenOneFixMayReverse() {
        startClockwise();
        LiveGpsFilter.Snapshot atMove = filter.latest();
        for (int seq = 5; seq <= 8; seq++) {
            input(seq, 6, 100);
            assertEquals(LiveGpsFilter.State.MOVING, filter.latest().state());
        }
        input(9, 6.7, 100);
        assertEquals(LiveGpsFilter.State.STATIONARY, filter.latest().state());
        assertSame(atMove.fix(), filter.latest().fix());
        assertEquals(CLOCKWISE, filter.latest().direction());
        assertEquals(atMove.revision(), filter.latest().revision());
        // Rule 3 can use an eligible fix inside the entry radius, measured against the original anchor.
        input(10, 5.3, 100);
        assertEquals(LiveGpsFilter.State.MOVING, filter.latest().state());
        assertEquals(COUNTERCLOCKWISE, filter.latest().direction());
        assertEquals(2, filter.latest().revision());
        assertTrue(decisions.contains("GPS_DATA_FILTER_RULE_2 stationary"));
        assertTrue(decisions.contains("GPS_DATA_FILTER_RULE_7 retain"));
        assertTrue(decisions.contains("GPS_DATA_FILTER_RULE_3 confirm"));
    }

    @Test public void firstStationaryDeparturePublishesPositionAndDirectionTogether() {
        for (int seq = 1; seq <= 6; seq++) input(seq, 180, 100);
        assertNull(filter.latest());
        input(7, 182, 100);
        assertEquals(CLOCKWISE, filter.latest().direction());
        assertEquals(7, filter.latest().fix().packet().sequence);
    }

    @Test public void singleRepeatDuringMovementKeepsThreeFixReversalRule() {
        startClockwise();
        input(5, 6, 100);
        input(6, 4, 100);
        input(7, 2, 100);
        assertEquals(LiveGpsFilter.State.MOVING, filter.latest().state());
        assertEquals(CLOCKWISE, filter.latest().direction());
        input(8, 0, 100);
        assertEquals(COUNTERCLOCKWISE, filter.latest().direction());
        assertTrue(decisions.contains("GPS_DATA_FILTER_RULE_2 cancel_check"));
        assertFalse(decisions.contains("GPS_DATA_FILTER_RULE_3 confirm"));
    }

    @Test public void smallChangesCanLeaveTheFixedStationaryRadius() {
        startClockwise();
        input(5, 6, 100);
        input(6, 6, 100.4);
        input(7, 6, 100.8);
        input(8, 6, 101.2);
        input(9, 6, 101.6);
        assertEquals(LiveGpsFilter.State.MOVING, filter.latest().state());
        assertTrue(decisions.contains("GPS_DATA_FILTER_RULE_2 cancel_check"));
    }

    @Test public void stationaryCheckStartsOverAfterASequenceGap() {
        startClockwise();
        input(5, 6, 100);
        input(6, 6, 100);
        for (int seq = 8; seq <= 12; seq++) {
            input(seq, 6, 100);
            assertEquals(LiveGpsFilter.State.MOVING, filter.latest().state());
        }
        input(13, 6, 100);
        assertEquals(LiveGpsFilter.State.STATIONARY, filter.latest().state());
        assertTrue(decisions.contains("GPS_DATA_FILTER_RULE_2 cancel_check"));
    }

    @Test public void stationaryCheckUsesTheConfiguredDuration() {
        filter = newFilter(750);
        startClockwise();
        input(5, 6, 100);
        input(6, 6, 100);
        assertEquals(LiveGpsFilter.State.MOVING, filter.latest().state());
        input(7, 6, 100);
        assertEquals(LiveGpsFilter.State.STATIONARY, filter.latest().state());
    }

    @Test public void fourHalfMetreMovesHoldCoordinatesAndRefreshAgeBeforeTheFifth() {
        startClockwise();
        double halfMetre = Math.toDegrees(.5 / 100);
        for (int i = 1; i <= 4; i++) {
            input(4 + i, 6 + i * halfMetre, 100);
            assertEquals(1, filter.latest().revision());
            assertEquals(4, filter.latest().fix().packet().sequence);
            assertEquals((4 + i) * 500L, filter.latest().freshAt());
        }
        input(9, 6 + 5 * halfMetre, 100);
        assertEquals(2, filter.latest().revision());
        assertEquals(9, filter.latest().fix().packet().sequence);
        assertEquals(CLOCKWISE, filter.latest().direction());
        assertTrue(decisions.contains("GPS_DATA_FILTER_RULE_0 hold"));
    }

    @Test public void radialMovementKeepsThePublishedDirection() {
        startClockwise();
        input(5, 6, 103);
        assertEquals(2, filter.latest().revision());
        assertEquals(CLOCKWISE, filter.latest().direction());
        assertTrue(decisions.contains("GPS_DATA_FILTER_RULE_5 retain_direction"));
    }

    @Test public void gapsClearPendingReversalEvidence() {
        startClockwise();
        input(5, 5, 100);
        input(6, 4, 100);
        filter.expire(5001);
        input(11, 3, 100);
        assertEquals(1, filter.latest().revision());
        input(12, 2, 100);
        assertEquals(1, filter.latest().revision());
        input(13, 1, 100);
        assertEquals(COUNTERCLOCKWISE, filter.latest().direction());
        assertTrue(decisions.contains("GPS_DATA_FILTER_RULE_4 clear_direction_evidence"));
    }

    @Test public void currentRuleExplainsWaitingHoldingAndPublishing() {
        assertEquals("GPS_DATA_FILTER_RULE_4\nWaiting for a receiver fix", filter.currentRule().displayText());
        input(1, 0, 100);
        input(2, 0, 100);
        input(3, 0, 100);
        assertRule(0, 3, "(2/4)");
        input(4, 0, 100);
        input(5, 0, 100);
        input(6, 0, 100);
        assertRule(2, 6, "Stationary");
        input(7, 0, 100);
        assertRule(5, 7, "Waiting for a fix that establishes direction");
        filter.reject(null, "Not a GPS packet");
        assertRule(5, 7, "Waiting for a fix that establishes direction");

        input(8, 2, 100);
        assertRule(3, 8, "Published clockwise direction");
        input(9, 0, 100);
        assertRule(1, 9, "counterclockwise direction (1/3)");
        input(10, 358, 100);
        assertRule(1, 10, "counterclockwise direction (2/3)");
        input(11, 356, 100);
        assertRule(6, 11, "Published new target · counterclockwise");
        filter.expire(7600);
        assertRule(4, 11, "Waiting for a fresh fix");
    }

    private void assertRule(int rule, long packet, String summary) {
        var status = filter.currentRule();
        assertEquals(rule, status.rule());
        assertEquals(Long.valueOf(packet), status.packet());
        assertTrue(status.displayText().contains(summary));
    }

    private void startClockwise() {
        for (int i = 1; i <= 4; i++) input(i, (i - 1) * 2, 100);
        assertEquals(CLOCKWISE, filter.latest().direction());
    }

    private void input(int seq, double bearing, double range) {
        GoTo.Point p = Geo.destination(MOUNT, bearing, range);
        long time = seq * 500L;
        LoRaTelemetry packet = LoRaTelemetry.parse(String.format(Locale.ROOT,
            "RX,GPS,%d,%d,%.12f,%.12f,8,1.15,-85,7.0,0", seq, time, p.lat(), p.lon()));
        filter.accept(source.toFilterInput(packet, time, MOUNT, 2), time);
    }
}

