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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LiveTrackingTest {
    private static final int CPR = 360_000;
    private static final GoTo.PanTiming TIMING = new GoTo.PanTiming(652_000, 1);
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
            LiveTracking tracking = new LiveTracking(client, CPR, TIMING, TILT_PERIOD, false, () -> true, value -> {});
            var first = new LiveTracking.Targets(1, 1, 120, 85, 1, 20);
            var update = new LiveTracking.Targets(2, 2, 140, 90, 1, 20);
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
            LiveTracking tracking = new LiveTracking(client, CPR, TIMING, null, false, () -> true, value -> {});
            var first = new LiveTracking.Targets(1, 1, 0xffffff, 100, -1, 20);
            var behind = new LiveTracking.Targets(2, 2, 1, 100, -1, 20);
            tracking.tick(first);
            tracking.tick(first);
            tracking.tick(first);
            tracking.tick(behind);
            tracking.tick(behind);
            tracking.tick(new LiveTracking.Targets(3, 3, 0xfffff9, 100, -1, 20));
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
            LiveTracking tracking = new LiveTracking(client, CPR, TIMING, TILT_PERIOD, false, active::get, value -> {});
            tracking.tick(new LiveTracking.Targets(1, 1, 120, 90, 1, 20));
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
            LiveTracking tracking = new LiveTracking(client, CPR, TIMING, TILT_PERIOD, false, () -> true, value -> {});
            tracking.tick(null);
            assertTrue(mount.commands.isEmpty());
            tracking.tick(new LiveTracking.Targets(1, 1, 140, 85, 1, 20));
            var reversed = new LiveTracking.Targets(2, 2, 110, 85, -1, 20);
            tracking.tick(reversed);
            tracking.tick(reversed);
            tracking.tick(reversed);
            client.stopBothMotors();
            mount.verify();
        }
    }

    @Test public void distanceSelectsSpeedOnlyOnAnExistingStopAndTiltCanBeDisabled() throws Exception {
        // 100 m and 200 m are different restart rates. The first move must still use maximum speed.
        int restartPeriod = TIMING.period(AimAtSurfer.siderealForRange(200) * CPR / GoTo.SIDEREAL_DAY_SECONDS);
        try (Mount mount = new Mount(List.of(
            reply("f1", "=101"), encoder(1, 100), reply("G130", "="), period(1, PAN_PERIOD), reply("J1", "="),
            encoder(1, 105), encoder(1, 141), reply("K1", "="),
            reply("f1", "=111"), reply("f1", "=101"), encoder(1, 145),
            reply("G130", "="), period(1, restartPeriod), reply("J1", "=")
        ))) {
            var tracker = new LiveTracking(mount.client((name, fields) -> {}), CPR, TIMING, TILT_PERIOD,
                true, () -> true, value -> {});
            tracker.tick(new LiveTracking.Targets(1, 1, 120, null, 1, 100));
            var duringPan = new LiveTracking.Targets(2, 2, 140, null, 1, 300);
            tracker.tick(duringPan);
            tracker.tick(duringPan);
            var restart = new LiveTracking.Targets(3, 3, 175, null, 1, 200);
            tracker.tick(restart);
            tracker.tick(restart);
            mount.verify();
        }
    }

    @Test public void startupHoldsFirstTargetAgainstGpDirectionThenFollowsTheNewestFix() throws Exception {
        int restart=TIMING.period(AimAtSurfer.siderealForRange(200)*CPR/GoTo.SIDEREAL_DAY_SECONDS);
        try(Mount mount=new Mount(List.of(
            reply("f1","=101"),encoder(1,100),reply("G131","="),period(1,PAN_PERIOD),reply("J1","="),
            encoder(1,90),encoder(1,79),reply("K1","="),
            reply("f1","=101"),encoder(1,78),reply("G130","="),period(1,restart),reply("J1","=")))) {
            var events=new CopyOnWriteArrayList<String>();
            var tracker=new LiveTracking(mount.client((name,fields)->events.add(name)),CPR,TIMING,null,true,()->true,v->{});
            var first=new LiveTracking.Targets(1,1,80,null,1,200);
            var next=new LiveTracking.Targets(2,2,120,null,1,200);
            tracker.track(null,true);
            tracker.offerStartupTarget(first.packet(),first.pan(),first.surferRangeM(),Long.MAX_VALUE);
            tracker.track(next,false);
            tracker.track(next,false);
            tracker.track(next,false);
            tracker.track(next,false);
            assertEquals(1,events.stream().filter("live_startup_aim_complete"::equals).count());
            mount.verify();
        }
    }

    @Test public void exactMatchSkipsStartupAndProximityPauseResumesWithoutAnotherStartup() throws Exception {
        try(Mount mount=new Mount(List.of(
            reply("f1","=101"),encoder(1,100),
            reply("f1","=101"),encoder(1,100),reply("G130","="),period(1,PAN_PERIOD),reply("J1","="),
            reply("K1","="),reply("f1","=101"),encoder(1,115)))) {
            var tracker=new LiveTracking(mount.client((n,f)->{}),CPR,TIMING,null,true,()->true,v->{});
            tracker.offerStartupTarget(1,100,100,Long.MAX_VALUE);
            tracker.track(null,false);
            tracker.track(new LiveTracking.Targets(2,2,150,null,1,100),false);
            tracker.track(null,true);
            tracker.track(null,true);
            tracker.track(null,false);
            tracker.track(new LiveTracking.Targets(3,3,80,null,1,100),false);
            mount.verify();
        }
    }

    @Test public void failedStartupSetterDoesNotRetryOnTheSamePublication() throws Exception {
        try (Mount mount=new Mount(List.of(reply("f1","=101"),encoder(1,100),reply("G131","!0")))) {
            var tracker=new LiveTracking(mount.client((n,f)->{}),CPR,TIMING,null,true,()->true,v->{});
            var first=new LiveTracking.Targets(1,1,80,null,1,200);
            tracker.offerStartupTarget(first.packet(),first.pan(),first.surferRangeM(),Long.MAX_VALUE);
            tracker.track(null,false);
            tracker.track(null,false);
            mount.verify();
        }
    }

    @Test public void stationaryLoRaAimsOnceWithNoDirectionPublication() throws Exception {
        var pivot=new GoTo.Point(-28.07012,153.3870,0);
        var gp=new LiveGpsFilter(pivot,4,2000,2000,35,(event,values)->{});
        var source=new LiveGpsSource(0);
        var gate=new LiveRangeGate(pivot,5);
        var events=new CopyOnWriteArrayList<String>();
        try(Mount mount=new Mount(List.of(reply("f1","=101"),encoder(1,100),reply("G131","="),
            period(1,PAN_PERIOD),reply("J1","="),encoder(1,80),reply("K1","=")))) {
            var tracker=new LiveTracking(mount.client((name,fields)->events.add(name)),CPR,TIMING,null,true,()->true,s->{});
            for(int i=0;i<89;i++) {
                var packet=LoRaTelemetry.parse("RX,GPS,"+(2121+i)+","+(1000+500*i)+",-28.07012,153.38716,25,0.6,-75,10,0");
                var position=source.toFilterInput(packet,500L*i,pivot,0);
                gp.accept(position,500L*i);
                gate.observe(position,tracker.needsStartupTarget());
                var first=gate.takeFirst();
                if(first!=null) tracker.offerStartupTarget(first.packet().sequence,80,16,Long.MAX_VALUE);
                assertNull(gp.latest());
                tracker.track(null,gate.inside());
            }
            assertEquals(1,events.stream().filter("live_pan_start_sent"::equals).count());
            assertEquals(1,events.stream().filter("live_startup_aim_complete"::equals).count());
            mount.verify();
        }
    }

    @Test public void expiredStartupWaitsForAnotherFix() throws Exception {
        try(Mount mount=new Mount(List.of(reply("f1","=101"),encoder(1,100)))) {
            var tracker=new LiveTracking(mount.client((n,f)->{}),CPR,TIMING,null,true,()->true,s->{});
            tracker.offerStartupTarget(1,99,10,-1);
            tracker.track(null,false);
            assertTrue(tracker.needsStartupTarget());
            tracker.offerStartupTarget(2,100,10,Long.MAX_VALUE);
            tracker.track(null,false);
            assertFalse(tracker.startupPending());
            mount.verify();
        }
    }

    @Test public void finalStopRetriesOnlyUnconfirmedAxesEvenWhenOneStopIsRejected() throws Exception {
        try(Mount mount=new Mount(List.of(reply("K1","!0"),reply("K2","="),reply("f1","=101"),
            reply("f2","=111"),reply("K2","="),reply("f2","!0"),reply("K2","="),reply("f2","=101")))) {
            assertTrue(LiveTracking.awaitFullStop(mount.client((n,f)->{}),()->true));
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

