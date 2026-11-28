package au.com.gildo.fm;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class SettingsAutosaveTest {
    private final List<String> events=new ArrayList<>();
    private String[] saved=FmService.DEFAULTS.clone();
    private SettingsAutosave.Edit write;
    private Consumer<String> complete;
    private final SettingsAutosave form=new SettingsAutosave((edit,done)->{
        assertNull("Two simultaneous writes",write);
        write=edit;
        complete=done;
        events.add("write "+edit.value());
    },(edit,error)->events.add(error==null?"saved":"failed"));

    private void reply(String error) {
        SettingsAutosave.Edit edit=write;
        Consumer<String> done=complete;
        write=null;
        complete=null;
        if(error==null) {
            try {
                String[] next=saved.clone();
                edit.apply(next);
                LandmarkCalibration.clearStaleReference(saved,next);
                saved=SettingsFile.decode(SettingsFile.settingsDocument(next).getJSONObject("settings"),saved);
            } catch(Exception invalid) { error=invalid.getMessage(); }
        }
        done.accept(error);
    }

    @Test public void livePressFlushesFinalTextAndStartsAfterDurableReply() {
        int lead=FmService.LIVE_PAN_LEAD;
        form.edit(lead,"3");
        form.edit(lead,"38");
        assertTrue(events.isEmpty());
        form.finishAll(()->events.add("start "+saved[lead]));
        assertTrue(form.waitingForAction());
        form.finishAll(()->fail("Duplicate launch"));
        assertEquals(List.of("write 38"),events);
        reply(null);
        assertEquals(List.of("write 38","saved","start 38"),events);
        assertFalse(form.waitingForAction());
        form.finishAll(()->events.add("next"));
        assertNull(write);
        assertEquals("next",events.get(events.size()-1));
    }

    @Test public void checkboxAndNewerTextSurviveEarlierWrite() {
        int lead=FmService.LIVE_PAN_LEAD;
        form.edit(lead,"4");
        form.finishField(lead);
        form.edit(lead,"45");
        form.edit(FmService.TILT_DISABLE,"true");
        form.finishField(FmService.TILT_DISABLE);
        form.finishAll(()->events.add(saved[lead]+"/"+saved[FmService.TILT_DISABLE]));
        reply(null);
        assertTrue(form.hasDraft(lead));
        assertEquals(FmService.TILT_DISABLE,write.index());
        reply(null);
        reply(null);
        assertEquals("45/true",events.get(events.size()-1));
        assertFalse(form.hasDraft(lead));
    }

    @Test public void failedSaveBlocksStartAndRetainsEditForRetry() {
        int lead=FmService.LIVE_PAN_LEAD;
        form.edit(lead,"25");
        form.finishField(lead);
        form.finishAll(()->fail("Started after write failure"));
        reply("Storage unavailable");
        assertTrue(form.hasDraft(lead));
        assertFalse(form.waitingForAction());
        assertEquals("35",saved[lead]);
        form.finishAll(()->events.add("retry started"));
        reply(null);
        assertEquals("retry started",events.get(events.size()-1));
        assertEquals("25",saved[lead]);
    }

    @Test public void landmarkCaptureFollowsQueuedEditEvenWhenThatEditFails() {
        int index=LandmarkCalibration.LANDMARK_LAT;
        form.edit(index,"-28,");
        form.finishField(index);
        form.afterPendingWrites(()->events.add("capture"));
        assertTrue(form.waitingForAction());
        assertEquals(List.of("write -28,"),events);
        reply(null);
        assertEquals(List.of("write -28,","failed","capture"),events);
        assertTrue(form.hasDraft(index));
        form.discardDraft(form.draft(index));
        assertFalse(form.hasDraft(index));
    }

    @Test public void settingsOpenAfterLoadAndLiveHoldsThemUntilBothMotorsStop() {
        assertFalse(SettingsAutosave.editable(false,false,false));
        assertTrue(SettingsAutosave.editable(true,false,false)); // Discovery can still be running.
        assertFalse(SettingsAutosave.editable(true,true,false)); // Includes Stop sent and braking.
        assertTrue(SettingsAutosave.editable(true,true,true));   // Report may still be writing.
    }

    @Test public void newerLandmarkDraftSurvivesCaptureCompletion() {
        int index=LandmarkCalibration.LANDMARK_LAT;
        form.edit(index,"-28, 153");
        var atCapture=form.draft(index);
        form.edit(index,"-28, 154");
        form.discardDraft(atCapture);
        assertEquals("-28, 154",form.draft(index).value());
    }

    @Test public void startupDiscoveryCannotReplaceEditedEndpointOrOtherEdits() throws Exception {
        String[] starting=saved.clone();
        String[] discovered=starting.clone();
        discovered[4]="192.168.4.25";
        saved[FmService.LIVE_PAN_SPEED_MULTIPLIER]="1.3";
        SettingsFile.requireUnchanged(starting,saved,4,5);
        String[] merged=SettingsFile.mergeEdit(starting,discovered,saved);
        assertEquals("1.3",merged[FmService.LIVE_PAN_SPEED_MULTIPLIER]);
        assertEquals(discovered[4],merged[4]);
        saved[4]="192.168.4.35";
        assertThrows(java.io.IOException.class,()->SettingsFile.requireUnchanged(starting,saved,4,5));
    }

    @Test public void incompleteCoordinateKeepsOldPairAndOtherCheckboxCanSave() {
        MountCoordinates.apply("-28, 153",saved);
        new LandmarkCalibration(42,3).saveReference(saved,new GoTo.Encoders(500,600));
        assertTrue(LandmarkCalibration.hasReference(saved));
        form.edit(MountCoordinates.LATITUDE,"-27,");
        form.finishField(MountCoordinates.LATITUDE);
        form.edit(FmService.TILT_DISABLE,"true");
        form.finishField(FmService.TILT_DISABLE);
        form.finishAll(()->fail("Started with incomplete coordinates"));
        reply(null);
        assertEquals("-28, 153",MountCoordinates.format(saved));
        reply(null);
        assertTrue(FmService.tiltDisabled(saved));
        assertTrue(LandmarkCalibration.hasReference(saved));
        form.edit(MountCoordinates.LATITUDE,"-27, 152");
        form.finishAll(()->events.add("start"));
        reply(null);
        assertEquals("-27, 152",MountCoordinates.format(saved));
        assertFalse(LandmarkCalibration.hasReference(saved));
        assertEquals("start",events.get(events.size()-1));
    }
}
