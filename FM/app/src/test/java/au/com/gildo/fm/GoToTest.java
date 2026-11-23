package au.com.gildo.fm;

import org.junit.Test;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.Assert.*;

public class GoToTest {
    private static final GoTo.Reference REFERENCE=new GoTo.Reference(
        new GoTo.Point(0,0,10),80,0,0x800000,0x800000,1,1);
    private static final GoTo.Point TARGET=new GoTo.Point(0,0.001,0);
    private static final GoTo.MountSetup SETUP=new GoTo.MountSetup(360000,360000,"0336C5","0336C5");

    @Test public void bothTargetsAcknowledgedBeforeStartAndFinishAfterReplies() throws Exception {
        try (Mount mount=new Mount(null)) {
            long[] lastReply={-1};
            GoTo client=mount.client((event,fields)->{
                if(event.equals("gti_rx"))
                    for(int i=0;i<fields.length;i+=2)
                        if(fields[i].equals("rxMs")) lastReply[0]=(Long)fields[i+1];
            });
            GoTo.PositionResult position=client.goToPosition(SETUP,TARGET,REFERENCE,()->true,GoTo.now());
            String result=position.report();
            assertEquals(Long.valueOf(lastReply[0]),position.bothStartsAcknowledgedAtMs());
            assertTrue(result,result.startsWith("GoTo sent"));
            assertTrue(result,result.contains("GTi pan "));
            assertTrue(result,result.contains("Pan Start reply "));
            assertTrue(result,result.contains("Tilt Start reply "));
            assertTrue(result,result.contains("Press to result "));
            assertEquals(java.util.List.of("j1","j2","f1","f2","G1","S1","G2","S2","J1","J2"),
                mount.commands.stream().map(command->command.substring(0,2)).collect(java.util.stream.Collectors.toList()));
            assertTrue(mount.index("S1")<mount.index("J1"));
            assertTrue(mount.index("S2")<mount.index("J1"));
            assertTrue(mount.index("J1")<mount.index("J2"));
            assertEquals(2,mount.commands.stream().filter(c->c.startsWith("J")).count());
            assertFalse(mount.commands.stream().anyMatch(c->c.matches("[aFe][12]")));
        }
    }

    @Test public void gotoAcceptsMovesBeyondTheOldTravelLimits() throws Exception {
        GoTo.Reference north=new GoTo.Reference(new GoTo.Point(0,0,0),0,0,0x800000,0x800000,1,1);
        try(Mount mount=new Mount(null)) {
            String result=mount.client().goToPosition(SETUP,TARGET,north,()->true,GoTo.now()).report();
            assertTrue(result,result.startsWith("GoTo sent"));
            assertTrue(mount.commands.contains("S1905F81"));
            assertTrue(mount.commands.contains("J1"));
        }
    }

    @Test public void dispatchesBothAxesEvenWhenAlreadyAtTheTarget() throws Exception {
        GoTo.Reference onTarget=new GoTo.Reference(
            new GoTo.Point(0,0,0),90,0,0x800000,0x800000,1,1);
        try(Mount mount=new Mount(null)) {
            String result=mount.client().goToPosition(SETUP,TARGET,onTarget,()->true,GoTo.now()).report();
            assertTrue(result,result.startsWith("GoTo sent"));
            assertTrue(result,result.contains("Arrival unverified"));
            assertEquals(java.util.List.of("j1","j2","f1","f2","G120","S1000080","G220","S2000080","J1","J2"),
                mount.commands);
        }
    }

    @Test public void lostStartReplyEndsWithoutPollingOrResending() throws Exception {
        for(String axis:new String[]{"J1","J2"}) {
            try(Mount mount=new Mount("timeout-"+axis)) {
                GoTo.PositionResult position=mount.client().goToPosition(SETUP,TARGET,REFERENCE,()->true,GoTo.now());
                String result=position.report();
                assertNull(position.bothStartsAcknowledgedAtMs());
                assertTrue(result,result.contains(":"+axis+" reply timed out"));
                assertTrue(result,result.contains(axis.equals("J1")?"0 of 2":"1 of 2"));
                java.util.List<String> expected=new java.util.ArrayList<>(java.util.List.of(
                    "j1","j2","f1","f2","G1","S1","G2","S2","J1"));
                if(axis.equals("J2")) expected.add("J2");
                assertEquals(expected,mount.commands.stream().map(command->command.substring(0,2))
                    .collect(java.util.stream.Collectors.toList()));
            }
        }
    }

