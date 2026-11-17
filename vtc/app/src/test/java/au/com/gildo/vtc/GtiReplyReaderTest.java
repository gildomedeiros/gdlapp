package au.com.gildo.vtc;

import org.junit.Test;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.*;
import static org.junit.Assert.*;

public class GtiReplyReaderTest {
    private static final class Frame {
        final int delay;final String value;
        Frame(int delay,String value){this.delay=delay;this.value=value;}
    }
    private static final class Wire implements GtiReplyReader.Receiver {
        final ArrayDeque<Frame> frames=new ArrayDeque<>();
        final List<Integer> budgets=new ArrayList<>();
        long now;
        Wire(Frame... frames){this.frames.addAll(Arrays.asList(frames));}
        public String receive(int timeout)throws IOException {
            budgets.add(timeout);
            Frame frame=frames.poll();
            if(frame==null||frame.delay>=timeout){now+=timeout;throw new SocketTimeoutException("simulated");}
            now+=frame.delay;return frame.value;
        }
        String read(String command)throws IOException {return GtiReplyReader.receive(command,this,()->now);}
    }
    @Test public void fieldStatusDelayed806MsRemainsInItsOriginalReadTransaction()throws Exception {
        Wire w=new Wire(new Frame(806,"=211\r"));
        assertEquals("=211\r",w.read(":f1\r"));assertEquals(List.of(1500),w.budgets);assertEquals(806,w.now);
    }
    @Test public void delayedStatusCannotBecomeAStopAcknowledgement()throws Exception {
        Wire w=new Wire(new Frame(87,"=211\r"),new Frame(100,"=\r"));
        assertEquals("=\r",w.read(":L1\r"));assertEquals(List.of(700,613),w.budgets);
    }
    @Test public void mismatchedRepliesNeverExtendTheAbsoluteDeadline() {
        Wire w=new Wire(new Frame(400,"=211\r"),new Frame(250,"=101\r"),new Frame(100,"=\r"));
        SocketTimeoutException failure=assertThrows(SocketTimeoutException.class,()->w.read(":L1\r"));
        assertTrue(failure.getMessage().contains("discarded 2"));assertEquals(List.of(700,300,50),w.budgets);assertEquals(700,w.now);
    }
    @Test public void statusAndCounterQueriesRejectTheWrongPayloadShape()throws Exception {
        Wire status=new Wire(new Frame(10,"=\r"),new Frame(20,"=123456\r"),new Frame(30,"=101\r"));
        assertEquals("=101\r",status.read(":f2\r"));
        Wire count=new Wire(new Frame(10,"=101\r"),new Frame(20,"=CB5B78\r"));
        assertEquals("=CB5B78\r",count.read(":j1\r"));
    }
    @Test public void controllerErrorsAreNotHiddenByTheReceiveFilter()throws Exception {
        Wire w=new Wire(new Frame(10,"!2\r"),new Frame(10,"=\r"));
        assertEquals("!2\r",w.read(":S1000080\r"));assertEquals(1,w.frames.size());
    }
    @Test public void lostOrMalformedRepliesStillFailAndMovesKeepTheOriginalBudget() {
        Wire missing=new Wire();
        assertThrows(SocketTimeoutException.class,()->missing.read(":J1\r"));assertEquals(List.of(700),missing.budgets);
        Wire malformed=new Wire(new Frame(20,"=101"),new Frame(20,"garbage\r"));
        assertThrows(SocketTimeoutException.class,()->malformed.read(":f1\r"));assertEquals(1500,malformed.now);
        assertEquals(700,GtiReplyReader.timeoutMs(":G100\r"));assertEquals(700,GtiReplyReader.timeoutMs(":S1000080\r"));
    }
}
