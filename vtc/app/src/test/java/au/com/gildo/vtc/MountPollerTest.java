package au.com.gildo.vtc;

import org.junit.Test;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class MountPollerTest {
    static class Wire extends GtiClientTest.Wire {
        long now;
        int missingReads;
        String missingCommand=":f1";
        long readDelay=1500;
        boolean loseStopReply,dropStop,uninitialized,blocked;
        boolean decelerating;
        String stopTimeout="";
        @Override public String exchange(String c)throws IOException {
            if(c.startsWith(missingCommand)&&missingReads>0) {
                missingReads--;commands.add(c);now+=readDelay;throw new SocketTimeoutException("late read");
            }
            if(c.startsWith(":L")&&(loseStopReply||dropStop||(!stopTimeout.isEmpty()&&c.startsWith(stopTimeout)))) {
                if(dropStop)commands.add(c);else super.exchange(c);
                now+=700;throw new SocketTimeoutException("missing Stop acknowledgement");
            }
            if(c.startsWith(":f")&&uninitialized){commands.add(c);return "=000\r";}
            if(c.startsWith(":f")&&blocked){commands.add(c);return "=021\r";}
            if(c.startsWith(":L")&&decelerating){commands.add(c);return "=\r";}
            return super.exchange(c);
        }
    }
    static class Session {
        final Wire wire=new Wire();
        final GtiClient mount=new GtiClient(wire);
        final List<String> events=new ArrayList<>();
        final MountPoller poller=new MountPoller(()->wire.now,(event,fields)->events.add(event));
        Session()throws IOException{mount.connect();wire.commands.clear();}
        GtiClient.Position poll()throws IOException{return poller.poll(mount,()->true,100000);}
    }
    @Test public void oneLateReadAt1600msDoesNotStopOrResendTheGoto()throws Exception {
        Session s=new Session();GtiClient.Position before=s.mount.position();
        s.mount.goTo(before,37,-9,()->true);
        int pan=s.wire.target[0],tilt=s.wire.target[1];long starts=s.wire.starts();
        s.wire.missingReads=1;s.wire.readDelay=1600;
        assertNull(s.poll());assertTrue(s.poller.waiting());assertFalse(s.poller.interruptedMove());
        assertFalse(s.wire.sent(":L"));assertEquals(starts,s.wire.starts());
        GtiClient.Position restored=s.poll();assertNotNull(restored);assertTrue(restored.moving);
        MountPoller.validateRecovery(before,restored,true,pan,tilt,s.mount.cpr);
        s.poller.reset();assertFalse(s.poller.waiting());
        assertEquals(pan,s.wire.target[0]);assertEquals(tilt,s.wire.target[1]);assertEquals(starts,s.wire.starts());
    }
    @Test public void repeatedIsolatedLossesDoNotAccumulateIntoAFault()throws Exception {
        Session s=new Session();
        for(int n=0;n<12;n++) {
            s.wire.missingReads=1;assertNull(s.poll());assertNotNull(s.poll());
            s.poller.reset();s.wire.now+=200;
        }
        assertFalse(s.wire.sent(":L"));assertEquals(0,s.wire.starts());
        assertEquals(12,Collections.frequency(s.events,"mount_poll_retry"));
    }
    @Test public void sustainedSilenceStopsThenRequiresACompleteStoppedSample()throws Exception {
        Session s=new Session();GtiClient.Position before=s.mount.position();s.mount.goTo(before,30,-9,()->true);
        int pan=s.wire.target[0],tilt=s.wire.target[1];
        s.wire.pos[0]=GtiClient.wrap24(before.pan+10000);s.wire.pos[1]=before.tilt-3000;
        s.wire.missingReads=4;
        for(int n=0;n<3;n++){assertNull(s.poll());assertFalse(s.wire.sent(":L"));}
        assertNull(s.poll());assertTrue(s.wire.sent(":L1"));assertTrue(s.wire.sent(":L2"));
        assertTrue(s.poller.interruptedMove());assertTrue(s.poller.waiting());
        GtiClient.Position restored=s.poll();assertNotNull(restored);assertFalse(restored.moving);
        MountPoller.validateRecovery(before,restored,true,pan,tilt,s.mount.cpr);
        assertEquals(2,s.wire.starts());assertEquals(pan,s.wire.target[0]);assertEquals(tilt,s.wire.target[1]);
    }
    @Test public void stopPacketLossKeepsPollingUntilStoppedRatherThanClosingTheSession()throws Exception {
        Session s=new Session();s.mount.goTo(s.mount.position(),30,0,()->true);
        s.wire.missingReads=4;s.wire.dropStop=true;
        for(int n=0;n<4;n++)assertNull(s.poll());
        assertTrue(s.poller.waiting());assertTrue(s.wire.moving[0]);
        assertNull("A moving sample cannot release recovery after Stop was requested",s.poll());
        s.wire.dropStop=false;s.wire.now+=2100;
        assertNull(s.poll()); // Stop is retried, but another sample must confirm it.
        assertNotNull(s.poll());assertFalse(s.wire.moving[0]);
        assertTrue(s.events.contains("mount_recovery_stop_unconfirmed"));assertEquals(1,s.wire.starts());
    }
    @Test public void lostStopAcknowledgementCanBeResolvedByStoppedCounters()throws Exception {
        Session s=new Session();s.mount.goTo(s.mount.position(),30,0,()->true);
        s.wire.missingReads=4;s.wire.loseStopReply=true;
        for(int n=0;n<4;n++)assertNull(s.poll());
        assertTrue(s.poller.interruptedMove());assertFalse(s.poll().moving);
        assertEquals(1,s.wire.starts());
    }
    @Test public void landmarkHandoverWaitsForDecelerationAfterStopAcknowledgements()throws Exception {
        Session s=new Session();s.mount.goTo(s.mount.position(),30,-9,()->true);
        long starts=s.wire.starts();s.wire.decelerating=true;
        s.poller.stopForTransition(s.mount,()->true);
        assertTrue(s.poller.waiting());assertTrue(s.poller.interruptedMove());
        assertNull(s.poll());assertEquals(starts,s.wire.starts());
        s.wire.moving[0]=false;s.wire.moving[1]=false;
        assertFalse(s.poll().moving);assertEquals(starts,s.wire.starts());
    }
    @Test public void motionDeadlineCanRequestStopEarlierThanTheRecoveryGrace()throws Exception {
        Session s=new Session();s.wire.missingReads=1;
        assertNull(s.poller.poll(s.mount,()->true,1000));
        assertTrue(s.poller.interruptedMove());assertTrue(s.wire.sent(":L1"));
    }
    @Test public void explicitStopOrBackgroundCancellationPreventsAnyRecoveryReadOrStart()throws Exception {
        Session s=new Session();s.wire.missingReads=1;assertNull(s.poll());
        int commands=s.wire.commands.size();
        assertThrows(CancellationException.class,()->s.poller.poll(s.mount,()->false,100000));
        assertEquals(commands,s.wire.commands.size());assertEquals(0,s.wire.starts());
        AtomicBoolean permitted=new AtomicBoolean(true);
        s.wire.afterPositionRead=axis->permitted.set(false);
        assertThrows(CancellationException.class,()->s.poller.poll(s.mount,permitted::get,100000));
        assertFalse(s.wire.commands.get(s.wire.commands.size()-1).startsWith(":J"));
    }
    @Test public void partialSampleIsDiscardedAndNextReadUsesNewCountersOnBothAxes()throws Exception {
        Session s=new Session();
        s.wire.afterPositionRead=axis->{if(axis==1){s.wire.missingCommand=":f2";s.wire.missingReads=1;}};
        assertNull(s.poll());assertTrue(s.poller.waiting());
        s.wire.afterPositionRead=axis->{};s.wire.pos[0]=12345;s.wire.pos[1]=67890;
        GtiClient.Position p=s.poll();assertEquals(12345,p.pan);assertEquals(67890,p.tilt);
        assertFalse(s.wire.sent(":L"));
    }
    @Test public void blockedMotorRebootAndRejectedCommandsAreNeverHiddenAsPacketLoss()throws Exception {
        Session blocked=new Session();blocked.wire.blocked=true;
        assertThrows(IOException.class,blocked::poll);assertFalse(blocked.poller.waiting());
        Session reboot=new Session();reboot.wire.uninitialized=true;
        assertThrows(IOException.class,reboot::poll);assertFalse(reboot.poller.waiting());
        Session rejected=new Session();rejected.wire.rejected=":f1";
        assertThrows(IOException.class,rejected::poll);assertFalse(rejected.poller.waiting());
    }
    @Test public void aRejectedStopIsNotSwallowedEvenWhenTheOtherAxisTimesOut()throws Exception {
        Session s=new Session();s.wire.missingReads=4;
        for(int n=0;n<3;n++)assertNull(s.poll());
        s.wire.stopTimeout=":L1";s.wire.rejected=":L2";
        assertThrows(IOException.class,s::poll);
    }
    @Test public void recoveryKeepsReferenceForSmallCounterOvershootAndUndershoot()throws Exception {
        int[] cpr={360000,360000};
        GtiClient.Position before=new GtiClient.Position(0xfffff0,8000000,false);
        int end=GtiClient.wrap24(before.pan+30000);
        for(int travelled:new int[]{-100,12000,30100})
            MountPoller.validateRecovery(before,new GtiClient.Position(GtiClient.wrap24(before.pan+travelled),7997000,false),true,end,7991000,cpr);
        MountPoller.validateRecovery(before,new GtiClient.Position(GtiClient.wrap24(before.pan+100),before.tilt,false),false,0,0,cpr);
        assertThrows(IOException.class,()->MountPoller.validateRecovery(null,before,false,0,0,cpr));
    }
}
