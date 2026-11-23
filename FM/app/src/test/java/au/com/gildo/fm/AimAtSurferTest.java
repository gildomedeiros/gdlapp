package au.com.gildo.fm;

import org.junit.Test;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

public class AimAtSurferTest {
    private static final GoTo.PanTiming TIMING = new GoTo.PanTiming(10_000, 16);
    private static final int CPR = 360_000;

    @Test public void stopsAtFirstCrossingAcrossEncoderWrapAndReadsFinalPosition() throws Exception {
        try (Mount mount = new Mount(List.of(
            step("f1", "=101"), encoder(0xfffff0), step("G130", "="), speed(), step("J1", "="),
            encoder(0xfffffa), encoder(6), step("K1", "="), step("f1", "=111"), step("f1", "=101"), encoder(9)
        ))) {
            AimAtSurfer.Attempt attempt = run(mount, 4);
            assertEquals("Target crossed", attempt.outcome);
            assertNull(attempt.issue);
            assertEquals(2, attempt.encoderPolls);
            assertEquals(2, attempt.statusPolls);
            assertEquals(Integer.valueOf(5), attempt.errorCounts);
            assertEquals(Integer.valueOf(3), attempt.coastCounts);
            assertEquals(Geo.panGapM(175, 5, CPR), attempt.finalGapM, 1e-12);
            assertNotNull(attempt.startAck);
            assertNotNull(attempt.stopped);
            mount.verify();
        }
    }

    @Test public void decreasingPanStopsAtOrBelowTheTarget() throws Exception {
        try (Mount mount = new Mount(List.of(
            step("f1", "=101"), encoder(12), step("G131", "="), speed(), step("J1", "="),
            encoder(8), encoder(2), step("K1", "="), step("f1", "=101"), encoder(0)
        ))) {
            AimAtSurfer.Attempt attempt = run(mount, 2);
            assertEquals("Target crossed", attempt.outcome);
            assertEquals(Integer.valueOf(-2), attempt.errorCounts);
            mount.verify();
        }
    }

    @Test public void aLostStartReplyStopsOnceAndDoesNotRestart() throws Exception {
        try (Mount mount = new Mount(List.of(
            step("f1", "=101"), encoder(20), step("G130", "="), speed(), step("J1", null),
            step("K1", "="), step("f1", "=101"), encoder(21)
        ))) {
            AimAtSurfer.Attempt attempt = run(mount, 24);
            assertEquals("Start issue", attempt.outcome);
            assertTrue(attempt.issue.contains("timed out"));
            assertNull(attempt.startAck);
            assertNotNull(attempt.stopAck);
            assertEquals(0, attempt.encoderPolls);
            mount.verify();
        }
    }

    @Test public void aRejectedSpeedSetterEndsBeforeStartOrStop() throws Exception {
        try (Mount mount = new Mount(List.of(
            step("f1", "=101"), encoder(20), step("G130", "="), step("I1C30000", "!0")
        ))) {
            IOException failure = assertThrows(IOException.class, () -> run(mount, 24));
            assertTrue(failure.getMessage().contains(":I1"));
            mount.verify();
        }
    }

    @Test public void anInvalidEncoderReplyStopsOnceAndConfirmsRest() throws Exception {
        try (Mount mount = new Mount(List.of(
            step("f1", "=101"), encoder(20), step("G130", "="), speed(), step("J1", "="),
            step("j1", "=FF"), step("K1", "!0"), step("f1", "=101"), encoder(23)
        ))) {
            AimAtSurfer.Attempt attempt = run(mount, 24);
            assertEquals("Pan feedback failed", attempt.outcome);
            assertTrue(attempt.issue.contains("Stop reply unusable"));
            assertNotNull(attempt.stopped);
            assertEquals(Integer.valueOf(23), attempt.finalCount);
            mount.verify();
        }
    }

    @Test public void anUnusableStopStatusEndsTheCheckWithoutRetrying() throws Exception {
        try (Mount mount = new Mount(List.of(
            step("f1", "=101"), encoder(20), step("G130", "="), speed(), step("J1", "="),
            encoder(24), step("K1", "="), step("f1", "=1234")
        ))) {
            AimAtSurfer.Attempt attempt = run(mount, 24);
            assertTrue(attempt.issue.contains("Full stop unconfirmed"));
            assertEquals(1, attempt.statusPolls);
            assertNull(attempt.finalGapM);
            mount.verify();
        }
    }

    @Test public void alreadyAimedUsesTheBaselineAndSkipsSpeedSelection() throws Exception {
        try (Mount mount = new Mount(List.of(step("f1", "=101"), encoder(20)))) {
            AimAtSurfer.Attempt attempt = attempt(20);
            AimAtSurfer.run(attempt, mount.client(attempt), TIMING, CPR, CPR, () -> true);
            assertEquals("Already aimed", attempt.outcome);
            assertEquals(0, attempt.finalGapM, 0);
            assertNull(attempt.startSent);
            assertNull(attempt.sidereal);
            mount.verify();
        }
    }

