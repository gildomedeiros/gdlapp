package au.com.gildo.vtc;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;
public class JpegFrameTest {
    private final byte[] valid = {(byte)255,(byte)216,1,2,(byte)255,(byte)217};
    @Test public void preservesFrameBytes() throws Exception {
        assertArrayEquals(valid,JpegFrame.read(new ByteArrayInputStream(valid),valid.length));
    }
    @Test public void rejectsTruncatedFrame() {
        assertThrows(IOException.class,() -> JpegFrame.read(new ByteArrayInputStream(new byte[]{(byte)255,(byte)216,1}),100));
    }
    @Test public void rejectsJsonErrorInsteadOfImage() {
        assertThrows(IOException.class,() -> JpegFrame.read(new ByteArrayInputStream("{\"message\":\"Busy\"}".getBytes()),100));
    }
    @Test public void rejectsOversizeFrame() {
        assertThrows(IOException.class,() -> JpegFrame.read(new ByteArrayInputStream(valid),valid.length-1));
    }
    @Test public void rejectsEmptyResponse() {
        assertThrows(IOException.class,() -> JpegFrame.read(new ByteArrayInputStream(new byte[0]),100));
    }
    @Test public void handlesFragmentedNetworkReads() throws Exception {
        InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(valid)) {
            @Override public int read(byte[] b,int off,int len) throws IOException { return super.read(b,off,Math.min(1,len)); }
        };
        assertArrayEquals(valid,JpegFrame.read(fragmented,100));
    }
}
