package au.com.gildo.fm;

import org.junit.Test;
import static org.junit.Assert.*;

public class LiveFramingTest {
    private static final GoTo.Point MOUNT = new GoTo.Point(0, 0, 0);
    private static final GoTo.Point SURFER = Geo.destination(MOUNT, 0, 150);

    @Test public void widthChangesOnlyTheClassificationOfObservedTime() {
        var wide = report(LiveFraming.DEFAULT_SHOT_WIDTH_M);
        var tight = report(5);
        assertEquals(40, LiveFraming.DEFAULT_SHOT_WIDTH_M);
        for (LiveFraming framing : new LiveFraming[]{wide, tight}) {
            framing.fix(SurferModes.Mode.TAKEOFF, SURFER, 500);
            framing.sample(2, 600);
            framing.sample(0, 900);
            framing.fix(SurferModes.Mode.RIDING, SURFER, 1000);
            framing.sample(0, 1100);
            assertEquals(500, framing.takeoffMs());
            assertEquals(300, framing.observedMs());
        }
        assertEquals(0, wide.outsideMs());
        assertEquals(300, tight.outsideMs());
    }

    @Test public void missingEncoderIntervalsAreNotReportedAsTimeInFrame() {
        var framing = report(40);
        framing.fix(SurferModes.Mode.TAKEOFF, SURFER, 500);
        framing.sample(10, 700);
        framing.sample(10, 3000);
        framing.endTakeoff(3500);
        assertTrue(framing.sawTakeoff());
        assertEquals(3000, framing.takeoffMs());
        assertEquals(0, framing.observedMs());
        assertTrue(framing.summary().contains("unavailable"));
    }

    private LiveFraming report(double widthM) { return new LiveFraming(MOUNT, widthM, 2000, 0); }
}
