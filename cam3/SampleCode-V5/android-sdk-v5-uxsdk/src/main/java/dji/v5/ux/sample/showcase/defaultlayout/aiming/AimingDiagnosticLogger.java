package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** CAM3 v2.1: Bounded, best-effort diagnostics. This class has no flight-control interface. */
public final class AimingDiagnosticLogger implements AutoCloseable {
    public interface Destination { OutputStream open() throws IOException; }
    public interface Result { void complete(boolean success); }
    public static final int FILE_LIMIT = 512 * 1024;
    private final File current, previous;
    private final int limit;
    private final Consumer<String> logcat;
    private final ThreadPoolExecutor worker;
    private final AtomicLong dropped = new AtomicLong();
    private final Map<String, String> signatures = new LinkedHashMap<>();
    private final Map<String, Long> emitted = new HashMap<>();
    private final SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);

    public AimingDiagnosticLogger(File directory, Consumer<String> logcat) {
        this(directory, logcat, FILE_LIMIT, 256);
    }
    // Package-private configuration permits small, deterministic storage/overflow tests.
    AimingDiagnosticLogger(File directory, Consumer<String> logcat, int limit, int capacity) {
        this.current = new File(directory, "aiming-current.log");
        this.previous = new File(directory, "aiming-previous.log");
        this.logcat = logcat; this.limit = limit;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), task -> {
                    Thread t = new Thread(task, "cam3-diagnostic-writer"); t.setDaemon(true); return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        event("logger", "cam3=2.1 sdk=5.18.0 logFormat=1; coordinates omitted; SDK state is not physical stop proof");
    }
    public void event(String type, String detail) { record(type, null, detail, 0); }
    /** Signature excludes advancing ages; unchanged summaries may recur after repeatMs. */
    public void changed(String type, String signature, String detail, long repeatMs) {
        record(type, signature, detail, repeatMs);
    }
    private void record(String type, String signature, String detail, long repeatMs) {
        long wall = System.currentTimeMillis(), mono = System.nanoTime() / 1000000;
        submit(() -> {
            try {
                if (signature != null) {
                    String prior = signatures.get(type);
                    long last = emitted.containsKey(type) ? emitted.get(type) : 0;
                    if (signature.equals(prior) && (repeatMs <= 0 || mono - last < repeatMs)) return;
                    if (signatures.size() >= 128 && !signatures.containsKey(type)) {
                        String first = signatures.keySet().iterator().next(); signatures.remove(first); emitted.remove(first);
                    }
                    signatures.put(type, signature); emitted.put(type, mono);
                }
                long lost = dropped.getAndSet(0);
                String line = date.format(new Date(wall)) + " monoMs=" + mono + " " + clean(type)
                        + " " + clean(detail) + (lost == 0 ? "" : " droppedEntries=" + lost) + "\n";
                try { logcat.accept(line); } catch (RuntimeException ignored) { }
                try { append(line); }
                catch (IOException | RuntimeException ex) {
                    try { logcat.accept("CAM3 log file write failed: " + ex.getClass().getSimpleName()); }
                    catch (RuntimeException ignored) { }
                }
            } catch (RuntimeException ignored) { /* A logger failure cannot affect flight state. */ }
        });
    }
    private static String clean(String value) {
        if (value == null) return "null";
        return value.substring(0, Math.min(value.length(), 4096)).replace('\n', ' ').replace('\r', ' ');
    }
    private boolean submit(Runnable task) {
        try { worker.execute(task); return true; }
        catch (RejectedExecutionException ex) { dropped.incrementAndGet(); return false; }
    }
    private void append(String line) throws IOException {
        File directory = current.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create log directory");
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) bytes = Arrays.copyOf(bytes, limit);
        if (current.length() + bytes.length > limit) {
            if (previous.exists() && !previous.delete()) throw new IOException("Cannot rotate previous log");
            if (current.exists() && !current.renameTo(previous)) throw new IOException("Cannot rotate current log");
        }
        try (FileOutputStream out = new FileOutputStream(current, true)) { out.write(bytes); }
    }
    /** Serialized with writes: export a stable retained snapshot, never read a rotating file on UI/control. */
    public void export(Destination destination, Result result) {
        boolean accepted = submit(() -> {
            boolean success = false;
            try {
                if (!current.isFile() && !previous.isFile()) throw new IOException("No log available");
                try (OutputStream out = destination.open()) {
                    if (out == null) throw new IOException("Destination unavailable");
                    out.write(("cam3=2.1 sdk=5.18.0 logFormat=1 exportedAt=" + date.format(new Date())
                            + "\nRetained diagnostic snapshot; coordinates omitted; SDK callbacks do not prove aircraft response.\n")
                            .getBytes(StandardCharsets.UTF_8));
                    copy(previous, out); copy(current, out); out.flush();
                }
                success = true;
            } catch (IOException | RuntimeException ignored) { }
            try { result.complete(success); } catch (RuntimeException ignored) { }
        });
        if (!accepted) result.complete(false);
    }
    private void copy(File file, OutputStream out) throws IOException {
        if (!file.isFile()) return;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int count, remaining = limit;
            while (remaining > 0 && (count = in.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
                out.write(buffer, 0, count); remaining -= count;
            }
        }
    }
    public long droppedEntries() { return dropped.get(); }
    @Override public void close() { worker.shutdown(); }
    boolean awaitClosed(long seconds) throws InterruptedException { return worker.awaitTermination(seconds, TimeUnit.SECONDS); }
}