    @Test public void rejectedOrMissingTargetPreventsEitherStart() throws Exception {
        for(String failure:new String[]{"reject","timeout"}) {
            try(Mount mount=new Mount(failure)) {
                try {
                    mount.client().goToPosition(SETUP,TARGET,REFERENCE,()->true,GoTo.now());
                    fail("Expected target failure");
                } catch(IOException expected) {
                    assertTrue(expected.toString(),expected.getMessage().contains(":S2"));
                }
                assertTrue(mount.index("S2")>=0);
                assertFalse(mount.commands.stream().anyMatch(c->c.startsWith("J")));
            }
        }
    }

    @Test public void expiredFixPreventsStartEvenIfBothTargetsAccepted() throws Exception {
        try(Mount mount=new Mount(null)) {
            try {
                mount.client().goToPosition(SETUP,TARGET,REFERENCE,()->false,GoTo.now());
                fail("Expected cancellation");
            } catch(IOException expected) {
                assertTrue(expected.getMessage().contains("before Start"));
            }
            assertTrue(mount.index("S2")>=0);
            assertFalse(mount.commands.stream().anyMatch(c->c.startsWith("J")));
        }
    }

    @Test public void calibrationReadsEachEncoderExactlyOnce() throws Exception {
        try(Mount mount=new Mount(null)) {
            mount.positions[1]=0x800012;
            GoTo.Encoders captured=mount.client().captureReference();
            assertEquals(new GoTo.Encoders(0x800000,0x800012),captured);
            assertEquals(java.util.List.of("j1","j2"),mount.commands);
        }
    }

    @Test public void connectionRejectsAColdPanWhenTiltIsMoving() throws Exception {
        try(Mount mount=new Mount("moving")) {
            try {
                mount.client().connect(()->fail("Mount is moving"));
                fail("Expected connection to reject a moving tilt axis");
            } catch(IOException expected) {
                assertTrue(expected.getMessage().contains("stop"));
            }
            assertTrue(mount.commands.stream().allMatch(c->c.matches("[aef][12]")));
        }
    }

    @Test public void connectionInitializesOnlyColdAxesAndCalibrationOnlyReadsPositions() throws Exception {
        for(String mode:new String[]{"cold-pan","cold-both"}) {
            try(Mount mount=new Mount(mode)) {
                GoTo client=mount.client();
                GoTo.MountSetup setup=client.connect(()->{
                    assertEquals(java.util.List.of("a1","a2","e1","e2","f1","f2"),mount.commands);
                });
                assertEquals(SETUP,setup);
                assertEquals(-1,mount.index("j1"));
                int setupCommands=mount.commands.size();
                GoTo.Encoders captured=client.captureReference();
                assertEquals(new GoTo.Encoders(0x800000,0x800000),captured);
                assertTrue(mount.index("F1")>=0 && mount.index("F1")<mount.index("j1"));
                if("cold-both".equals(mode)) assertTrue(mount.index("F2")>mount.index("F1") && mount.index("F2")<mount.index("j1"));
                else assertEquals(-1,mount.index("F2"));
                assertEquals(java.util.List.of("j1","j2"),mount.commands.subList(setupCommands,mount.commands.size()));
            }
        }
    }

    @Test public void connectionStopsWhenInitializationFailsOrAxisStaysCold() throws Exception {
        for(String mode:new String[]{"init-rejected","init-not-ready"}) {
            try(Mount mount=new Mount(mode)) {
                try {
                    mount.client().connect(()->{});
                    fail("Expected initialization failure");
                } catch(IOException expected) {
                    assertTrue(expected.getMessage(),expected.getMessage().contains(
                        "init-rejected".equals(mode)?":F1":"uninitialized"));
                }
                assertTrue(mount.index("F1")>=0);
                assertEquals(-1,mount.index("j1"));
                assertTrue(mount.commands.stream().allMatch(c->c.matches("[aefF][12]")));
            }
        }
    }

    @Test public void reconnectingAnInitializedMountSkipsInitialization() throws Exception {
        try(Mount mount=new Mount(null)) {
            assertEquals(SETUP,mount.client().connect(()->fail("No initialization expected")));
            assertEquals(java.util.List.of("a1","a2","e1","e2","f1","f2"),mount.commands);
        }
    }

