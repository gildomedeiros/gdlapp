package au.com.gildo.fm;

import java.io.IOException;
import java.util.Map;
import java.util.function.LongSupplier;

/** Clip timer and Canon recording state. Accessed on the serial camera worker. */
final class CameraRecording {
    private static final String RECORD = "/shooting/control/recbutton";
    private static final String MOVIE_MODE = "/shooting/control/moviemode";
    private static final String START = "{\"action\":\"start\"}";
    private static final String STOP = "{\"action\":\"stop\"}";
    private static final long RETRY_MS = 3000;

    interface Exchange {
        String send(String method, String path, String body) throws IOException;
    }

    static final class HttpError extends IOException {
        final int code;
        final String detail;

        static HttpError fromResponse(int code, String body) {
            return new HttpError(code, message(body));
        }

        HttpError(int code, String detail) {
            super("Camera HTTP " + code + (detail.isEmpty() ? "" : " · " + detail));
            this.code = code;
            this.detail = detail;
        }

        private static String message(String body) {
            try {
                if (ConfigJson.parse(body) instanceof Map<?, ?> fields && fields.get("message") instanceof String value) {
                    return value.trim();
                }
            } catch (IllegalArgumentException ignored) {
                // The HTTP status is sufficient if the error body is absent or not JSON.
            }
            return "";
        }
    }

    private long startedAt = -1;
    private long lastSession = -1;
    private long retryAt;

    void reset() {
        startedAt = -1;
        lastSession = -1;
        retryAt = 0;
    }

    long startedAt() { return startedAt; }

    void stop(Exchange camera) throws IOException {
        try {
            camera.send("POST", RECORD, STOP);
        } catch (HttpError error) {
            if (error.code != 400 || !error.detail.equalsIgnoreCase("Invalid parameter")) throw error;
        }
        reset();
    }

    String observe(String json, long now) {
        if (ConfigJson.parse(json) instanceof Map<?, ?> events && events.get("recbutton") instanceof Map<?, ?> rec) {
            Object state = rec.containsKey("action") ? rec.get("action") : rec.get("status");
            if ("start".equals(state)) {
                if (startedAt < 0) startedAt = now;
                return "start";
            }
            if ("stop".equals(state)) {
                startedAt = -1;
                return "stop";
            }
        }
        return "";
    }

    void update(long session, double limitMinutes, LongSupplier clock, Exchange camera) throws IOException {
        if (session != lastSession) {
            retryAt = 0;
            lastSession = session;
        }
        long now = clock.getAsLong();
        if (session < 0 || now < retryAt) return;

        if (startedAt >= 0 && (limitMinutes == 0 || now - startedAt < Math.round(limitMinutes * 60_000))) return;

        try {
            if (startedAt >= 0) {
                stop(camera);
            }
            try {
                camera.send("POST", RECORD, START);
            } catch (HttpError error) {
                // Enter movie mode only when requested by the camera. An already
                // recording camera can report busy, and changing its mode could interrupt it.
                if (error.code != 503 || !error.detail.equalsIgnoreCase("Mode not supported")) throw error;
                camera.send("POST", MOVIE_MODE, "{\"action\":\"on\"}");
                camera.send("POST", RECORD, START);
            }
            startedAt = clock.getAsLong();
            retryAt = 0;
        } catch (IOException error) {
            retryAt = clock.getAsLong() + RETRY_MS;
            throw error;
        }
    }
}