    @Test public void choosesHsSpeedFromRangeWithinTheSlewBounds() {
        assertEquals(800, AimAtSurfer.siderealForRange(7.39), 0);
        assertEquals(228.56, AimAtSurfer.siderealForRange(150), .01);
        assertEquals(171.42, AimAtSurfer.siderealForRange(200), .01);
        assertEquals(128, AimAtSurfer.siderealForRange(300), 0);
    }

    @Test public void halfTurnUsesIncreasingCountsAndRateMeasurementsExcludeStartup() {
        assertEquals(CPR / 2, AimAtSurfer.shortestStep(0, CPR / 2, CPR));
        HighSpeedReplay.BenchSegment bench = new HighSpeedReplay.BenchSegment(0);
        bench.sample(100, 0);
        bench.sample(200, 100);
        bench.sample(400, 500);
        assertEquals(2000, bench.measuredRate(1), 0);
    }

    @Test public void panStopAckThenTiltGotoThenPanVerificationUsesOneCommandStream() throws Exception {
        try (Mount mount = new Mount(List.of(
            step("f1","=101"), encoder(20), step("G130","="), speed(), step("J1","="), encoder(24),
            step("K1","="), step("f2","=101"), tiltEncoder(40), step("G220","="),
            step("S2"+GoTo.encode(45),"="), step("J2","="),
            step("f1","=111"), step("f1","=101"), encoder(25)
        ))) {
            List<WireLog> wire = new ArrayList<>();
            AimAtSurfer.Attempt aim = withTilt(24);
            aim.log = (event, fields) -> captureWire(wire, 1, event, fields);
            aim.tilt.log = (event, fields) -> captureWire(wire, 2, event, fields);
            AimAtSurfer.run(aim, mount.client(aim::mountEvent), TIMING, CPR, CPR, () -> true);
            assertEquals("Start accepted", aim.tilt.outcome);
            assertNotNull(aim.tilt.startAck);
            assertTrue(aim.tilt.startSent >= aim.stopAck);
            assertTrue(aim.stopped >= aim.tilt.startAck);
            assertEquals(Integer.valueOf(25), aim.finalCount);
            assertEquals(2, aim.statusPolls);
            assertEquals(0, aim.tilt.encoderPolls);
            assertEquals(0, aim.tilt.statusPolls);
            mount.verify();
            assertEquals(mount.commands.size(), mount.ports.stream().distinct().count());
            assertEquals(mount.commands.size() * 2, wire.size());
            for (int i = 0; i < mount.commands.size(); i++) {
                String command = mount.commands.get(i);
                int axis = Character.digit(command.charAt(1), 10);
                int port = mount.ports.get(i);
                assertEquals(new WireLog("gti_tx", i + 1, axis, port), wire.get(2 * i));
                assertEquals(new WireLog("gti_rx", i + 1, axis, port), wire.get(2 * i + 1));
            }
        }
    }

    @Test public void rejectedPanStopSkipsTiltAndStillChecksPanFullStop() throws Exception {
        try (Mount mount = new Mount(List.of(
            step("f1","=101"), encoder(20), step("G130","="), speed(), step("J1","="), encoder(24),
            step("K1","!0"), step("f1","=101"), encoder(25)
        ))) {
            AimAtSurfer.Attempt aim = runWithTilt(mount, 24);
            assertEquals("Not sent", aim.tilt.outcome);
            assertTrue(aim.tilt.issue.contains("Pan Stop acknowledgment unavailable"));
            assertNull(aim.tilt.startSent);
            assertNotNull(aim.stopped);
            mount.verify();
        }
    }

    @Test public void rejectedTiltTargetSkipsTiltStartAndStillChecksPanFullStop() throws Exception {
        try (Mount mount = new Mount(List.of(
            step("f1","=101"), encoder(20), step("G130","="), speed(), step("J1","="), encoder(24),
            step("K1","="), step("f2","=101"), tiltEncoder(40), step("G220","="),
            step("S2"+GoTo.encode(45),"!0"), step("f1","=101"), encoder(25)
        ))) {
            AimAtSurfer.Attempt aim = runWithTilt(mount, 24);
            assertEquals("Blocked before Start", aim.tilt.outcome);
            assertTrue(aim.tilt.issue.contains(":S2"));
            assertNull(aim.tilt.startSent);
            assertNotNull(aim.stopped);
            mount.verify();
        }
    }

    @Test public void lostTiltStartReplyIsReportedBeforePanVerification() throws Exception {
        try (Mount mount = new Mount(List.of(
            step("f1","=101"), encoder(20), step("G130","="), speed(), step("J1","="), encoder(24),
            step("K1","="), step("f2","=101"), tiltEncoder(40), step("G220","="),
            step("S2"+GoTo.encode(45),"="), step("J2",null), step("f1","=101"), encoder(25)
        ))) {
            AimAtSurfer.Attempt aim = runWithTilt(mount, 24);
            assertEquals("Start unconfirmed", aim.tilt.outcome);
            assertTrue(aim.tilt.issue.contains(":J2 reply timed out"));
            assertNotNull(aim.tilt.startSent);
            assertNull(aim.tilt.startAck);
            assertNotNull(aim.stopped);
            mount.verify();
        }
    }

