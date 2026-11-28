package au.com.gildo.vtc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Rejects empty, truncated and oversized frame responses before bitmap decoding. */
final class JpegFrame {
    static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > limit - output.size()) throw new IOException("Preview frame exceeds size limit.");
            output.write(buffer, 0, count);
        }
        byte[] bytes = output.toByteArray();
        if (bytes.length < 4 || (bytes[0]&255) != 255 || (bytes[1]&255) != 216
                || (bytes[bytes.length-2]&255) != 255 || (bytes[bytes.length-1]&255) != 217)
            throw new IOException("Camera did not return a complete JPEG preview.");
        return bytes;
    }
}
