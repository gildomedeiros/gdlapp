package au.com.gildo.vtc;

import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public class GtiClientTest {
    @Test public void rejectedModeIdentifiesCommandAndDoesNotSendStart()throws Exception {
        List<String> commands=new ArrayList<>();
        GtiClient mount=new GtiClient(new GtiClient.Transport(){
            public String exchange(String command){commands.add(command);return "!2\r";}
            public void close(){}
        });
        GtiClient.GoToResult result=mount.attemptGoToAxis(1,1000,-3,false,()->true,()->1000,()->fail("Unexpected Start"));
        assertFalse(result.started());
        assertFalse(result.startSent());
        assertEquals(":G1",result.command());
        assertEquals("!2",result.reply());
        assertEquals(List.of(":G101\r"),commands);
    }

    @Test public void noWaitPanSendsOnlyThreeDatagramsAndStopNeedsNoReply()throws Exception {
        List<String> sends=new ArrayList<>();
        GtiClient mount=new GtiClient(new GtiClient.Transport(){
            public String exchange(String request){throw new AssertionError("Unexpected reply wait: "+request);}
            public void send(String request){sends.add(request);}
            public void close(){}
        });
        assertTrue(mount.goToAxis(1,1000,3,true,()->true,()->true,()->{}));
        mount.stopTrackingAxis(1,()->true,()->{});
        assertEquals(List.of(":G100\r",":S1E80300\r",":J1\r",":K1\r"),sends);
    }

    @Test public void panStartDoesNotWaitForTiltAndAChangedGpsCancelsStart()throws Exception {
        Wire wire=new Wire();
        wire.moving[1]=true;
        GtiClient mount=new GtiClient(wire);
        assertFalse(mount.goToAxis(1,1000,3,false,()->true,()->false,()->fail("Stale target started")));
        assertEquals(List.of(":G100\r",":S1E80300\r"),wire.commands);
        wire.commands.clear();
        assertTrue(mount.goToAxis(1,1100,3,false,()->true,()->true,()->{}));
        assertEquals(List.of(":G100\r",":S14C0400\r",":J1\r"),wire.commands);
    }

    @Test public void noWaitReprogramsNewTargetBeforeStart()throws Exception {
        java.util.List<String> sent=new java.util.ArrayList<>();
        GtiClient mount=new GtiClient(new GtiClient.Transport(){
            public String exchange(String command){throw new AssertionError("Unexpected reply wait: "+command);}
            public void send(String command){sent.add(command);}
            public void close(){}
        });
        mount.cpr[0]=2_073_600;
        assertTrue(mount.goToAxis(1,1000,3,true,()->true,()->1100,()->{}));
        assertEquals(java.util.List.of(":G100\r",":S1E80300\r",":S14C0400\r",":J1\r"),sent);
    }

    @Test public void noWaitIsDefaultAndExplicitAcknowledgedIsRetained() {
        Settings defaults=new Settings(Map.of());
        assertTrue(defaults.gotoNoWait);
        assertEquals(0,defaults.acquisitionTriggerMetres,0);
        Settings custom=new Settings(Map.of("gotoMode","acknowledged","acquisitionTriggerMetres","7"));
        assertFalse(custom.gotoNoWait);
        assertEquals(7,custom.acquisitionTriggerMetres,0);
    }

    @Test public void uncertainInitializationStillMarksTheSavedReferenceAsInvalid() {
        Wire wire=new Wire(){@Override public String exchange(String c)throws IOException {
            if(c.startsWith(":f")){commands.add(c);return "=000\r";}
            return super.exchange(c);
        }};
        wire.fail=":F1";GtiClient mount=new GtiClient(wire);
        assertThrows(IOException.class,mount::connect);assertTrue(mount.initializedDuringConnect);
        assertEquals(0,wire.starts());
    }
    @Test public void initializationIsExposedSoSavedMotorReferencesCanBeInvalidated()throws Exception {
        Wire wire=new Wire(){
            final boolean[] initialized={false,false};
            @Override public String exchange(String c)throws IOException {
                int axis=c.charAt(2)-'1';
                if(c.startsWith(":f")&&!initialized[axis]){commands.add(c);return "=000\r";}
                if(c.startsWith(":F"))initialized[axis]=true;
                return super.exchange(c);
            }
        };
        GtiClient mount=new GtiClient(wire);mount.connect();
        assertTrue(mount.initializedDuringConnect);assertNotNull(mount.position());
        GtiClient existing=new GtiClient(wire);existing.connect();assertFalse(existing.initializedDuringConnect);
    }
    @Test public void resetDetectedDuringPositionReadInvalidatesTheReferenceWithoutReinitializing()throws Exception {
        java.util.concurrent.atomic.AtomicBoolean reset=new java.util.concurrent.atomic.AtomicBoolean();
        Wire wire=new Wire(){@Override public String exchange(String c)throws IOException {
            if(reset.get()&&c.startsWith(":f")){commands.add(c);return "=000\r";}
            return super.exchange(c);
        }};
        GtiClient mount=new GtiClient(wire);mount.connect();reset.set(true);
        assertThrows(IOException.class,mount::position);assertTrue(mount.referenceLost);
        assertFalse(wire.sent(":F"));assertEquals(0,wire.starts());
    }
    @Test public void reconnectRereadsTiltPositionMisdeliveredAsPanResolution()throws Exception {
        Wire previousWire=new Wire();
        previousWire.cpr=2_073_600;
        GtiClient previous=new GtiClient(previousWire);
        previous.connect();

        Wire wire=new Wire(){
            @Override public String exchange(String command)throws IOException {
                if(command.equals(":a1\r")&&commands.isEmpty()){
                    commands.add(command);
                    return "=FDB27F\r";
                }
                return super.exchange(command);
            }
        };
        wire.cpr=2_073_600;
        GtiClient candidate=new GtiClient(wire);
        candidate.reconnect(previous,()->true);

        assertArrayEquals(previous.cpr,candidate.cpr);
        assertFalse(candidate.referenceLost);
        assertEquals(List.of(":a1\r",":a1\r",":f1\r",":e1\r",":a2\r",":f2\r",":e2\r"),wire.commands);
    }

    @Test public void repeatedResolutionMismatchKeepsReferenceAndRejectsCandidate()throws Exception {
        GtiClient previous=new GtiClient(new Wire());
        previous.connect();
        Wire wire=new Wire();
        wire.cpr=8_368_893;
        GtiClient candidate=new GtiClient(wire);

        IOException failure=assertThrows(IOException.class,()->candidate.reconnect(previous,()->true));
        assertTrue(failure.getMessage().contains("expected 360000, read 8368893, then 8368893"));
        assertFalse(previous.referenceLost);
        assertFalse(candidate.referenceLost);
        assertEquals(List.of(":a1\r",":a1\r"),wire.commands);
    }

    @Test public void firmwareMismatchRejectsCandidateWithoutMarkingReferenceLost()throws Exception {
        GtiClient previous=new GtiClient(new Wire());
        previous.connect();
        Wire wire=new Wire(){
            @Override public String exchange(String command)throws IOException {
                if(command.equals(":e2\r")){
                    commands.add(command);
                    return "=FDB27F\r";
                }
                return super.exchange(command);
            }
        };
        GtiClient candidate=new GtiClient(wire);

        IOException failure=assertThrows(IOException.class,()->candidate.reconnect(previous,()->true));
        assertTrue(failure.getMessage().contains("GTi firmware during reconnect"));
        assertFalse(previous.referenceLost);
        assertFalse(candidate.referenceLost);
        assertEquals(List.of(":a1\r",":f1\r",":e1\r",":a2\r",":f2\r",":e2\r"),wire.commands);
    }

    @Test public void reconnectConfirmsUninitializedStatusBeforeLosingReference()throws Exception {
        GtiClient previous=new GtiClient(new Wire());
        previous.connect();
        for(String secondReply:List.of("=101\r","=100\r")) {
            Wire wire=new Wire(){
                private boolean first=true;
                @Override public String exchange(String command)throws IOException {
                    if(command.equals(":f1\r")){
                        commands.add(command);
                        if(first){first=false;return "=100\r";}
                        return secondReply;
                    }
                    return super.exchange(command);
                }
            };
            GtiClient candidate=new GtiClient(wire);
            if(secondReply.equals("=100\r")) {
                assertThrows(IOException.class,()->candidate.reconnect(previous,()->true));
                assertTrue(candidate.referenceLost);
            } else {
                candidate.reconnect(previous,()->true);
                assertFalse(candidate.referenceLost);
            }
            assertEquals(List.of(":a1\r",":f1\r",":f1\r"),wire.commands.subList(0,3));
            assertFalse(wire.sent(":F"));
        }
    }

    @Test public void reconnectStatusConfirmationTimeoutKeepsReference()throws Exception {
        GtiClient previous=new GtiClient(new Wire());
        previous.connect();
        Wire wire=new Wire(){
            private boolean first=true;
            @Override public String exchange(String command)throws IOException {
                if(command.equals(":f1\r")){
                    commands.add(command);
                    if(first){first=false;return "=100\r";}
                    throw new java.net.SocketTimeoutException("Simulated lost confirmation");
                }
                return super.exchange(command);
            }
        };
        GtiClient candidate=new GtiClient(wire);

        assertThrows(java.net.SocketTimeoutException.class,()->candidate.reconnect(previous,()->true));
        assertFalse(candidate.referenceLost);
        assertEquals(List.of(":a1\r",":f1\r",":f1\r"),wire.commands);
    }

    static class Wire implements GtiClient.Transport {
        final List<String> commands=new ArrayList<>();
        final int[] pos={0xfffff0,8000000},target={0,0};
        final boolean[] moving={false,false};
        int cpr=360000;
        String fail="",badAck="",rejected="";
        Runnable afterTarget=()->{};
        java.util.function.IntConsumer afterPositionRead=axis->{};
        public String exchange(String c)throws IOException {
            commands.add(c);char op=c.charAt(1);int axis=c.charAt(2)-'1';
            if(c.startsWith(fail)&&!fail.isEmpty())throw new IOException("Simulated lost response");
            if(!rejected.isEmpty()&&c.startsWith(rejected))return "!2\r";
            if(!badAck.isEmpty()&&c.startsWith(badAck))return "=000080\r";
            switch(op) {
                case 'a':return "="+GtiClient.encode24(cpr)+"\r";
                case 'e':return "=030401\r";
                case 'f':return moving[axis]?"=011\r":"=001\r";
                case 'j':
                    String result="="+GtiClient.encode24(pos[axis])+"\r";
                    afterPositionRead.accept(axis);
                    return result;
                case 'S':target[axis]=GtiClient.decode24(c.substring(3,c.length()-1));afterTarget.run();break;
                // Actual 0336C5 field response before J; do not mirror the S setter.
                case 'h':return "=000080\r";
                case 'J':moving[axis]=true;break;
                case 'L':moving[axis]=false;break;
                default:break;
            }
            return "=\r";
        }
        public void close(){}
        long starts(){return commands.stream().filter(c->c.startsWith(":J")).count();}
        boolean sent(String prefix){return commands.stream().anyMatch(c->c.startsWith(prefix));}
    }
    @Test public void startsOnlyAfterBothFiniteTargetsAreAcknowledged()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();GtiClient.Position here=g.position();
        g.begin();g.move(here,.25,-.1,()->true);
        assertTrue(w.sent(":G120"));assertTrue(w.sent(":G221"));
        assertEquals(GtiClient.wrap24(here.pan+250),w.target[0]);assertEquals(here.tilt-100,w.target[1]);
        assertEquals(2,w.starts());
        assertTrue(w.commands.indexOf(":S2"+GtiClient.encode24(w.target[1])+"\r")<w.commands.indexOf(":J1\r"));
        assertFalse(w.sent(":h"));
        assertFalse(w.sent(":I"));assertFalse(w.sent(":G110"));assertFalse(w.sent(":G111"));
        g.stop();assertFalse(w.moving[0]);assertFalse(w.moving[1]);
    }
    @Test public void unexpectedSetterPayloadPreventsStartAndStopsBothAxes()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();w.badAck=":S1";
        assertThrows(IOException.class,()->g.move(g.position(),.25,0,()->true));
        assertEquals(0,w.starts());assertTrue(w.sent(":L1"));assertTrue(w.sent(":L2"));
    }
    @Test public void targetTimeoutNeverFallsThroughToStart()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();w.fail=":S1";
        assertThrows(IOException.class,()->g.move(g.position(),.25,0,()->true));
        assertEquals(0,w.starts());assertTrue(w.sent(":L1"));assertTrue(w.sent(":L2"));
    }
    @Test public void uiStopBetweenTargetAndStartCancelsMovement()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();w.afterTarget=g::cancel;
        assertThrows(IOException.class,()->g.move(g.position(),.25,.25,()->true));
        assertEquals(0,w.starts());assertTrue(w.sent(":L1"));assertTrue(w.sent(":L2"));
    }
    @Test public void staleInputBeforeSecondStartStopsFirstAxisToo()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();
        assertThrows(IOException.class,()->g.move(g.position(),.25,.25,()->w.starts()==0));
        assertEquals(1,w.starts());assertFalse(w.moving[0]);assertTrue(w.sent(":L2"));
    }
    @Test public void missingFirstStopAcknowledgementStillAttemptsSecondAxis()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();w.fail=":L1";
        assertThrows(IOException.class,g::stop);assertTrue(w.sent(":L2"));
    }
    @Test public void refusesMovingMountAtConnection() {
        Wire w=new Wire();w.moving[0]=true;GtiClient g=new GtiClient(w);
        assertThrows(IOException.class,g::connect);assertEquals(0,w.starts());
    }
    @Test public void physicalCommandBoundAndInvalidNumbers()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();
        assertThrows(IOException.class,()->g.move(g.position(),10.01,0,()->true));
        assertThrows(IOException.class,()->g.move(g.position(),Double.NaN,0,()->true));
        assertEquals(0,w.starts());
    }
    @Test public void secondTargetTimeoutPreventsEitherAxisStarting()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();w.fail=":S2";
        assertThrows(IOException.class,()->g.move(g.position(),.25,.25,()->true));
        assertEquals(0,w.starts());assertTrue(w.sent(":L1"));assertTrue(w.sent(":L2"));
    }
    @Test public void recorded0336C5PositionUsesFiniteDestinationWithoutPrestartTargetReadback()throws Exception {
        Wire w=new Wire();w.cpr=2073600;w.pos[0]=0x8004D3;w.pos[1]=0x80076F;
        assertEquals("=000080\r",w.exchange(":h1\r"));w.commands.clear();
        GtiClient g=new GtiClient(w);g.connect();g.begin();g.move(g.position(),-.25,-.25,()->true);
        assertTrue(w.sent(":G121"));assertTrue(w.sent(":G221"));
        assertTrue(w.sent(":S133FF7F"));assertEquals(0x7FFF33,w.target[0]);
        assertEquals(0x8001CF,w.target[1]);assertEquals(2,w.starts());
        assertFalse(w.sent(":h"));assertFalse(w.sent(":I"));
        assertTrue(w.commands.indexOf(":S2CF0180\r")<w.commands.indexOf(":J1\r"));
    }
    @Test public void rejectedTargetAndModeCommandsNeverStart()throws Exception {
        for(String rejected:new String[]{":G1",":S1",":S2"}) {
            Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();w.rejected=rejected;
            assertThrows(IOException.class,()->g.move(g.position(),.25,.25,()->true));
            assertEquals(0,w.starts());assertTrue(w.sent(":L1"));assertTrue(w.sent(":L2"));
        }
    }
    @Test public void lostSecondStartReplyStillStopsBothAxes()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();w.fail=":J2";
        assertThrows(IOException.class,()->g.move(g.position(),.25,.25,()->true));
        assertTrue(w.sent(":J1"));assertFalse(w.moving[0]);assertTrue(w.sent(":L1"));assertTrue(w.sent(":L2"));
    }
    @Test public void recordedDecelerationPositionIsNotTreatedAsStoppedArrival()throws Exception {
        Wire w=new Wire();w.cpr=2073600;w.pos[0]=8331991;w.pos[1]=8337437;
        GtiClient g=new GtiClient(w);g.connect();w.moving[0]=true;
        // 1.0.2 read 8331991 during the last part of the move, then "stopped".
        // The next position was 8331814, within 4 counts of target 8331818.
        w.afterPositionRead=axis->{if(axis==0){w.moving[0]=false;w.pos[0]=8331814;}};
        GtiClient.Position inFlight=g.position();
        assertEquals(8331991,inFlight.pan);
        assertEquals("001",inFlight.panStatus);
        assertEquals("011",inFlight.panStatusBefore);
        assertTrue("The earlier running status must prevent an arrival check",inFlight.moving);
        assertThrows(IOException.class,()->g.move(inFlight,-.25,0,()->true));
        assertEquals(0,w.starts());
        GtiClient.Position settled=g.position();
        assertFalse(settled.moving);assertEquals(8331814,settled.pan);
        assertEquals(-4,GtiClient.deltaCounts(settled.pan,8331818,w.cpr));
    }
    @Test public void tiltStoppingDuringReadAlsoRequiresAnotherPositionSample()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();w.moving[1]=true;
        int earlierTilt=w.pos[1];
        w.afterPositionRead=axis->{if(axis==1){w.moving[1]=false;w.pos[1]=earlierTilt-50;}};
        GtiClient.Position changing=g.position();
        assertTrue(changing.moving);assertEquals(earlierTilt,changing.tilt);
        assertEquals("011",changing.tiltStatusBefore);assertEquals("001",changing.tiltStatus);
        assertFalse(g.position().moving);
    }
    @Test public void motionStartingDuringPositionReadCannotAppearStopped()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();
        w.afterPositionRead=axis->{if(axis==0)w.moving[0]=true;};
        GtiClient.Position p=g.position();
        assertEquals("001",p.panStatusBefore);assertEquals("011",p.panStatus);assertTrue(p.moving);
        assertThrows(IOException.class,()->g.move(p,.25,0,()->true));assertEquals(0,w.starts());
    }
    @Test public void missingStatusAfterCounterReadCannotProduceAStoppedSample()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();
        w.afterPositionRead=axis->{if(axis==1)w.fail=":f2";};
        assertThrows(IOException.class,g::position);assertEquals(0,w.starts());
    }
    @Test public void largeGotoUsesFastFiniteTargetsOnBothAxes()throws Exception {
        Wire w=new Wire();w.cpr=2073600;w.pos[0]=w.pos[1]=0x800000;
        GtiClient g=new GtiClient(w);g.connect();g.goTo(g.position(),-37,-9,()->true);
        assertTrue(w.sent(":G101"));assertTrue(w.sent(":G201"));
        assertEquals(8175488,w.target[0]);assertEquals(8336768,w.target[1]);assertEquals(2,w.starts());
        assertFalse(w.sent(":I"));assertFalse(w.sent(":G110"));assertFalse(w.sent(":G210"));
        assertTrue(w.commands.indexOf(":S2"+GtiClient.encode24(w.target[1])+"\r")<w.commands.indexOf(":J1\r"));
    }
    @Test public void smallAxisUsesSlowModeEvenDuringLargeGoto()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();g.goTo(g.position(),20,-.2,()->true);
        assertTrue(w.sent(":G100"));assertTrue(w.sent(":G221"));assertEquals(2,w.starts());
    }
    @Test public void largeGotoStillHasAHardBoundAndCancellation()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();
        assertThrows(IOException.class,()->g.goTo(g.position(),46,0,()->true));
        assertThrows(IOException.class,()->g.goTo(g.position(),Double.NaN,0,()->true));
        w.afterTarget=g::cancel;
        assertThrows(IOException.class,()->g.goTo(g.position(),37,9,()->true));
        assertEquals(0,w.starts());assertTrue(w.sent(":L1"));assertTrue(w.sent(":L2"));
    }
    @Test public void failedLargeGotoTargetDoesNotStartEitherAxis()throws Exception {
        Wire w=new Wire();GtiClient g=new GtiClient(w);g.connect();w.fail=":S2";
        assertThrows(IOException.class,()->g.goTo(g.position(),37,9,()->true));
        assertEquals(0,w.starts());assertTrue(w.sent(":L1"));assertTrue(w.sent(":L2"));
    }
    @Test public void codecAndDeltaRespect24BitAndRevolutionWrap()throws Exception {
        assertEquals(0x123456,GtiClient.decode24("563412"));
        assertEquals("563412",GtiClient.encode24(0x123456));
        assertEquals(40,GtiClient.deltaCounts(24,0xfffff0,360000));
        assertEquals(-40,GtiClient.deltaCounts(0xfffff0,24,360000));
        assertEquals(20,GtiClient.deltaCounts(10,359990,360000));
        assertThrows(IOException.class,()->GtiClient.decode24("FFFF"));
    }
}

