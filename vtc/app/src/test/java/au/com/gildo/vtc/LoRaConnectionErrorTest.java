package au.com.gildo.vtc;
import org.junit.Test;
import java.net.BindException;
import java.io.IOException;
import static org.junit.Assert.*;

public class LoRaConnectionErrorTest {
    @Test public void identifiesOccupiedConfiguredPhonePort(){
        String message=LoRaConnectionError.describe(new BindException("bind failed: EADDRINUSE (Address already in use)"),5005);
        assertTrue(message.contains("Phone UDP port 5005 is already in use"));
        assertTrue(message.contains("owner is unknown"));
    }
    @Test public void detectsWrappedFailureWithDifferentPort(){
        assertTrue(LoRaConnectionError.describe(new IOException("socket failed",new BindException("Address already in use")),5010).contains("port 5010 is already in use"));
    }
    @Test public void otherBindFailuresAreNotBlamedOnOccupiedPort(){
        assertEquals("LoRa connection failed: Permission denied",LoRaConnectionError.describe(new BindException("Permission denied"),5005));
    }
}
