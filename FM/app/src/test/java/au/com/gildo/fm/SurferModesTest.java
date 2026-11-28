package au.com.gildo.fm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import org.junit.Test;

import static au.com.gildo.fm.SurferModes.Mode.*;
import static org.junit.Assert.*;

public class SurferModesTest {
    private static final GoTo.Point LANDMARK = new GoTo.Point(-28.0792, 153.4011, Double.NaN);

    @Test public void recordedSittingFixesRoundTripInOrderWithRepeats() throws IOException {
        var fixes = SurferModes.generate(LANDMARK, 90, List.of(SITTING), 5, 2);
        assertEquals(LANDMARK, fixes.get(0));
        assertEquals(11, fixes.size());
        assertEquals(.982, distance(LANDMARK, fixes.get(1)), .00001);
        assertEquals(fixes.get(1), fixes.get(4));
        assertEquals(LANDMARK, fixes.get(5));
        assertEquals(fixes, SurferModes.decode(new BufferedReader(new StringReader(SurferModes.encode(fixes)))));
    }

    @Test public void waveRideDurationScalesPathAndKeepsHalfSecondFixes() {
        var five = SurferModes.generate(LANDMARK, 90, List.of(RIDING), 5, 2);
        var seven = SurferModes.generate(LANDMARK, 90, List.of(RIDING), 7, 2);
        assertEquals(5000, (five.size() - 1) * 500L);
        assertEquals(7000, (seven.size() - 1) * 500L);
        assertEquals(22.591, distance(LANDMARK, five.get(10)), .00001);
        assertEquals(22.591 * 7 / 5, distance(LANDMARK, seven.get(14)), .00001);
    }

    @Test public void selectedModesAppendWithoutResetAndPaddlingTurnChangesDirection() {
        var fixes = SurferModes.generate(LANDMARK, 90, List.of(PADDLE_TURN, TAKEOFF), 5, 2);
        assertTrue(fixes.get(1).lon() < LANDMARK.lon());
        assertTrue(fixes.get(5).lon() < fixes.get(1).lon());
        assertTrue(fixes.get(10).lon() > LANDMARK.lon());
        assertEquals(fixes.get(10), fixes.get(11));
        assertTrue(fixes.get(fixes.size() - 1).lon() > fixes.get(11).lon());
    }

    @Test public void preparedFixesUseNormalGpsCadenceThenPadCanTakeOver() {
        var simulator = new GpsPadSimulator(LANDMARK.lat(), LANDMARK.lon());
        simulator.setRateHz(2);
        simulator.poll(1000);
        simulator.move(GpsPadSimulator.Move.UP, 20);
        var fixes = SurferModes.generate(LANDMARK, 90, List.of(RIDING), 1, 2);
        var playback = simulator.apply(fixes, 1100, true);
        var first = simulator.poll(1120);
        assertEquals(1, first.playbackFix());
        assertSame(playback, first.playback());
        assertEquals(LANDMARK.lat(), LoRaTelemetry.parse(first.line()).lat, 0);
        assertNull(simulator.poll(1619));
        assertEquals(2, simulator.poll(1630).playbackFix());
        assertEquals(3, simulator.poll(2120).playbackFix());
        assertTrue(simulator.recordFirstReply(playback, 2200));
        assertFalse(simulator.recordFirstReply(playback, 2300));
        assertTrue(simulator.playbackStatus().contains("1100 ms"));
        assertEquals(0, simulator.poll(2620).playbackFix());
        var tap = simulator.move(GpsPadSimulator.Move.UP, 2);
        var manual = simulator.poll(3120);
        assertNull(manual.playback());
        assertEquals(tap.number(), manual.lastTap());
        assertEquals(tap.position().lat(), LoRaTelemetry.parse(manual.line()).lat, 0);
    }