    @Test public void calibrationRejectsAnInvalidEncoderReply() throws Exception {
        try(Mount mount=new Mount("invalid-tilt")) {
            try {
                mount.client().captureReference();
                fail("Expected an invalid tilt encoder reply to block calibration");
            } catch(IOException expected) {
                assertTrue(expected.getMessage().contains("Invalid GTi counter"));
            }
            assertEquals(java.util.List.of("j1","j2"),mount.commands);
        }
    }

    @Test public void gotoBlocksAnUninitializedMountBeforeSettingOrStartingMovement() throws Exception {
        try(Mount mount=new Mount("cold-pan")) {
            try {
                mount.client().goToPosition(SETUP,TARGET,REFERENCE,()->true,GoTo.now());
                fail("Expected GoTo to require calibration");
            } catch(IOException expected) {
                assertTrue(expected.getMessage(),expected.getMessage().contains("Connect GTi"));
            }
            assertEquals(java.util.List.of("j1","j2","f1"),mount.commands);
        }
    }

    @Test public void movingTiltBlocksBeforeCalculatingEitherTarget() throws Exception {
        try(Mount mount=new Mount("tilt-moving")) {
            GoTo client=mount.client((event,fields)->{
                if(event.equals("axis_target")) fail("Both axis statuses must pass before calculating targets");
            });
            IOException failure=assertThrows(IOException.class,
                ()->client.goToPosition(SETUP,TARGET,REFERENCE,()->true,GoTo.now()));
            assertEquals("Axis 2 is already moving",failure.getMessage());
            assertEquals(java.util.List.of("j1","j2","f1","f2"),mount.commands);
        }
    }

    @Test public void stopBothMotorsSendsOneAcknowledgedStop() throws Exception {
        try(Mount mount=new Mount(null)) {
            mount.client().stopBothMotors();
            assertEquals(java.util.List.of("K3"),mount.commands);
        }
        try(Mount mount=new Mount("reject-stop")) {
            IOException failure=assertThrows(IOException.class,()->mount.client().stopBothMotors());
            assertTrue(failure.getMessage().contains(":K3"));
            assertEquals(java.util.List.of("K3"),mount.commands);
        }
    }

    @Test public void replayStopPollReadsBothAxesInOrderEvenWhenPanIsMoving() throws Exception {
        try(Mount mount=new Mount("pan-moving")) {
            GoTo.MotionStatus status=mount.client().readMotionStatus();
            assertTrue(status.panMoving());
            assertFalse(status.tiltMoving());
            assertFalse(status.stopped());
            assertEquals(java.util.List.of("f1","f2"),mount.commands);
        }
    }

    @Test public void hsDistanceGapScalesWithMountRangeAndUsesTheShortestPanSeparation() {
        GoTo.Point point=new GoTo.Point(0,0,0);
        HighSpeedReplay.Checkpoint near=new HighSpeedReplay.Checkpoint(point,0,10,100,0,0,0,0);
        HighSpeedReplay.Checkpoint far=new HighSpeedReplay.Checkpoint(point,0,10,200,0,0,0,0);
        assertEquals(0,near.distanceGapM(10,3600),0);
        assertEquals(100,near.distanceGapM(610,3600),0.000001);
        assertEquals(200,far.distanceGapM(610,3600),0.000001);
        assertEquals(near.distanceGapM(610,3600),near.distanceGapM(3010,3600),0.000001);
    }

    @Test public void highSpeedPanKeepsMovingThroughLeadChangesAndStopsOnlyAtLastCheckpoint() throws Exception {
        HighSpeedReplay.Plan plan=highSpeedPlan();
        int first=plan.checkpoints().get(0).count();
        int middle=plan.checkpoints().get(1).count();
        int last=plan.last().count();
        java.util.List<Double> lead=new java.util.ArrayList<>();
        java.util.List<Long> accepted=new java.util.ArrayList<>();
        try(Mount mount=new Mount(null,first,first,middle,last,last+2)) {
            GoTo.Trace trace=(event,fields)->{
                if(event.equals("hs_pan_sample")) lead.add((Double)fields[11]);
                if(event.equals("start_acknowledged")) accepted.add((Long)fields[3]);
            };
            GoTo client=mount.client(trace);
            GoTo.PanTiming timing=client.readHighSpeedPanTiming();
            int period=client.highSpeedPanPeriod(timing,plan.countsPerSecond());
            HighSpeedReplay.Result result=HighSpeedReplay.goToPositionHS(client,plan,timing,period,speedUps(HighSpeedReplay.SpeedUp.NONE),()->true,()->{},progress->{},trace);
            assertEquals("HS replay complete",result.outcome());
            assertEquals(java.util.List.of(result.startAckMs),accepted);
            assertEquals(last,result.lastSampleCount.intValue());
            assertEquals(last+2,result.finalCount.intValue());
            assertEquals(0,result.distanceGapsM[0],0);
            assertNotNull(result.distanceGapsM[1]);
            assertNotNull(result.distanceGapsM[2]);
            assertTrue(lead.toString(),lead.stream().anyMatch(value->value<0));
            assertTrue(lead.toString(),lead.stream().anyMatch(value->value>0));
            assertEquals(java.util.List.of("b1","g1","j1","G130","I1"+GoTo.encode(period),"J1",
                "j1","j1","j1","K1","f1","j1"),mount.commands);
        }
    }

