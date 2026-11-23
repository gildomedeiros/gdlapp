package au.com.gildo.fm;

import org.junit.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LiveTrackingTest {
    private static final int CPR = 360_000;
    private static final int PAN_PERIOD = 195, TILT_PERIOD = 38;

    @Test public void gpsUpdatesKeepTheSlewRunningAndLostFeedbackOnlyLogs() throws Exception {
        try (Mount mount = new Mount(List.of(
            reply("f1", "=101"), encoder(1, 100), reply("G130", "="), period(1, PAN_PERIOD), reply("J1", "="),
            encoder(1, 120), reply("j1", null), encoder(1, 142),
            reply("K1", "="), reply("f2", "=101"), encoder(2, 80),
            reply("G220", "="), period(2, TILT_PERIOD), reply("S2" + GoTo.encode(90), "="), reply("J2", "="),
            reply("K3", "=")
        ))) {
            var events = new CopyOnWriteArrayList<String>();
            GoTo client = mount.client((name, fields) -> events.add(name));
            LiveTracking tracking = new LiveTracking(client, CPR, PAN_PERIOD, TILT_PERIOD, () -> true, value -> {});
            var first = new LiveTracking.Targets(1, 1, 120, 85, 1);
            var update = new LiveTracking.Targets(2, 2, 140, 90, 1);
            tracking.tick(first);
            tracking.tick(update);
            tracking.tick(update);
            assertTrue(events.contains("live_pan_feedback_failed"));
            assertEquals(0, mount.commands.stream().filter("K1"::equals).count());
            tracking.tick(update);
            tracking.tick(update);
            client.stopBothMotors();
            mount.verify();
        }
    }

    @Test public void decreasingPanThruWrapStopsOnceAndWaitsForATargetAhead() throws Exception {
        try (Mount mount = new Mount(List.of(
            reply("f1", "=101"), encoder(1, 3), reply("G131", "="), period(1, PAN_PERIOD), reply("J1", "="),
            encoder(1, 0xfffffe), reply("K1", "="),
            reply("f1", "=111"), reply("f1", "=101"), encoder(1, 0xfffffd),
            reply("f1", "=101"), encoder(1, 0xfffffd), reply("G131", "="), period(1, PAN_PERIOD), reply("J1", "="),
            reply("K3", "=")
        ))) {
            GoTo client = mount.client((name, fields) -> {});
            LiveTracking tracking = new LiveTracking(client, CPR, PAN_PERIOD, null, () -> true, value -> {});
            var first = new LiveTracking.Targets(1, 1, 0xffffff, 100, -1);
            var behind = new LiveTracking.Targets(2, 2, 1, 100, -1);
            tracking.tick(first);
            tracking.tick(first);
            tracking.tick(first);
            tracking.tick(behind);
            tracking.tick(behind);
            tracking.tick(new LiveTracking.Targets(3, 3, 0xfffff9, 100, -1));
            client.stopBothMotors();
            mount.verify();
        }
    }

    @Test public void stopRequestedDuringAReplyPreventsTheNextStartCommand() throws Exception {
        try (Mount mount = new Mount(List.of(
            reply("f1", "=101"), encoder(1, 100), reply("G130", "="), reply("K3", "=")
        ))) {
            AtomicBoolean active = new AtomicBoolean(true);
            GoTo client = mount.client((name, fields) -> {
                if (name.equals("gti_rx") && mount.commands.contains("G130")) active.set(false);
            });
            LiveTracking tracking = new LiveTracking(client, CPR, PAN_PERIOD, TILT_PERIOD, active::get, value -> {});
            tracking.tick(new LiveTracking.Targets(1, 1, 120, 90, 1));
            client.stopBothMotors();
            mount.verify();
        }
    }

    @Test public void nullTargetIsIdleAndGpReversalRequiresVerifiedStopBeforeRestart() throws Exception {
        try (Mount mount = new Mount(List.of(
            reply("f1", "=101"), encoder(1, 100), reply("G130", "="), period(1, PAN_PERIOD), reply("J1", "="),
            reply("K1", "="), reply("f1", "=111"), reply("f1", "=101"), encoder(1, 123),
            reply("G131", "="), period(1, PAN_PERIOD), reply("J1", "="), reply("K3", "=")
        ))) {
            GoTo client = mount.client((name, fields) -> {});
            LiveTracking tracking = new LiveTracking(client, CPR, PAN_PERIOD, TILT_PERIOD, () -> true, value -> {});
            tracking.tick(null);
            assertTrue(mount.commands.isEmpty());
            tracking.tick(new LiveTracking.Targets(1, 1, 140, 85, 1));
            var reversed = new LiveTracking.Targets(2, 2, 110, 85, -1);
            tracking.tick(reversed);
            tracking.tick(reversed);
            tracking.tick(reversed);
            client.stopBothMotors();
            mount.verify();
        }
    }

    private record Step(String command, String response) {}

    private static Step reply(String command, String response) { return new Step(command, response); }
    private static Step encoder(int axis, int count) { return reply("j" + axis, "=" + GoTo.encode(count)); }
    private static Step period(int axis, int value) { return reply("I" + axis + GoTo.encode(value), "="); }

    private static final class Mount implements AutoCloseable {
        private final DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        private final Thread thread;
        private final List<Step> script;
        final List<String> commands = new CopyOnWriteArrayList<>();

        Mount(List<Step> script) throws Exception {
            this.script = script;
            thread = new Thread(this::serve);
            thread.setDaemon(true);
            thread.start();
        }

        GoTo client(GoTo.Trace trace) {
            return new GoTo(DatagramSocket::new, (InetSocketAddress)server.getLocalSocketAddress(), trace, true);
        }

        private void serve() {
            try {
                while (!server.isClosed()) {
                    byte[] input = new byte[64];
                    DatagramPacket request = new DatagramPacket(input, input.length);
                    server.receive(request);
                    String command = new String(input, 1, request.getLength() - 2, StandardCharsets.US_ASCII);
                    int index = commands.size();
                    commands.add(command);
                    Step step = index < script.size() ? script.get(index) : null;
                    String response = step != null && step.command().equals(command) ? step.response() : "!0";
                    if (response != null) {
                        byte[] bytes = (response + "\r").getBytes(StandardCharsets.US_ASCII);
                        server.send(new DatagramPacket(bytes, bytes.length, request.getSocketAddress()));
                    }
                }
            } catch (Exception failure) {
                if (!server.isClosed()) throw new AssertionError(failure);
            }
        }

        void verify() { assertEquals(script.stream().map(Step::command).toList(), commands); }

        @Override public void close() throws InterruptedException {
            server.close();
            thread.join(1000);
        }
    }
}

