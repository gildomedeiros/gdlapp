package au.com.gildo.vtc;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class CameraSnapshotTest {
    private static final byte[] JPEG = {(byte) 255, (byte) 216, (byte) 255, (byte) 217};

    @Test public void fetchesExactlyOneFrameBetweenStartAndStop() throws IOException {
        List<String> calls = new ArrayList<>();
        byte[] result = CameraSnapshot.capture((method, suffix, body) -> {
            calls.add(method + suffix + ":" + body);
            return JPEG;
        });
        assertArrayEquals(JPEG, result);
        assertEquals(List.of("POST:" + CameraSnapshot.START, "GET/flip:null",
                "POST:" + CameraSnapshot.STOP), calls);
    }

    @Test public void stopsAfterFrameFailureAndKeepsOriginalError() {
        IOException frameError = new IOException("Frame unavailable");
        IOException stopError = new IOException("Stop unavailable");
        IOException error = assertThrows(IOException.class, () -> CameraSnapshot.capture((method, suffix, body) -> {
            if (method.equals("GET")) throw frameError;
            if (CameraSnapshot.STOP.equals(body)) throw stopError;
            return new byte[0];
        }));
        assertSame(frameError, error);
        assertArrayEquals(new Throwable[]{stopError}, error.getSuppressed());
    }

    @Test public void cleansUpAfterUnconfirmedStartWithoutFetchingAFrame() {
        List<String> requests = new ArrayList<>();
        assertThrows(IOException.class, () -> CameraSnapshot.capture((method, suffix, body) -> {
            requests.add(method + suffix + ":" + body);
            if (CameraSnapshot.START.equals(body)) throw new IOException("Start timed out");
            return new byte[0];
        }));
        assertEquals(List.of("POST:" + CameraSnapshot.START, "POST:" + CameraSnapshot.STOP), requests);
    }
}
