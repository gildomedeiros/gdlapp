package au.com.gildo.vtc;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceDiscoveryTest {
    @Test public void recognizesSx70WithoutWaitingForConnectionClose() throws IOException {
        String body="{\"munufacturer\":\"Canon.Inc\",\"productname\":\"Canon PowerShot SX70 HS\"}";
        byte[] packet=("HTTP/1.1 200 OK\r\nContent-Length:"+body.length()+
            "\r\nContent-Type:application/json\r\n\r\n"+body).getBytes(StandardCharsets.US_ASCII);
        InputStream openConnection=new InputStream(){
            int offset;
            @Override public int read() throws IOException {
                if(offset==packet.length)throw new IOException("Socket is still open");
                return packet[offset++]&255;
            }
        };
        DeviceDiscovery.Device camera=DeviceDiscovery.identifyCamera("192.168.4.4",DeviceDiscovery.readCameraBody(openConnection));
        assertEquals("Canon PowerShot SX70 HS",camera.identity());
        assertEquals("192.168.4.4",camera.address());
    }
}