    @Test public void alreadyAimedPanLetsTiltStartWithoutPanMotion() throws Exception {
        try (Mount mount = new Mount(List.of(step("f1","=101"), encoder(20),
            step("f2","=101"), tiltEncoder(40), step("G220","="), step("S2"+GoTo.encode(45),"="), step("J2","=")
        ))) {
            AimAtSurfer.Attempt aim = runWithTilt(mount, 20);
            assertEquals("Already aimed", aim.outcome);
            assertEquals("Start accepted", aim.tilt.outcome);
            assertEquals(aim.tilt.startAck, aim.firstStartReply());
            assertEquals(Integer.valueOf(20), aim.finalCount);
            mount.verify();
        }
    }

    @Test public void derivesTiltFromHeightDifferenceAndUsesCalibrationSign() {
        GoTo.Reference reference=new GoTo.Reference(new GoTo.Point(0,0,4),80,0,20,100_000,1,-1);
        AimAtSurfer.Target target=AimAtSurfer.tiltTarget(0,reference,25,CPR);
        assertEquals(-9.09028,target.angleDeg(),0.0001);
        assertEquals(109_090,target.count());
    }

    private static AimAtSurfer.Attempt attempt(int target) {
        AimAtSurfer.Attempt attempt = new AimAtSurfer.Attempt(GoTo.now(), message -> {});
        attempt.target = new AimAtSurfer.Target(target, 175, 90);
        return attempt;
    }

    private static AimAtSurfer.Attempt run(Mount mount, int target) throws IOException {
        AimAtSurfer.Attempt attempt = attempt(target);
        AimAtSurfer.run(attempt, mount.client(attempt), TIMING, CPR, CPR, () -> true);
        return attempt;
    }

    private static AimAtSurfer.Attempt runWithTilt(Mount mount, int panTarget) throws IOException {
        AimAtSurfer.Attempt aim = withTilt(panTarget);
        AimAtSurfer.run(aim, mount.client(aim::mountEvent), TIMING, CPR, CPR, () -> true);
        return aim;
    }

    private static AimAtSurfer.Attempt withTilt(int panTarget) {
        AimAtSurfer.Attempt aim = attempt(panTarget);
        aim.tilt = new AimAtSurfer.Attempt(aim.press, AimAtSurfer.Axis.TILT, message -> {});
        aim.tilt.target = new AimAtSurfer.Target(45, 175, -5);
        return aim;
    }

    private static void captureWire(List<WireLog> wire, int axis, String event, Object[] fields) {
        if (!event.equals("gti_tx") && !event.equals("gti_rx")) return;
        long id = -1;
        int port = -1;
        for (int i = 0; i < fields.length; i += 2) {
            switch ((String)fields[i]) {
                case "exchange" -> id = (Long)fields[i + 1];
                case "localPort" -> port = (Integer)fields[i + 1];
                case "axis" -> assertEquals(axis, ((Integer)fields[i + 1]).intValue());
            }
        }
        wire.add(new WireLog(event, id, axis, port));
    }

    private record WireLog(String event, long id, int axis, int port) {}

    private static Step tiltEncoder(int count) { return step("j2", "=" + GoTo.encode(count)); }
    private static Step encoder(int count) { return step("j1", "=" + GoTo.encode(count)); }
    private static Step speed() { return step("I1C30000", "="); }
    private static Step step(String command, String response) { return new Step(command, response); }
    private record Step(String command, String response) {}

    private static final class Mount implements AutoCloseable {
        private final DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        private final List<Step> script;
        private final List<String> commands = new CopyOnWriteArrayList<>();
        private final List<Integer> ports = new CopyOnWriteArrayList<>();
        private final Thread thread;

        Mount(List<Step> script) throws IOException {
            this.script = script;
            thread = new Thread(this::serve);
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            byte[] buffer = new byte[64];
            try {
                while (!server.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    server.receive(packet);
                    String wire = new String(buffer, 0, packet.getLength(), StandardCharsets.US_ASCII);
                    String command = wire.substring(1, wire.length() - 1);
                    int index = commands.size();
                    commands.add(command);
                    ports.add(packet.getPort());
                    Step expected = index < script.size() ? script.get(index) : null;
                    String response = expected != null && expected.command().equals(command) ? expected.response() : "!0";
                    if (response != null) {
                        byte[] bytes = (response + "\r").getBytes(StandardCharsets.US_ASCII);
                        server.send(new DatagramPacket(bytes, bytes.length, packet.getSocketAddress()));
                    }
                }
            } catch (IOException failure) {
                if (!server.isClosed()) throw new AssertionError(failure);
            }
        }

        GoTo client(GoTo.Trace trace) {
            return new GoTo(DatagramSocket::new, (InetSocketAddress)server.getLocalSocketAddress(), trace, true);
        }

        void verify() { assertEquals(script.stream().map(Step::command).toList(), commands); }

        @Override public void close() throws InterruptedException {
            server.close();
            thread.join(1000);
        }
    }
}
