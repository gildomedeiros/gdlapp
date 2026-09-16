package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** CAM3 v2.5: Ordered session files; disk work never runs on the control or receiver thread. */
public final class FullSessionLog {
    public interface Destination { OutputStream open(String name) throws IOException; }
    private final Destination destination;
    private final Consumer<String> error;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cam3-full-log"); t.setDaemon(true); return t;
    });
    private final int capacity;
    private int queued;
    private Session active;
    private volatile int controlsPending;
    private volatile String status = "Full Log OFF; minimal private diagnostics";
    private final AtomicLong totalDropped = new AtomicLong();
    private static final class Session {
        final String name;
        final AtomicLong dropped = new AtomicLong();
        Writer writer;
        boolean failed;
        long record;
        Session(String name) { this.name = name; }
    }
    public FullSessionLog(Destination destination, Consumer<String> error) { this(destination, error, 512); }
    FullSessionLog(Destination destination, Consumer<String> error, int capacity) {
        this.destination = destination; this.error = error; this.capacity = capacity;
    }
    public synchronized boolean enabled() { return active != null; }
    public boolean busy() { return controlsPending > 0; }
    public String status() { return status + (totalDropped.get() == 0 ? "" : "\nWARNING: dropped full-log entries=" + totalDropped.get()); }
    public synchronized void enable() {
        if (active != null || busy()) return;
        String name = "cam3_full_" + new SimpleDateFormat("yyyy-MM-dd_HHmmss_SSS", Locale.US).format(new Date())
                + "_" + UUID.randomUUID().toString().substring(0, 8) + ".jsonl";
        Session s = new Session(name); active = s; controlsPending++; status = "Opening Downloads/CAM3/" + name;
        worker.execute(() -> {
            try {
                s.writer = new BufferedWriter(new OutputStreamWriter(destination.open(name), StandardCharsets.UTF_8));
                write(s, "session_start", System.currentTimeMillis(), System.nanoTime()/1000000,
                        new Object[]{"version", "2.5", "format", 1});
                status = "Full Log ON: Downloads/CAM3/" + name;
            } catch (Exception ex) { fail(s, ex); }
            finally { synchronized (FullSessionLog.this) { controlsPending--; } }
        });
    }
    public synchronized void disable(String reason) {
        Session s = active;
        if (s == null) return;
        active = null; controlsPending++;
        worker.execute(() -> {
            try {
                if (!s.failed) {
                    write(s, "session_end", System.currentTimeMillis(), System.nanoTime()/1000000,
                            new Object[]{"reason", reason});
                    s.writer.close(); s.writer = null;
                    status = "Full Log OFF; saved Downloads/CAM3/" + s.name;
                }
            } catch (Exception ex) { fail(s, ex); }
            finally { synchronized (FullSessionLog.this) { controlsPending--; } }
        });
    }
    public synchronized void record(String type, Object... fields) {
        Session s = active;
        if (s == null) return;
        if (queued >= capacity) { s.dropped.incrementAndGet(); totalDropped.incrementAndGet(); return; }
        queued++;
        long wall = System.currentTimeMillis(), mono = System.nanoTime()/1000000;
        worker.execute(() -> {
            try { if (!s.failed) write(s, type, wall, mono, fields); }
            catch (Exception ex) { fail(s, ex); }
            finally { synchronized (FullSessionLog.this) { queued--; } }
        });
    }
    private void write(Session s, String type, long wall, long mono, Object[] fields) throws IOException {
        long lost = s.dropped.getAndSet(0);
        if (lost > 0) line(s, "log_overflow", wall, mono, new Object[]{"droppedEntries", lost});
        line(s, type, wall, mono, fields);
        s.writer.flush();
    }
    private void line(Session s, String type, long wall, long mono, Object[] fields) throws IOException {
        StringBuilder out = new StringBuilder("{\"record\":").append(++s.record)
                .append(",\"timestamp\":").append(json(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date(wall))))
                .append(",\"epochMs\":").append(wall).append(",\"monoMs\":").append(mono)
                .append(",\"event\":").append(json(type));
        for (int i=0; i+1<fields.length; i+=2) out.append(',').append(json(fields[i])).append(':').append(json(fields[i+1]));
        s.writer.write(out.append("}\n").toString());
    }
    private void fail(Session s, Exception ex) {
        s.failed = true;
        synchronized (this) { if (active == s) active = null; }
        if (s.writer != null) { try { s.writer.close(); } catch (Exception ignored) { } s.writer = null; }
        status = "Full Log ERROR: " + ex.getClass().getSimpleName() + "; partial file: " + s.name;
        try { error.accept(status); } catch (RuntimeException ignored) { }
    }
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number) return Double.isFinite(((Number)value).doubleValue()) ? value.toString() : "null";
        if (value instanceof Boolean) return value.toString();
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32 || Character.isSurrogate(c)) out.append(String.format(Locale.US, "\\u%04x", (int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
    // Test/process teardown only. Normal UI toggles reuse this writer thread.
    void shutdown() { disable("shutdown"); worker.shutdown(); }
    boolean awaitClosed() throws InterruptedException { return worker.awaitTermination(10, TimeUnit.SECONDS); }
}