    @Test public void lostHighSpeedStartReplyTriggersOneStopAndNeverCountsAsAnAcknowledgment() throws Exception {
        HighSpeedReplay.Plan plan=highSpeedPlan();
        int first=plan.checkpoints().get(0).count();
        try(Mount mount=new Mount("timeout-J1",first,first+3)) {
            GoTo.Trace trace=(event,fields)->{
                if(event.equals("start_acknowledged")) fail("A timeout is not an acknowledgment");
            };
            HighSpeedReplay.Result result=HighSpeedReplay.goToPositionHS(mount.client(trace),plan,new GoTo.PanTiming(1000000,1),123,speedUps(HighSpeedReplay.SpeedUp.NONE),
                ()->true,()->{},progress->{},trace);
            assertNull(result.startAckMs);
            assertNotNull(result.stoppedMs);
            assertFalse(result.reachedLast);
            assertEquals(java.util.List.of("j1","G130","I1"+GoTo.encode(123),"J1","K1","f1","j1"),mount.commands);
        }
    }

    @Test public void rejectedHighSpeedPeriodPreventsAnyStart() throws Exception {
        HighSpeedReplay.Plan plan=highSpeedPlan();
        try(Mount mount=new Mount("reject-hs-period",plan.checkpoints().get(0).count())) {
            assertThrows(IOException.class,()->HighSpeedReplay.goToPositionHS(mount.client(),plan,new GoTo.PanTiming(1000000,1),123,speedUps(HighSpeedReplay.SpeedUp.NONE),
                ()->true,()->{},progress->{},(event,fields)->{}));
            assertEquals(java.util.List.of("j1","G130","I1"+GoTo.encode(123)),mount.commands);
        }
    }

    @Test public void highSpeedCeilingCapsTheMotorWhileKeepingTheRequestedSurferTimeline() throws Exception {
        double pace=30.0;
        HighSpeedReplay.Plan plan=highSpeedPlan(pace);
        HighSpeedReplay.Checkpoint start=plan.checkpoints().get(0);
        assertTrue(plan.ceilingApplied());
        assertEquals(pace,plan.targetPaceKmh(),0.01);
        assertEquals(800.0,plan.countsPerSecond()*86164.09065/SETUP.panCpr(),0.000001);
        assertTrue(start.requiredCountsPerSecond()>plan.countsPerSecond());
        assertTrue(start.matchedPaceKmh(plan.countsPerSecond())<pace);
        assertEquals(Math.round(Geo.distance(.0009,0,.0009,.00009)*3600/pace)*2,plan.last().dueMs());
        assertTrue(plan.runLimitMs()>Math.abs(plan.last().relativeCount())*1000/plan.countsPerSecond());
        assertFalse(highSpeedPlan().ceilingApplied());
    }

