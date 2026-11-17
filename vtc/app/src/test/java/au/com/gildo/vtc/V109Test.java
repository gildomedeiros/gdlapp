package au.com.gildo.vtc;
import org.junit.Test;
import java.util.*;
import java.io.IOException;
import static org.junit.Assert.*;

public class V109Test {
    @Test public void obsoleteOverlapSettingIsNotExposedAfterMigration(){
        assertFalse(new Settings(Map.of("waitForGoto","0")).values.containsKey("waitForGoto"));
    }
    @Test public void noWaitSendsCommandsWhileMovingWhenFirmwareAcceptsThem()throws Exception {
        GtiClientTest.Wire w=new GtiClientTest.Wire();GtiClient g=new GtiClient(w);g.connect();w.moving[0]=true;
        GtiClient.Position p=g.position();assertTrue(p.moving);
        assertTrue(g.moveNoWait(p,2,0,true,()->true));
        assertTrue(w.sent(":G100"));assertTrue(w.sent(":S1"));assertTrue(w.sent(":J1"));assertFalse(w.sent(":L"));
        assertEquals(GtiClient.wrap24(p.pan+2000),w.target[0]);
    }
    @Test public void busyRejectionKeepsOriginalMoveWithoutStopOrNewDestination()throws Exception {
        GtiClientTest.Wire w=new GtiClientTest.Wire();GtiClient g=new GtiClient(w);g.connect();w.moving[0]=true;w.target[0]=1234;w.rejected=":G1";
        assertFalse(g.moveNoWait(g.position(),2,0,true,()->true));
        assertEquals(1234,w.target[0]);assertTrue(w.moving[0]);assertFalse(w.sent(":L"));assertFalse(w.sent(":S"));assertEquals(0,w.starts());
    }
    @Test public void normalModeStillRejectsMovingSampleWithoutSendingNewMotion()throws Exception {
        GtiClientTest.Wire w=new GtiClientTest.Wire();GtiClient g=new GtiClient(w);g.connect();w.moving[0]=true;
        assertThrows(IOException.class,()->g.goTo(g.position(),2,0,()->true));assertFalse(w.sent(":G"));
    }
    @Test public void partialUpdateFailureIsNotMistakenForHarmlessBusyRejection()throws Exception {
        GtiClientTest.Wire w=new GtiClientTest.Wire();GtiClient g=new GtiClient(w);g.connect();w.moving[0]=true;w.rejected=":S1";
        assertThrows(IOException.class,()->g.moveNoWait(g.position(),2,0,true,()->true));assertTrue(w.sent(":L1"));assertEquals(0,w.starts());
    }
    @Test public void noWaitStillChecksBoundsAndCancellation()throws Exception {
        GtiClientTest.Wire w=new GtiClientTest.Wire();GtiClient g=new GtiClient(w);g.connect();w.moving[0]=true;
        assertThrows(IOException.class,()->g.moveNoWait(g.position(),46,0,true,()->true));
        assertThrows(IOException.class,()->g.moveNoWait(g.position(),2,0,true,()->false));assertFalse(w.sent(":J"));
    }
    @Test public void receiverActivityShowsNoFixAndAgesWithoutInventingAFix(){
        LoRaActivity a=new LoRaActivity();a.datagram(100);a.line("GDL_LILYGO_READY",100);
        assertTrue(a.display(200).contains("Receiver packets: 1"));assertTrue(a.display(200).contains("Surfer/data messages: 0"));
        a.datagram(1000);a.line("RAW,NOFIX,1,1246,0,185,-16,13.0,0",1000);a.decision("Transmitter has no GPS fix");
        assertTrue(a.display(2500).contains("1.5 s ago"));assertTrue(a.display(2500).contains("RAW,NOFIX"));assertFalse(a.display(2500).contains("Latest received GPS"));
        a.datagram(3000);a.line("GDL_LILYGO_READY",3000);
        assertTrue(a.display(3000).contains("last 2.0 s ago"));assertTrue(a.display(3000).contains("RAW,NOFIX"));
    }
    @Test public void latestReceivedDataAndItsRejectionRemainVisible(){
        LoRaActivity a=new LoRaActivity();a.datagram(1000);a.line("RX,GPS,42,1000,-28.1,153.4,9,1.7,-66,12.0,0",1000);
        a.decision("Received but rejected: duplicate/out-of-order GPS");String text=a.display(1500);
        assertTrue(text.contains("Satellites 9"));assertTrue(text.contains("HDOP 1.70"));assertTrue(text.contains("RSSI -66"));assertTrue(text.contains("rejected"));
        a.line("bad packet",2000);assertTrue(a.display(2000).contains("Latest message: bad packet"));assertFalse(a.display(2000).contains("Satellites 9"));
    }
}
