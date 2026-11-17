package au.com.gildo.vtc;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class StopCompletionTest {
    @Test public void failedStopStillCompletesDisconnectAndExitOnce(){
        StopCompletion s=new StopCompletion();List<Boolean> replies=new ArrayList<>();
        s.request(replies::add);s.request(replies::add);
        s.complete(false);s.complete(true);
        assertEquals(Arrays.asList(false,false),replies);assertFalse(s.pending());
    }
    @Test public void confirmedStopRemainsConfirmed(){
        StopCompletion s=new StopCompletion();List<Boolean> replies=new ArrayList<>();
        s.request(replies::add);s.complete(true);assertEquals(Arrays.asList(true),replies);
    }
    @Test public void replacementNetworkOnlyAllowsStopAndStatus(){
        for(String c:new String[]{":L1\r",":L2\r",":f1\r",":f2\r"})assertTrue(StopCompletion.recoveryCommand(c));
        for(String c:new String[]{":J1\r",":G110\r",":I1000010\r",":S1000010\r",":F1\r",":j1\r",":L3\r"})assertFalse(StopCompletion.recoveryCommand(c));
    }
}