    @Test public void hsCsvDelaysSetDueTimesAndPaceForEachLeg() throws Exception {
        GoTo.Reference ref=new GoTo.Reference(new GoTo.Point(0,0,0),0,0,0x800000,0x800000,1,1);
        GoTo.Point first=new GoTo.Point(.0009,0,0), second=new GoTo.Point(.0009,.000045,0);
        GoTo.Point third=new GoTo.Point(.0009,.00009,0);
        HighSpeedReplay.Plan plan=HighSpeedReplay.plan(java.util.List.of(
            new HighSpeedReplay.TimedPoint(first,0),new HighSpeedReplay.TimedPoint(second,1000),
            new HighSpeedReplay.TimedPoint(third,597)),ref,SETUP.panCpr());
        assertEquals(0,plan.checkpoints().get(0).dueMs());
        assertEquals(1000,plan.checkpoints().get(1).dueMs());
        assertEquals(1597,plan.last().dueMs());
        double legDistance=Geo.distance(first.lat(),first.lon(),second.lat(),second.lon());
        assertEquals(legDistance*3.6,plan.checkpoints().get(0).targetPaceKmh(),0.000001);
        assertEquals(legDistance*3600/597,plan.checkpoints().get(1).targetPaceKmh(),0.000001);
        assertEquals(plan.checkpoints().get(1).targetPaceKmh(),plan.last().targetPaceKmh(),0);
        assertEquals(2*legDistance*3600/1597,plan.targetPaceKmh(),0.000001);
    }

    @Test public void hsCsvDelayValidationIdentifiesTheInvalidViewpoint() {
        GoTo.Reference ref=new GoTo.Reference(new GoTo.Point(0,0,0),0,0,0x800000,0x800000,1,1);
        GoTo.Point first=new GoTo.Point(.0009,0,0), second=new GoTo.Point(.0009,.000045,0);
        IOException firstDelay=assertThrows(IOException.class,()->HighSpeedReplay.plan(java.util.List.of(
            new HighSpeedReplay.TimedPoint(first,1000),new HighSpeedReplay.TimedPoint(second,1000)),ref,SETUP.panCpr()));
        assertEquals("HS viewpoint 1 requires delay_ms = 0",firstDelay.getMessage());
        IOException secondDelay=assertThrows(IOException.class,()->HighSpeedReplay.plan(java.util.List.of(
            new HighSpeedReplay.TimedPoint(first,0),new HighSpeedReplay.TimedPoint(second,0)),ref,SETUP.panCpr()));
        assertEquals("HS viewpoint 2 requires a positive delay_ms",secondDelay.getMessage());
    }

    private static HighSpeedReplay.Plan highSpeedPlan() throws IOException { return highSpeedPlan(18); }

    private static HighSpeedReplay.Plan highSpeedPlan(double pace) throws IOException {
        GoTo.Reference ref=new GoTo.Reference(new GoTo.Point(0,0,0),0,0,0xfffff0,0x800000,1,1);
        long delay=Math.round(Geo.distance(.0009,0,.0009,.00009)*3600/pace);
        return HighSpeedReplay.plan(java.util.List.of(
            new HighSpeedReplay.TimedPoint(new GoTo.Point(.0009,0,0),0),
            new HighSpeedReplay.TimedPoint(new GoTo.Point(.0009,.00009,0),delay),
            new HighSpeedReplay.TimedPoint(new GoTo.Point(.0009,.00018,0),delay)),ref,SETUP.panCpr());
    }

    @Test public void markedHighSpeedCheckpointConfirmsStopBeforeRestartThenCountsThePauseInTheRun() throws Exception {
        HighSpeedReplay.Plan plan=speedUpPlan();
        int start=plan.checkpoints().get(0).count();
        int middle=plan.checkpoints().get(1).count();
        int end=plan.last().count();
        GoTo.PanTiming timing=new GoTo.PanTiming(1_000_000,1);
        int initialPeriod=timing.period(plan.countsPerSecond());
        int fasterPeriod=timing.period(plan.capRate(plan.checkpoints().get(1).requiredCountsPerSecond()));
        assertTrue(fasterPeriod<initialPeriod);
        try(Mount mount=new Mount("speed-up-stop-check",start,middle,middle+5,middle+9,end,end+2)) {
            HighSpeedReplay.Result result=HighSpeedReplay.goToPositionHS(mount.client(),plan,timing,initialPeriod,
                speedUps(HighSpeedReplay.SpeedUp.MATCH_CHECKPOINT),()->true,()->{},progress->{},(event,fields)->{});
            assertEquals("HS replay complete",result.outcome());
            assertEquals(plan.countsPerSecond(),result.observed[1].rate(),0);
            assertEquals(plan.capRate(plan.checkpoints().get(1).requiredCountsPerSecond()),result.observed[2].rate(),0);
            assertTrue(result.observed[2].elapsedMs()-result.observed[1].elapsedMs()>=100);
            assertEquals(java.util.List.of("j1","G130","I1"+GoTo.encode(initialPeriod),"J1","j1",
                "K1","f1","f1","j1","G130","I1"+GoTo.encode(fasterPeriod),"J1","j1","j1",
                "K1","f1","j1"),mount.commands);
        }
    }