    @Test public void continuousProfilesContinueBeyondOnePatternWithoutResettingPosition() {
        var sitting = new SurferModes.Continuous(SITTING, LANDMARK, 90);
        for (int i = 0; i < 80; i++) assertTrue(distance(LANDMARK, sitting.advance()) <= .983);

        var takeoff = new SurferModes.Continuous(TAKEOFF, LANDMARK, 90);
        var turn = new SurferModes.Continuous(PADDLE_TURN, LANDMARK, 90);
        GoTo.Point last = LANDMARK;
        for (int i = 0; i < 8; i++) last = takeoff.advance();
        assertEquals(9.6, distance(LANDMARK, last), .0001);
        assertEquals(1.964, distance(last, takeoff.advance()), .0001);
        assertTrue(turn.advance().lon() < LANDMARK.lon());
        for (int i = 1; i < 10; i++) last = turn.advance();
        assertTrue(last.lon() > LANDMARK.lon());
        assertEquals(last, turn.advance());
        assertTrue(turn.advance().lon() > last.lon());
    }

    @Test public void badFileReportsItsLine() {
        var invalid = new BufferedReader(new StringReader("latitude,longitude\n1,2\n91,2\n"));
        var failure = assertThrows(IOException.class, () -> SurferModes.decode(invalid));
        assertTrue(failure.getMessage().contains("line 3"));
    }

    @Test public void typedSequencePlaysInOrderAndRepeatsFromTheLastPosition() {
        var modes = SurferModes.parseSequence("3, 1,3");
        assertEquals(List.of(PADDLE_SAME, SITTING, PADDLE_SAME), modes);
        var fixes = SurferModes.generate(LANDMARK, 90, modes, 5, 2);
        var firstPaddle = SurferModes.generate(LANDMARK, 90, List.of(PADDLE_SAME), 5, 2);
        var sitting = SurferModes.generate(firstPaddle.get(8), 90, List.of(SITTING), 5, 2);
        var secondPaddle = SurferModes.generate(sitting.get(10), 90, List.of(PADDLE_SAME), 5, 2);
        assertEquals(27, fixes.size());
        assertEquals(firstPaddle, fixes.subList(0, 9));
        assertEquals(sitting, fixes.subList(8, 19));
        assertEquals(secondPaddle, fixes.subList(18, 27));
        assertEquals(List.of(SITTING, SLOW, PADDLE_SAME, PADDLE_TURN, SITTING), SurferModes.parseSequence("1,2,3,4,1"));
    }

    @Test public void errorsIdentifyTheInvalidEntryAndPreventAnOversizedFile() {
        var empty = assertThrows(IllegalArgumentException.class, () -> SurferModes.parseSequence("   "));
        assertEquals("Enter a sequence such as 1,2,3,4,1", empty.getMessage());
        var failure = assertThrows(IllegalArgumentException.class, () -> SurferModes.parseSequence("1,7,1"));
        assertTrue(failure.getMessage().contains("position 2"));
        assertThrows(IllegalArgumentException.class, () -> SurferModes.parseSequence("1,,2"));
        assertThrows(IllegalArgumentException.class, () -> SurferModes.generate(LANDMARK, 90, List.of(RIDING, RIDING), 3600, 2));
    }

    @Test public void generatedOneHzRideKeepsDurationAndDistance() {
        var modes = List.of(TAKEOFF, RIDING);
        var one = SurferModes.generate(LANDMARK, 90, modes, 5, 1);
        var two = SurferModes.generate(LANDMARK, 90, modes, 5, 2);
        assertEquals(10, one.size());
        assertEquals((two.size() - 1) * 500, (one.size() - 1) * 1000);
        for (int i = 0; i < one.size(); ++i)
            assertEquals(0, distance(two.get(2*i), one.get(i)), .00001);
    }

    private static double distance(GoTo.Point a, GoTo.Point b) {
        return Geo.distance(a.lat(), a.lon(), b.lat(), b.lon());
    }
}
