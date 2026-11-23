package au.com.gildo.fm;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ReplayOutcomeTest {
    @Test public void distinguishesAnAcknowledgedStartFromTheNextMovingAxisBlock() {
        String first=FmService.replayOutcomeLine(1,"GoTo sent · pan and tilt Start acknowledged\nTarget details");
        String second=FmService.replayOutcomeLine(2,"Blocked before Start · Axis 1 is already moving");
        assertEquals("Viewpoint 1 — Start acknowledged. Pan and tilt both replied successfully. Arrival unverified.",first);
        assertEquals("Viewpoint 2 — Blocked before Start. Pan was still moving. FM sent no GoTo for this viewpoint.",second);
    }

    @Test public void retainsTheSpecificStartErrorAndPartialAcknowledgments() {
        String failure=FmService.replayOutcomeLine(3,
            "GoTo Start issue: :J2 reply timed out\nStart acknowledgments: 1 of 2\nTarget details");
        assertEquals("Viewpoint 3 — GoTo Start issue: :J2 reply timed out · Start acknowledgments: 1 of 2",failure);
    }

    @Test public void distanceConnectsSuccessesAcrossFailedRowsAndIncludesReturnLegs() {
        GoTo.Point start=new GoTo.Point(0,0,0);
        GoTo.Point failedTarget=new GoTo.Point(10,10,0);
        GoTo.Point nextSuccess=new GoTo.Point(0,0.0001,0);
        List<ReplayKml.Viewpoint> rows=List.of(
            new ReplayKml.Viewpoint(1,2,failedTarget,false,"Blocked"),
            new ReplayKml.Viewpoint(2,3,start,true,"Both Start replies acknowledged"),
            new ReplayKml.Viewpoint(3,4,failedTarget,false,"Only one Start reply acknowledged"),
            new ReplayKml.Viewpoint(4,5,nextSuccess,true,"Both Start replies acknowledged"),
            new ReplayKml.Viewpoint(5,6,start,true,"Both Start replies acknowledged"),
            new ReplayKml.Viewpoint(6,7,failedTarget,false,"Blocked"));

        assertEquals(0,FmService.successfulReplayPathLength(rows.subList(0,1)),0);
        assertEquals(0,FmService.successfulReplayPathLength(rows.subList(0,3)),0);
        assertEquals(2*Geo.distance(0,0,0,0.0001),FmService.successfulReplayPathLength(rows),0.000001);
    }

    @Test public void hsGapWarningDefaultsToThirtyMetresAndAcceptsASavedDecimal() {
        String[] settings=FmService.DEFAULTS.clone();
        assertEquals(30.0,FmService.hsGapWarningM(settings),0);
        settings[FmService.HS_GAP_WARNING]="17.25";
        assertEquals(17.25,FmService.hsGapWarningM(settings),0);
        assertEquals("17.25 m",FmService.hsGapWarningLabel(FmService.hsGapWarningM(settings)));
        settings[FmService.HS_GAP_WARNING]="0";
        assertEquals(0.0,FmService.hsGapWarningM(settings),0);
        for(String invalid:new String[]{"-1","NaN","Infinity",""}) {
            settings[FmService.HS_GAP_WARNING]=invalid;
            assertThrows(IllegalArgumentException.class,()->FmService.hsGapWarningM(settings));
        }
    }

    @Test public void hsPaceShowsTargetBeforeObservedAndMarksUnavailableMeasurements() {
        assertEquals("Pace [target, observed] · [18.00, 6.17] km/h",FmService.hsPaceLabel(18,6.1714));
        assertEquals("Pace [target, observed] · [18.00, —] km/h",FmService.hsPaceLabel(18,null));
    }

    @Test public void averageSpeedIsInKilometresPerHour() {
        assertEquals(8.64,FmService.averageReplaySpeedKmh(6.0,2500),0.000001);
        assertEquals(0.0,FmService.averageReplaySpeedKmh(0.0,2500),0.0);
        assertNull(FmService.averageReplaySpeedKmh(0.0,0));
    }
}