    @Test public void speedUpDoesNotRestartIfFinalWaypointWasReachedDuringCoast() throws Exception {
        HighSpeedReplay.Plan plan=speedUpPlan();
        int start=plan.checkpoints().get(0).count();
        int middle=plan.checkpoints().get(1).count();
        int end=plan.last().count();
        GoTo.PanTiming timing=new GoTo.PanTiming(1_000_000,1);
        try(Mount mount=new Mount(null,start,middle,end+1)) {
            HighSpeedReplay.Result result=HighSpeedReplay.goToPositionHS(mount.client(),plan,timing,
                timing.period(plan.countsPerSecond()),speedUps(HighSpeedReplay.SpeedUp.MATCH_CHECKPOINT),()->true,()->{},progress->{},(event,fields)->{});
            assertEquals("HS replay complete",result.outcome());
            assertEquals(end+1,result.finalCount.intValue());
            assertEquals(1,mount.commands.stream().filter("J1"::equals).count());
            assertEquals(1,mount.commands.stream().filter("K1"::equals).count());
        }
    }

    @Test public void multiplierRestartsWithIncreasedPeriodRateLimitedToTheCeiling() throws Exception {
        HighSpeedReplay.Plan plan=highSpeedPlan(12);
        int start=plan.checkpoints().get(0).count(),middle=plan.checkpoints().get(1).count(),end=plan.last().count();
        GoTo.PanTiming timing=new GoTo.PanTiming(1_000_000,1);
        int initialPeriod=timing.period(plan.countsPerSecond());
        double ceiling=800*SETUP.panCpr()/86164.09065;
        for(String field:new String[]{"1.5x","2.0x"}) {
            HighSpeedReplay.SpeedUp directive=HighSpeedReplay.SpeedUp.parse(field);
            double newRate=field.equals("1.5x")?1.5*plan.countsPerSecond():ceiling;
            assertTrue(newRate>plan.countsPerSecond());
            try(Mount mount=new Mount(null,start,middle,middle+5,middle+9,end,end+2)) {
                HighSpeedReplay.Result result=HighSpeedReplay.goToPositionHS(mount.client(),plan,timing,initialPeriod,
                    speedUps(directive),()->true,()->{},progress->{},(event,fields)->{});
                assertEquals("HS replay complete",result.outcome());
                assertEquals(newRate,result.observed[2].rate(),0.000001);
                assertEquals(java.util.List.of("j1","G130","I1"+GoTo.encode(initialPeriod),"J1","j1",
                    "K1","f1","j1","G130","I1"+GoTo.encode(timing.period(newRate)),"J1","j1","j1","K1","f1","j1"),
                    mount.commands);
            }
        }
        assertEquals(ceiling,HighSpeedReplay.SpeedUp.parse("2x").targetRate(plan,1,ceiling),0.000001);
    }

    @Test public void speedUpParserAcceptsMultipliersAndPreservesLegacyFlags() {
        assertEquals(1.5,HighSpeedReplay.SpeedUp.parse(" 1.5x ").multiplier(),0);
        assertEquals(2.0,HighSpeedReplay.SpeedUp.parse("2.0").multiplier(),0);
        assertTrue(HighSpeedReplay.SpeedUp.parse("1").matchCheckpoint());
        assertFalse(HighSpeedReplay.SpeedUp.parse("1.0x").requested());
        assertFalse(HighSpeedReplay.SpeedUp.parse("0").requested());
        assertFalse(HighSpeedReplay.SpeedUp.parse("").requested());
        assertThrows(IllegalArgumentException.class,()->HighSpeedReplay.SpeedUp.parse("0.5x"));
        assertThrows(IllegalArgumentException.class,()->HighSpeedReplay.SpeedUp.parse("twice"));
    }

    private static HighSpeedReplay.SpeedUp[] speedUps(HighSpeedReplay.SpeedUp middle) {
        return new HighSpeedReplay.SpeedUp[]{HighSpeedReplay.SpeedUp.NONE,middle,HighSpeedReplay.SpeedUp.NONE};
    }

