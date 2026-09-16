package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** CAM3 v2.1: Real file/queue/export tests; no Android device or aircraft required. */
public final class AimingDiagnosticLoggerTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static File directory(File root, String name) throws IOException {
        File file = new File(root, name);
        if (!file.mkdirs()) throw new IOException("Test directory already exists: " + file);
        return file;
    }
    private static boolean export(AimingDiagnosticLogger log, AimingDiagnosticLogger.Destination out) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean();
        log.export(out, success -> { result.set(success); done.countDown(); });
        check(done.await(5, TimeUnit.SECONDS), "export completed");
        return result.get();
    }
    public static void main(String[] args) throws Exception {
        File root = directory(new File(args[0]), "run-" + System.nanoTime());
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        AimingDiagnosticLogger log = new AimingDiagnosticLogger(directory(root, "normal"), lines::add, 8192, 256);
        log.changed("readiness", "airborne=false", "isFlying=false reason=not_airborne age=100", 0);
        log.changed("readiness", "airborne=false", "isFlying=false reason=not_airborne age=200", 0);
        log.changed("readiness", "mode=GPS_TRIPOD", "isFlying=true flightMode=GPS_TRIPOD reason=flight_mode_rejected", 0);
        log.event("state", "session=2 STARTING -> STOPPING");
        log.event("sdk_callback", "operation=enable session=2 success=false error=TEST_ERROR");
        ByteArrayOutputStream snapshot = new ByteArrayOutputStream();
        final ByteArrayOutputStream initial = snapshot;
        check(export(log, () -> initial), "export success");
        String text = snapshot.toString("UTF-8");
        check(text.contains("isFlying=false") && text.contains("flightMode=GPS_TRIPOD"), "raw diagnostic values retained");
        check(text.contains("session=2") && text.contains("TEST_ERROR"), "state and callback correlation retained");
        check(!text.contains("age=200"), "advancing ages alone do not repeat a message");
        // CAM3 v2.2: Export and process log identify the new release.
        // CAM3 v2.3: Verify the new release is identified in its log.
        check(text.contains("monoMs=") && text.contains("cam3=2.4"), "timestamp and build identity");
        check(!text.contains("latitude") && !text.contains("longitude"), "coordinate-free diagnostic fixture");
        check(lines.stream().filter(line -> line.contains("readiness")).count() == 2, "Logcat sink receives changes only");
        log.changed("heartbeat", "same", "periodic summary", 1);
        check(export(log, ByteArrayOutputStream::new), "first heartbeat flushed");
        Thread.sleep(10);
        log.changed("heartbeat", "same", "periodic summary", 1);
        check(export(log, ByteArrayOutputStream::new), "second heartbeat flushed");
        check(lines.stream().filter(line -> line.contains("heartbeat")).count() == 2, "periodic unchanged summary emitted");
        check(!export(log, () -> { throw new IOException("provider denied"); }), "provider failure reported");
        check(!export(log, () -> null), "null destination reported");
        check(!export(log, () -> new OutputStream() {
            @Override public void write(int b) throws IOException { throw new IOException("full"); }
        }), "write failure reported");
        log.close(); check(log.awaitClosed(5), "normal writer terminates");

        File rotating = directory(root, "rotating");
        log = new AimingDiagnosticLogger(rotating, line -> {}, 1024, 256);
        for (int i = 0; i < 60; i++) log.event("event", "number=" + i + " " + String.join("", Collections.nCopies(120, "x")));
        snapshot = new ByteArrayOutputStream(); final ByteArrayOutputStream rotated = snapshot;
        check(export(log, () -> rotated), "rotated export success");
        check(snapshot.size() <= 2304 && snapshot.toString("UTF-8").contains("number=59"), "rotation retains bounded latest data plus export header");
        check(new File(rotating, "aiming-current.log").length() <= 1024
                && new File(rotating, "aiming-previous.log").length() <= 1024, "both files bounded");
        log.close(); check(log.awaitClosed(5), "rotating writer terminates");

        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        log = new AimingDiagnosticLogger(directory(root, "slow"), line -> {
            blocked.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }, 8192, 4);
        check(blocked.await(2, TimeUnit.SECONDS), "writer deliberately blocked");
        long began = System.nanoTime();
        for (int i = 0; i < 1000; i++) log.event("queued", "entry=" + i);
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 2000, "full queue never waits for writer");
        check(log.droppedEntries() > 0, "overflow recorded");
        AtomicBoolean rejected = new AtomicBoolean();
        log.export(ByteArrayOutputStream::new, success -> rejected.set(!success));
        check(rejected.get(), "export queue overflow reported immediately");
        release.countDown(); log.close(); check(log.awaitClosed(5), "blocked writer released");

        File badDirectory = new File(root, "not-a-directory");
        Files.write(badDirectory.toPath(), "file".getBytes(StandardCharsets.UTF_8));
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        log = new AimingDiagnosticLogger(badDirectory, failures::add, 1024, 16);
        log.event("failure", "storage cannot be opened");
        check(!export(log, ByteArrayOutputStream::new), "missing log file export fails honestly");
        log.close(); check(log.awaitClosed(5), "failed storage does not kill writer");
        check(failures.stream().anyMatch(line -> line.contains("write failed")), "storage error reaches Logcat sink");

        File sinkFailure = directory(root, "sink-failure");
        log = new AimingDiagnosticLogger(sinkFailure, line -> { throw new IllegalStateException(); }, 8192, 16);
        log.event("still_saved", "Logcat failure is isolated");
        ByteArrayOutputStream saved = new ByteArrayOutputStream();
        check(export(log, () -> saved) && saved.toString("UTF-8").contains("still_saved"), "Logcat failure does not prevent local logging");
        log.close(); check(log.awaitClosed(5), "failed sink writer terminates");
        System.out.println("PASS: " + checks + " diagnostic assertions (contents, suppression, rotation, export, overflow, I/O failures)");
    }
}
