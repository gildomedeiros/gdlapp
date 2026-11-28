package au.com.gildo.vtc;
import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;
public class GtiClientTest {
    static class Fake implements GtiClient.Transport {
        final List<String> commands=new ArrayList<>();
        final int[] pos={0x800000,0x800000},target={0x800000,0x800000};
        boolean moving,failSecondStart;
        public String exchange(String command) throws IOException {
            commands.add(command);
            char op=command.charAt(1); int axis=command.charAt(2)-'1';
            if(op=='b') return "="+GtiClient.encode24(64935)+"\r";
            if(op=='a') return "="+GtiClient.encode24(2073600)+"\r";
            if(op=='j') return "="+GtiClient.encode24(pos[axis])+"\r";
            if(op=='f') return moving?"=011\r":"=001\r";
            if(op=='S') target[axis]=GtiClient.decode24(command.substring(3,9));
            if(op=='J') {
                if(failSecondStart && axis==1) throw new IOException("Lost reply");
                pos[axis]=target[axis];
            }
            return "=\r";
        }
        public void close() {}
    }
    @Test public void speedMoveProgramsTimerAndStopsBothAxes() throws Exception {
        Fake fake=new Fake();GtiClient mount=new GtiClient(fake);mount.connect();
        // Fake returns the old target when J is sent. Advance the target for this speed-mode test.
        fake.target[0]=fake.pos[0]+5760;
        mount.beginOperation();mount.moveByAtSpeed(1,1,0.5,p -> {});
        assertTrue(fake.commands.contains(":G110\r"));
        assertTrue(fake.commands.contains(":I1"+GtiClient.encode24(Math.round(64935*360f/(2073600*0.5f)))+"\r"));
        assertTrue(fake.commands.contains(":L1\r"));assertTrue(fake.commands.contains(":L2\r"));
    }
    @Test public void speedMoveRejectsInvalidSpeedBeforeStarting() throws Exception {
        Fake fake=new Fake();GtiClient mount=new GtiClient(fake);mount.connect();
        assertThrows(IOException.class,() -> mount.moveByAtSpeed(1,1,Double.NaN,p -> {}));
        assertFalse(fake.commands.contains(":J1\r"));
    }
    @Test public void speedMoveCancellationStopsWithoutStarting() throws Exception {
        Fake fake=new Fake();GtiClient mount=new GtiClient(fake);mount.connect();mount.cancel();
        assertThrows(IOException.class,() -> mount.moveByAtSpeed(1,1,0.5,p -> {}));
        assertFalse(fake.commands.contains(":J1\r"));assertTrue(fake.commands.contains(":L2\r"));
    }
    @Test public void fastNativeMoveAndReturnUseFirmwareTargets() throws Exception {
        Fake fake=new Fake();GtiClient mount=new GtiClient(fake);mount.connect();
        GtiClient.Position saved=mount.position();
        mount.beginOperation();mount.moveBy(1,1,true);
        assertTrue(fake.commands.contains(":G100\r"));
        mount.beginOperation();mount.goToSaved(saved,true);
        assertTrue(fake.commands.contains(":G101\r"));
        assertEquals(saved.pan,fake.pos[0]);
        assertFalse(fake.commands.stream().anyMatch(c -> c.startsWith(":I") || c.startsWith(":L")));
    }
    @Test public void nativeTiltHonoursSelectedModeAndDirection() throws Exception {
        Fake fake=new Fake();GtiClient mount=new GtiClient(fake);mount.connect();
        mount.beginOperation();mount.moveBy(2,-1,true);
        assertTrue(fake.commands.contains(":G201\r"));
        mount.beginOperation();mount.moveBy(2,1,false);
        assertTrue(fake.commands.contains(":G220\r"));
        assertEquals(0x800000,fake.pos[1]);
    }
    @Test public void encodesLittleEndian24Bits() throws Exception {
        assertEquals("563412",GtiClient.encode24(0x123456));
        assertEquals(0x123456,GtiClient.decode24("563412"));
    }
    @Test public void rejectsMalformedValues() {
        assertThrows(IOException.class,() -> GtiClient.decode24("123"));
        assertThrows(IOException.class,() -> GtiClient.decode24("GGGGGG"));
    }
    @Test public void connectOnlyReadsInitializedStationaryMount() throws Exception {
        Fake fake=new Fake();GtiClient mount=new GtiClient(fake);mount.connect();
        assertEquals(2073600,mount.cpr[0]);
        assertFalse(fake.commands.stream().anyMatch(c -> ":EGJS".indexOf(c.charAt(1))>=1));
    }
    @Test public void refusesMovingMountOnConnect() {
        Fake fake=new Fake();fake.moving=true;
        assertThrows(IOException.class,() -> new GtiClient(fake).connect());
    }
    @Test public void finitePositiveMoveAndReturnToSavedPosition() throws Exception {
        Fake fake=new Fake();GtiClient mount=new GtiClient(fake);mount.connect();
        GtiClient.Position saved=mount.position();
        mount.beginOperation();mount.moveBy(1,1);
        assertEquals(5760,fake.pos[0]-saved.pan);
        assertTrue(fake.commands.contains(":G120\r"));
        mount.beginOperation();mount.goToSaved(saved);
        assertEquals(saved.pan,fake.pos[0]);
        assertTrue(fake.commands.contains(":G121\r"));
    }
    @Test public void refusesReturnsOverTenDegreesWithoutStartingMotion() throws Exception {
        Fake fake=new Fake();GtiClient mount=new GtiClient(fake);mount.connect();
        assertThrows(IOException.class,() -> mount.goToSaved(new GtiClient.Position(fake.pos[0]+5760*11,fake.pos[1],false)));
        assertFalse(fake.commands.contains(":J1\r"));
    }
    @Test public void cancelledOperationCannotStartMotion() throws Exception {
        Fake fake=new Fake();GtiClient mount=new GtiClient(fake);mount.connect();mount.cancel();
        assertThrows(IOException.class,() -> mount.moveBy(1,1));
        assertFalse(fake.commands.contains(":J1\r"));
        assertTrue(fake.commands.contains(":L1\r"));assertTrue(fake.commands.contains(":L2\r"));
    }
    @Test public void startFailureStopsBothAxes() throws Exception {
        Fake fake=new Fake();GtiClient mount=new GtiClient(fake);mount.connect();fake.failSecondStart=true;
        assertThrows(IOException.class,() -> mount.goToSaved(new GtiClient.Position(fake.pos[0]+5760,fake.pos[1]+5760,false)));
        assertTrue(fake.commands.contains(":L1\r"));assertTrue(fake.commands.contains(":L2\r"));
    }
    @Test public void stopAttemptsSecondAxisEvenWhenFirstFails() {
        List<String> commands=new ArrayList<>();
        GtiClient mount=new GtiClient(new GtiClient.Transport() {
            public String exchange(String c) throws IOException { commands.add(c);if(c.equals(":L1\r")) throw new IOException("timeout");return "=\r"; }
            public void close() {}
        });
        assertThrows(IOException.class,mount::stop);assertTrue(commands.contains(":L2\r"));
    }
    @Test public void wrapsAcrossFullRevolution() {
        assertEquals(20,GtiClient.shortestDelta(10,2073590,2073600));
        assertEquals(-20,GtiClient.shortestDelta(2073590,10,2073600));
    }
    @Test public void wrapsAcross24BitCounterBoundary() {
        assertEquals(16,GtiClient.shortestDelta(8,0xfffff8,2073600));
    }
    @Test public void rejectedResponseIsReported() {
        GtiClient mount=new GtiClient(new GtiClient.Transport() {
            public String exchange(String c) { return "!02\r"; }
            public void close() {}
        });
        assertThrows(IOException.class,mount::connect);
    }
}


