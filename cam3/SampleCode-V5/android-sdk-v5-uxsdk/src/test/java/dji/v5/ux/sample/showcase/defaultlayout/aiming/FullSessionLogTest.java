package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class FullSessionLogTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void settled(FullSessionLog log) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (log.busy() && System.nanoTime() < until) Thread.sleep(5);
        check(!log.busy(), "Writer did not settle");
    }
    private static LoRaTelemetry packet(long seq) {
        return LoRaTelemetry.parse("RX,GPS,"+seq+","+(seq*500)+",-28.1,153.2,9,0.8,-100,2.5,0");
    }
    public static void main(String[] args) throws Exception {
        List<ByteArrayOutputStream> files = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        FullSessionLog log = new FullSessionLog(name -> {
            names.add(name); ByteArrayOutputStream out = new ByteArrayOutputStream(); files.add(out); return out;
        }, errors::add);
        log.record("off", "latitude", 99); check(!log.enabled(), "Default OFF");
        log.enable(); log.record("packet", "latitude", -28.1, "longitude", 153.2,
                "raw", "bad\n\"\\\tdata", "missingAccuracy", Double.NaN);
        log.disable("user_off"); log.record("off_after", "latitude", 99); settled(log);
        log.enable(); log.record("second", "ok", true); log.disable("screen_closed"); settled(log);
        log.shutdown(); check(log.awaitClosed(), "Writer drain");
        check(files.size()==2 && !names.get(0).equals(names.get(1)), "Unique session files");
        String first = files.get(0).toString("UTF-8"), second = files.get(1).toString("UTF-8");
        check(first.contains("\"latitude\":-28.1") && first.contains("\"missingAccuracy\":null"), "Coordinates and valid nonfinite JSON");
        check(first.contains("bad\\u000a\\\"\\\\\\u0009data"), "Raw text escaping");
        check(first.contains("session_start") && first.contains("session_end") && !first.contains("off_after"), "Toggle boundary");
        check(!second.contains("packet") && second.contains("screen_closed"), "No cross-session leakage");
        check(errors.isEmpty(), "Successful writes");
        if (args.length>0) Files.write(Paths.get(args[0], "full-log-test.jsonl"), first.getBytes(StandardCharsets.UTF_8));

        CountDownLatch open = new CountDownLatch(1);
        ByteArrayOutputStream overflowFile = new ByteArrayOutputStream();
        FullSessionLog overflow = new FullSessionLog(name -> {
            try { if (!open.await(10, TimeUnit.SECONDS)) throw new IOException("timeout"); }
            catch (InterruptedException e) { throw new IOException(e); }
            return overflowFile;
        }, errors::add, 2);
        overflow.enable();
        for (int i=0;i<8;i++) overflow.record("packet", "sequence", i);
        overflow.disable("off_while_opening"); open.countDown(); settled(overflow);
        overflow.shutdown(); check(overflow.awaitClosed(), "Overflow drain");
        String over = overflowFile.toString("UTF-8");
        check(over.contains("\"droppedEntries\":6") && over.contains("session_end"), "Overflow reported and close never dropped");
        check(overflow.status().contains("dropped full-log entries=6"), "Visible overflow warning");

        FullSessionLog failure = new FullSessionLog(name -> { throw new IOException("disk unavailable"); }, errors::add);
        failure.enable(); settled(failure);
        check(!failure.enabled() && failure.status().contains("ERROR"), "Open failure turns OFF visibly");
        failure.shutdown(); check(failure.awaitClosed(), "Failure shutdown");
        FullSessionLog writeFailure = new FullSessionLog(name -> new OutputStream() {
            public void write(int b) throws IOException { throw new IOException("disk full"); }
        }, errors::add);
        writeFailure.enable(); settled(writeFailure);
        check(!writeFailure.enabled() && writeFailure.status().contains("ERROR"), "Write failure handled");
        writeFailure.shutdown(); check(writeFailure.awaitClosed(), "Write failure shutdown");

        LoRaTelemetry.Tracker tracker = new LoRaTelemetry.Tracker(); int gapEvents=0;
        for (long seq : new long[]{1,2,3,7,8}) {
            long prior=tracker.lastSequence(), before=tracker.gaps;
            check(tracker.accept(packet(seq), seq*500), "Forward accepted");
            if (tracker.gaps>before) {
                gapEvents++; check(prior==3 && seq==7 && tracker.gaps-before==3, "Exact 3-to-7 gap");
            }
        }
        check(gapEvents==1 && tracker.gaps==3, "Single gap event for 1,2,3,7,8");
        check(!tracker.accept(packet(8), 5000) && tracker.gaps==3, "Duplicate does not recount gaps");
        for (String event : new String[]{"lora_gap","lora_rejected","phone_invalid","lora_connection","state","pause","resumed","full_log_error"})
            check(MinimalLogPolicy.keep(event,"reason"), "Minimal retains "+event);
        for (String event : new String[]{"lora_packet","readiness","blockers","authority_observed","first_command"})
            check(!MinimalLogPolicy.keep(event,"routine"), "Minimal excludes "+event);
        check(MinimalLogPolicy.keep("telemetry_retry_timeout_heading","request=3"), "Read timeouts retained");
        check(!MinimalLogPolicy.keep("telemetry_retry_result_heading","error=none"), "Successful read noise excluded");
        System.out.println("PASS: full session boundaries, JSON, coordinates, gaps, overflow, storage failures and minimal policy");
    }
}
