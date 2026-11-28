package au.com.gildo.fm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class LandmarkGpsCaptureTest {
    private final String[] original=FmService.DEFAULTS.clone();
    private final LandmarkGpsCapture capture=new LandmarkGpsCapture(1000);
    private final List<String> events=new ArrayList<>();
    private final GoTo.Trace log=(name,fields)->events.add(name);
    private long now=1000, sequence;
    private String[] saved;

    public LandmarkGpsCaptureTest() {
        MountCoordinates.apply("-28, 153",original,18,19,LandmarkCalibration.COORDINATES);
        new LandmarkCalibration(30,2).saveReference(original,new GoTo.Encoders(123,456));
    }
    private LandmarkGpsCapture.Reading gps(double lat,long at) {
        return new LandmarkGpsCapture.Reading(LoRaTelemetry.parse("RX,GPS,"+(++sequence)+","+sequence*500+
            ","+lat+",153.2,25,0.6,-75,10,0"),at);
    }
    private LandmarkGpsCapture.Result run(LandmarkGpsCapture.Receiver receiver,long delay) {
        return capture.run(original,receiver,()->now,()->now+=delay,next->saved=next,status->{},log);
    }
    @Test public void savesTenFreshSamplesAndLogsTheAverage() {
        var result=run(add->{
            add.accept(gps(-20,999)); // Received before the capture.
            var fix=gps(sequence%4==1?-28.10001:-28.09999,now);
            add.accept(fix); add.accept(fix);
        },500);
        assertEquals(5500,now);
        assertEquals(-28.1,Double.parseDouble(saved[18]),0.000001);
        assertEquals(153.2,Double.parseDouble(saved[19]),1e-8);
        assertEquals(10,events.stream().filter("landmark_gps_sample"::equals).count());
        assertTrue(events.contains("landmark_batch_selected"));
        assertNotNull(result.coordinates());
        assertFalse(LandmarkCalibration.hasReference(saved));
    }
    @Test public void requiresFourSecondsAndRejectsWideScatter() {
        var result=run(add->add.accept(gps(sequence%2==0?-28:-28.1,now)),300);
        assertEquals("",result.coordinates());
        assertEquals("",saved[18]);
        assertEquals("",saved[19]);
        assertFalse(events.contains("landmark_batch_selected"));
    }
    @Test public void slidingBatchCanRecoverAfterScatter() {
        var result=run(add->add.accept(gps(sequence<3?-28.2:-28.1,now)),500);
        assertTrue(result.message().startsWith("Saved landmark"));
        assertEquals(13,sequence);
    }
    @Test public void cancellingRetainsPreviousLandmarkWithoutSaving() {
        assertNull(run(add->capture.cancel(),500).coordinates());
        assertNull(saved);
        assertTrue(events.contains("landmark_capture_result"));
    }
    @Test public void failureToSaveBlankIsReported() {
        var result=capture.run(original,add->{throw new IOException("Receiver unavailable");},()->now,
            ()->{},next->{throw new IOException("Storage unavailable");},status->{},log);
        assertNull(result.coordinates());
        assertTrue(result.message().contains("Receiver unavailable"));
        assertTrue(result.message().contains("could not save blank"));
    }
}