    private static HighSpeedReplay.Plan speedUpPlan() throws IOException {
        GoTo.Reference ref=new GoTo.Reference(new GoTo.Point(0,0,0),0,0,0x800000,0x800000,1,1);
        return HighSpeedReplay.plan(java.util.List.of(
            new HighSpeedReplay.TimedPoint(new GoTo.Point(.0009,-.00018,0),0),
            new HighSpeedReplay.TimedPoint(new GoTo.Point(.0009,-.00009,0),2000),
            new HighSpeedReplay.TimedPoint(new GoTo.Point(.0009,0,0),2000)),ref,SETUP.panCpr());
    }

    private static final class Mount implements AutoCloseable {
        final DatagramSocket socket;
        final Thread server;
        final CopyOnWriteArrayList<String> commands=new CopyOnWriteArrayList<>();
        final int[] positions={0x800000,0x800000}, targets=positions.clone();

        final int[] panSamples;
        int panSampleIndex,stopRequests,speedUpStopPolls;

        Mount(String failure,int... panSamples) throws IOException {
            this.panSamples=panSamples;
            socket=new DatagramSocket(0,InetAddress.getLoopbackAddress());
            server=new Thread(()->serve(failure));
            server.start();
        }
        GoTo client() {
            return client((event,fields)->{});
        }
        GoTo client(GoTo.Trace trace) {
            return new GoTo(DatagramSocket::new,
                new InetSocketAddress(InetAddress.getLoopbackAddress(),socket.getLocalPort()),trace);
        }
        int index(String prefix) {
            for(int i=0;i<commands.size();i++) if(commands.get(i).startsWith(prefix)) return i;
            return -1;
        }
        private void serve(String failure) {
            boolean coldPan="cold-pan".equals(failure)||"cold-both".equals(failure)||
                "moving".equals(failure)||"init-rejected".equals(failure)||"init-not-ready".equals(failure);
            boolean[] initialized={!coldPan,!"cold-both".equals(failure)};
            try {
                byte[] buffer=new byte[128];
                while(!socket.isClosed()) {
                    DatagramPacket packet=new DatagramPacket(buffer,buffer.length);
                    socket.receive(packet);
                    String command=new String(buffer,1,packet.getLength()-2,StandardCharsets.US_ASCII);
                    commands.add(command);
                    int i=command.charAt(1)-'1';
                    String value="";
                    switch(command.charAt(0)) {
                        case 'a': value=GoTo.encode(360000); break;
                        case 'e': value="0336C5"; break;
                        case 'f':
                            boolean moving=i==0?"pan-moving".equals(failure):
                                "moving".equals(failure)||"tilt-moving".equals(failure);
                            if("speed-up-stop-check".equals(failure)&&i==0&&stopRequests==1)
                                moving=speedUpStopPolls++==0;
                            value=(moving?"11":"10")+(initialized[i]?"1":"0");
                            break;
                        case 'F':
                            if(!"init-rejected".equals(failure)&&!"init-not-ready".equals(failure)) initialized[i]=true;
                            break;
                        case 'b': value=GoTo.encode(1000000); break;
                        case 'g': value="01"; break;
                        case 'j':
                            if(i==0&&panSampleIndex<panSamples.length) {
                                if(panSampleIndex==1) java.util.concurrent.locks.LockSupport.parkNanos(25_000_000);
                                value=GoTo.encode(panSamples[panSampleIndex++]);
                            } else value=i==1&&"invalid-tilt".equals(failure)?"ZZZZZZ":GoTo.encode(positions[i]);
                            break;
                        case 'S':
                            if(i==1&&"timeout".equals(failure)) continue;
                            targets[i]=GoTo.decode(command.substring(2)); break;
                        case 'K': stopRequests++; break;
                        case 'J':
                            positions[i]=targets[i];
                            if(("timeout-"+command).equals(failure)) continue;
                            break;
                        default: break;
                    }
                    boolean rejected=i==1&&command.startsWith("S")&&"reject".equals(failure)
                        ||command.equals("F1")&&"init-rejected".equals(failure)
                        ||command.equals("K3")&&"reject-stop".equals(failure)
                        ||command.startsWith("I1")&&"reject-hs-period".equals(failure);
                    String reply=rejected?"!2\r":"="+value+"\r";
                    byte[] bytes=reply.getBytes(StandardCharsets.US_ASCII);
                    socket.send(new DatagramPacket(bytes,bytes.length,packet.getSocketAddress()));
                }
            } catch(IOException e) {
                if(!socket.isClosed()) throw new AssertionError(e);
            }
        }
        @Override public void close() throws InterruptedException {
            socket.close();
            server.join(1000);
        }
    }
}

