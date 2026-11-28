package au.com.gildo.fm;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class GpsPadSimulatorTest {
    private static final GoTo.Point LANDMARK = new GoTo.Point(-28.0792, 153.4011, 0);

    @Test public void allEightDirectionsMoveTheSameDistanceFromTheLastPosition() {
        for (GpsPadSimulator.Move move : GpsPadSimulator.Move.values()) {
            var simulator = newSimulator();
            var start = receive(simulator, 1000);
            simulator.move(move, 2.5);
            var moved = receive(simulator, 1500);
            assertEquals(move.name(), 0, Geo.delta(bearing(start, moved), move.bearing), .0001);
            assertEquals(move.name(), 2.5, distance(start, moved), .0001);

            simulator.move(move, 2.5);
            assertEquals(move.name(), 2.5, distance(moved, receive(simulator, 2000)), .0001);
        }
        assertEquals(8, GpsPadSimulator.Move.values().length);
    }

    @Test public void firstFixIsTheLandmarkAndRepeatedPositionsAdvanceThePacketClock() {
        var simulator = newSimulator();
        simulator.move(GpsPadSimulator.Move.UP_LEFT, 2);
        var start = receive(simulator, 1000);
        assertEquals(LANDMARK.lat(), start.lat, 0);
        assertEquals(LANDMARK.lon(), start.lon, 0);
        assertNull(simulator.poll(1499));
        var next = receive(simulator, 1500);
        var repeated = receive(simulator, 2000);
        assertEquals(2, next.sequence);
        assertEquals(3, repeated.sequence);
        assertEquals(500, repeated.senderMs - next.senderMs);
        assertEquals(0, distance(next, repeated), 0);
    }

    @Test public void fastTapsAreIndividuallyNumberedBetweenSamples() {
        var simulator = newSimulator();
        assertEquals(0, simulator.poll(1000).lastTap());

        var first = simulator.move(GpsPadSimulator.Move.UP, 2);
        var second = simulator.move(GpsPadSimulator.Move.UP_RIGHT, 2);
        assertEquals(1, first.number());
        assertEquals(2, second.number());
        assertNull(simulator.poll(1499));

        var sample = simulator.poll(1500);
        var fix = LoRaTelemetry.parse(sample.line());
        assertEquals(2, sample.lastTap());
        assertEquals(second.position().lat(), fix.lat, 0);
        assertEquals(second.position().lon(), fix.lon, 0);
    }

    @Test public void simulatorUsesNormalGpPublishingAndSourceSwitchClearsDirectionEvidence() {
        var mount = Geo.destination(LANDMARK, 180, 100);
        var simulator = newSimulator();
        var tracker = new LoRaTelemetry.Tracker();
        var filter = new LiveGpsFilter(mount, 4, 2000, LiveGpsFilter.DEFAULT_STATIONARY_CONFIRMATION_MS, 0,
            (event, fields) -> {});
        var source = new LiveGpsSource(1000);
        source.advance(1000, 2);

        for (long time = 1000; time <= 3500; time += 500)
            feed(simulator, tracker, source, filter, mount, time);
        assertNull(filter.latest());
        assertEquals(2, filter.currentRule().rule());

        simulator.move(GpsPadSimulator.Move.UP_RIGHT, 3);
        var moved = feed(simulator, tracker, source, filter, mount, 4000);
        var published = filter.latest();
        assertEquals(1, published.revision());
        assertEquals(LiveGpsSource.Direction.CLOCKWISE, published.direction());
        assertEquals(moved.lat, published.fix().target().lat(), 0);
        assertEquals(moved.lon, published.fix().target().lon(), 0);
        assertTrue(published.fix().simulated());

        filter.resetInput();
        assertNull(filter.latest());
        simulator = newSimulator();
        tracker = new LoRaTelemetry.Tracker();
        for (long time = 4500; time <= 7000; time += 500)
            feed(simulator, tracker, source, filter, mount, time);
        simulator.move(GpsPadSimulator.Move.UP_LEFT, 3);
        feed(simulator, tracker, source, filter, mount, 7500);
        assertEquals(2, filter.latest().revision());
        assertEquals(LiveGpsSource.Direction.COUNTERCLOCKWISE, filter.latest().direction());
    }

    @Test public void modeStartsAreEmittedOnceAtTheExistingCadenceAndUseTheLastPosition() {
        var simulator = newSimulator();
        simulator.selectMode(SurferModes.Mode.SITTING, 90, 900, 0);
        var initial = simulator.poll(1000);
        assertNull(initial.modeStart());
        assertEquals(LANDMARK.lon(), LoRaTelemetry.parse(initial.line()).lon, 0);

        var sitting = simulator.poll(1500);
        assertEquals(Long.valueOf(900), sitting.modeStart().selectedAt());
        assertEquals(SurferModes.Mode.SITTING, sitting.mode());
        var from = LoRaTelemetry.parse(sitting.line());
        assertTrue(simulator.selectMode(SurferModes.Mode.TAKEOFF, 90, 1600, 3000));
        assertTrue(simulator.selectMode(SurferModes.Mode.RIDING, 90, 1750, 3000));
        assertNull(simulator.poll(1999));
        var riding = simulator.poll(2000);
        assertEquals(Long.valueOf(1750), riding.modeStart().selectedAt());
        assertEquals(SurferModes.Mode.RIDING, riding.mode());
        assertFalse(riding.firstPlaybackFix());
        var moved = LoRaTelemetry.parse(riding.line());
        assertEquals(from.sequence + 1, moved.sequence);
        assertEquals(1.964, distance(from, moved), .0001);
        assertFalse(simulator.selectMode(SurferModes.Mode.RIDING, 90, 2050, 3000));
        assertNull(simulator.poll(2500).modeStart());

        var tap = simulator.move(GpsPadSimulator.Move.UP, 2);
        var manual = simulator.poll(3000);
        assertNull(manual.mode());
        assertNull(manual.modeStart());
        assertEquals(tap.number(), manual.lastTap());
        assertEquals(tap.position().lat(), LoRaTelemetry.parse(manual.line()).lat, 0);
    }

    @Test public void allMovingModesDefaultToThreeSecondsAndReturnAtTheLastFix() {
        for (var mode : SurferModes.Mode.values()) {
            if (mode == SurferModes.Mode.SITTING) continue;
            var simulator = newSimulator();
            simulator.poll(1000);
            long limitMs = SurferModes.continuousDurationMs(SurferModes.DEFAULT_CONTINUOUS_SECONDS);
            assertEquals(3000, limitMs);
            simulator.selectMode(mode, 90, 1020, limitMs);
            var moving = simulator.poll(1500);
            assertEquals(Long.valueOf(1020), moving.modeStart().selectedAt());
            assertNull(moving.modeStart().timedOutMode());
            assertEquals(3000, moving.modeStart().limitMs());
            for (long time = 2000; time <= 4000; time += 500) {
                moving = simulator.poll(time);
                assertEquals(mode, moving.mode());
                assertNull(moving.modeStart());
            }
            assertNull(simulator.poll(4499));
            var sitting = simulator.poll(4500);
            assertEquals(SurferModes.Mode.SITTING, sitting.mode());
            assertEquals(SurferModes.Mode.SITTING, simulator.mode());
            assertEquals(mode, sitting.modeStart().timedOutMode());
            assertNull(sitting.modeStart().selectedAt());
            assertEquals(3000, sitting.modeStart().limitMs());
            var lastMoving = LoRaTelemetry.parse(moving.line());
            var firstSitting = LoRaTelemetry.parse(sitting.line());
            assertEquals(lastMoving.sequence + 1, firstSitting.sequence);
            assertEquals(lastMoving.lat, firstSitting.lat, 0);
            assertEquals(lastMoving.lon, firstSitting.lon, 0);
            for (long time = 5000; time <= 11000; time += 500) {
                var idle = simulator.poll(time);
                assertEquals(SurferModes.Mode.SITTING, idle.mode());
                assertNull(idle.modeStart());
                assertTrue(distance(firstSitting, LoRaTelemetry.parse(idle.line())) < 1);
            }
        }
    }

    @Test public void selectingAnotherMovingModeReplacesThePreviousCountdown() {
        var simulator = newSimulator();
        simulator.poll(1000);
        var slow = SurferModes.Mode.SLOW;
        var ride = SurferModes.Mode.RIDING;
        // Keep the preference names used by builds that offered these durations in Settings.
        var saved = Map.of("Simulator mode 2 duration (s)", "2", "Simulator mode 6 duration (s)", "4.25");
        long slowLimit = SurferModes.continuousDurationMs(saved.getOrDefault(slow.durationPreference(),
            SurferModes.DEFAULT_CONTINUOUS_SECONDS));
        long rideLimit = SurferModes.continuousDurationMs(saved.getOrDefault(ride.durationPreference(),
            SurferModes.DEFAULT_CONTINUOUS_SECONDS));
        assertEquals(2000, slowLimit);
        assertEquals(4250, rideLimit);
        simulator.selectMode(slow, 90, 1100, slowLimit);
        for (long time = 1500; time <= 3000; time += 500) simulator.poll(time);
        simulator.selectMode(ride, 90, 3050, rideLimit);
        var switched = simulator.poll(3500);
        assertEquals(ride, switched.mode());
        assertEquals(Long.valueOf(3050), switched.modeStart().selectedAt());
        assertEquals(4250, switched.modeStart().limitMs());
        for (long time = 4000; time <= 7500; time += 500)
            assertEquals(ride, simulator.poll(time).mode());
        assertNull(simulator.poll(7750));
        var expired = simulator.poll(8000).modeStart();
        assertEquals(ride, expired.timedOutMode());
        assertEquals(4250, expired.limitMs());
    }

    @Test public void continuousDurationsMustBePositiveAndRepresentableInMilliseconds() {
        assertEquals(1250, SurferModes.continuousDurationMs("1,25"));
        for (String invalid : new String[] {"", "0", "-3", "0.0001", "1e99"})
            assertThrows(IllegalArgumentException.class, () -> SurferModes.continuousDurationMs(invalid));
    }

    @Test public void switchingFromSittingPublishesThroughTheExistingStationaryFilterState() {
        var mount = Geo.destination(LANDMARK, 180, 100);
        var simulator = newSimulator();
        var tracker = new LoRaTelemetry.Tracker();
        var filter = new LiveGpsFilter(mount, 4, 2000, LiveGpsFilter.DEFAULT_STATIONARY_CONFIRMATION_MS, 0,
            (event, fields) -> {});
        var source = new LiveGpsSource(1000);

        simulator.selectMode(SurferModes.Mode.SITTING, 90, 900, 0);
        for (long time = 1000; time <= 4000; time += 500)
            feed(simulator, tracker, source, filter, mount, time);
        assertNull(filter.latest());
        assertEquals(2, filter.currentRule().rule());

        simulator.selectMode(SurferModes.Mode.RIDING, 90, 4200, 3000);
        feed(simulator, tracker, source, filter, mount, 4500);
        // The first riding fix is still within 1 m of the saved sitting position.
        assertNull(filter.latest());
        assertEquals(3, filter.currentRule().rule());
        var departure = feed(simulator, tracker, source, filter, mount, 5000);
        assertEquals(departure.sequence, filter.latest().fix().packet().sequence);
        assertEquals(LiveGpsSource.Direction.CLOCKWISE, filter.latest().direction());
        assertTrue(filter.latest().rules().contains("GPS_DATA_FILTER_RULE_3"));
    }

    private static LoRaTelemetry feed(GpsPadSimulator simulator, LoRaTelemetry.Tracker tracker,
                                      LiveGpsSource source, LiveGpsFilter filter, GoTo.Point mount, long time) {
        var gps = receive(simulator, time);
        assertTrue(tracker.accept(gps, time));
        filter.accept(source.toFilterInput(gps, time, mount, 0, true), time);
        return gps;
    }

    @Test public void defaultOneHzAndRateSwitchPreserveEveryPlaybackRow() {
        var simulator = new GpsPadSimulator(LANDMARK.lat(), LANDMARK.lon());
        assertEquals(LANDMARK.lat(), receive(simulator, 1000).lat, 0);
        assertNull(simulator.poll(1999));
        assertEquals(LANDMARK.lon(), receive(simulator, 2000).lon, 0);
        var target = Geo.destination(LANDMARK, 90, 3);
        simulator.apply(java.util.List.of(target, target, LANDMARK), 2100, false);
        var first = simulator.poll(2100);
        assertEquals(target.lon(), LoRaTelemetry.parse(first.line()).lon, 0);
        assertNull(simulator.poll(2599));
        simulator.setRateHz(2);
        var repeat = simulator.poll(2600);
        assertEquals(2, repeat.playbackFix());
        assertEquals(target.lon(), LoRaTelemetry.parse(repeat.line()).lon, 0);
        simulator.setRateHz(1);
        assertNull(simulator.poll(3599));
        assertEquals(3, simulator.poll(3600).playbackFix());
    }

    private static GpsPadSimulator newSimulator() {
        var simulator = new GpsPadSimulator(LANDMARK.lat(), LANDMARK.lon());
        simulator.setRateHz(2);
        return simulator;
    }

    private static LoRaTelemetry receive(GpsPadSimulator simulator, long now) {
        return LoRaTelemetry.parse(simulator.poll(now).line());
    }

    private static double bearing(LoRaTelemetry from, LoRaTelemetry to) {
        return Geo.bearing(from.lat, from.lon, to.lat, to.lon);
    }

    private static double distance(LoRaTelemetry from, LoRaTelemetry to) {
        return Geo.distance(from.lat, from.lon, to.lat, to.lon);
    }
}
