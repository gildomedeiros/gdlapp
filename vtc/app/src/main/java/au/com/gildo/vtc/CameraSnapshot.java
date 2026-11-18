package au.com.gildo.vtc;

import java.io.IOException;

/** One Canon still from the live view buffer, with cleanup after every attempt. */
final class CameraSnapshot {
    static final String START = "{\"cameradisplay\":\"on\",\"liveviewsize\":\"small\"}";
    static final String STOP = "{\"cameradisplay\":\"on\",\"liveviewsize\":\"off\"}";

    interface Request {
        byte[] send(String method, String suffix, String body) throws IOException;
    }

    static byte[] capture(Request request) throws IOException {
        IOException captureError = null;
        try {
            request.send("POST", "", START);
            byte[] frame = request.send("GET", "/flip", null);
            if (frame.length < 4 || (frame[0] & 255) != 255 || (frame[1] & 255) != 216
                    || (frame[frame.length - 2] & 255) != 255 || (frame[frame.length - 1] & 255) != 217) {
                throw new IOException("Camera did not return a complete JPEG.");
            }
            return frame;
        } catch (IOException failure) {
            captureError = failure;
            throw failure;
        } finally {
            try {
                request.send("POST", "", STOP);
            } catch (IOException failure) {
                if (captureError == null) throw new IOException("Could not close camera preview.", failure);
                captureError.addSuppressed(failure);
            }
        }
    }
}
