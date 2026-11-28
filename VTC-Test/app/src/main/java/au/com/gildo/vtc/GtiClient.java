package au.com.gildo.vtc;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sky-Watcher motor protocol; coordinates are motor counts, not geographic bearings. */
final class GtiClient implements AutoCloseable {
    interface Transport extends AutoCloseable {
        String exchange(String command) throws IOException;
        void close();
    }
    static final class Position {
        final int pan, tilt;
        final boolean moving;
        Position(int pan,int tilt,boolean moving) { this.pan=pan; this.tilt=tilt; this.moving=moving; }
    }
    volatile String axisStatus = "Axis status not read";
    volatile String diagnostics = "No movement requested";
    final int[] cpr = new int[2];
    private final int[] timer = new int[2];
    private final Transport transport;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    GtiClient(Transport transport) { this.transport=transport; }
    void connect() throws IOException {
        for (int axis=1;axis<=2;axis++) {
            cpr[axis-1] = decode24(command('a',axis,""));
            if (cpr[axis-1] < 360 || cpr[axis-1] > 0x7fffff) throw new IOException("Invalid counts/revolution from axis " + axis);
            timer[axis-1] = decode24(command('b',axis,""));
            if (timer[axis-1] <= 0) throw new IOException("Invalid motor timer frequency");
            String status = status(axis);
            if (running(status)) throw new IOException("Mount is moving. Stop motion/tracking in SynScan first.");
            if ((Character.digit(status.charAt(2),16)&1)==0) command('F',axis,"");
        }
    }
    Position position() throws IOException {
        int pan = decode24(command('j',1,""));
        int tilt = decode24(command('j',2,""));
        String panStatus=status(1), tiltStatus=status(2);
        axisStatus="Live status: pan f="+panStatus+", tilt f="+tiltStatus;
        boolean moving=running(panStatus) || running(tiltStatus);
        return new Position(pan,tilt,moving);
    }
    void cancel() { cancelled.set(true); }
    void beginOperation() { cancelled.set(false); }
    void stop() throws IOException {
        cancelled.set(true);
        IOException failure = null;
        for (int axis=1;axis<=2;axis++) {
            try { command('L',axis,""); } catch(IOException error) { failure=error; }
        }
        if (failure!=null) throw failure;
    }
    void moveBy(int axis,double degrees) throws IOException { moveBy(axis,degrees,false); }
    void moveBy(int axis,double degrees,boolean fast) throws IOException {
        if (axis!=1 && axis!=2) throw new IOException("Invalid axis");
        Position here=position();
        if (here.moving) throw new IOException("Wait until mount stops, or press STOP.");
        int increment=(int)Math.round(degrees*cpr[axis-1]/360.0);
        int pan=here.pan,tilt=here.tilt;
        if (axis==1) pan=wrap24(pan+increment); else tilt=wrap24(tilt+increment);
        goTo(here,new Position(pan,tilt,false),fast);
    }
    void goToSaved(Position target) throws IOException { goToSaved(target,false); }
    void goToSaved(Position target,boolean fast) throws IOException {
        Position here=position();
        if (here.moving) throw new IOException("Wait until mount stops, or press STOP.");
        goTo(here,target,fast);
    }
    interface Progress { void update(Position position); }
    void moveByAtSpeed(int axis,double degrees,double rate,Progress progress) throws IOException {
        if(axis!=1 && axis!=2) throw new IOException("Invalid axis");
        Position here=position();
        int increment=(int)Math.round(degrees*cpr[axis-1]/360.0);
        Position target=new Position(axis==1?wrap24(here.pan+increment):here.pan,
                axis==2?wrap24(here.tilt+increment):here.tilt,false);
        moveAtSpeed(here,target,rate,progress);
    }
    void goToAtSpeed(Position target,double rate,Progress progress) throws IOException {
        moveAtSpeed(position(),target,rate,progress);
    }
    private void moveAtSpeed(Position here,Position target,double rate,Progress progress) throws IOException {
        if(!Double.isFinite(rate) || rate<0.02 || rate>1.0) throw new IOException("Test speed must be 0.02–1 degree/sec");
        if(here.moving) throw new IOException("Wait until mount stops, or press STOP.");
        diagnostics="Speed test requested: "+rate+"°/s (app-controlled stop)";
        int[] current={here.pan,here.tilt}, desired={target.pan,target.tilt}, direction=new int[2];
        boolean[] active=new boolean[2]; int[] periods={-1,-1};
        double longest=0;
        for(int i=0;i<2;i++) {
            int delta=shortestDelta(desired[i],current[i],cpr[i]);
            double distance=Math.abs(delta*360.0/cpr[i]);
            if(distance>10.0001) throw new IOException("Return is over 10 degrees away; move closer first.");
            direction[i]=Integer.signum(delta); active[i]=Math.abs(delta)>2;
            longest=Math.max(longest,distance);
        }
        if(!active[0] && !active[1]) { progress.update(here);return; }
        long deadline=System.nanoTime()+(long)((longest/rate+30)*1_000_000_000L);
        try {
            for(int i=0;i<2;i++) if(active[i]) {
                checkCancel();
                command('G',i+1,direction[i]<0?"11":"10"); // constant-rate low-speed mode
                double remaining=Math.abs(shortestDelta(desired[i],current[i],cpr[i])*360.0/cpr[i]);
                periods[i]=setRate(i,Math.min(rate,Math.max(0.02,remaining*2)));
                checkCancel();command('J',i+1,"");
            }
            while(active[0] || active[1]) {
                checkCancel();
                if(System.nanoTime()>deadline) throw new IOException("Movement deadline exceeded");
                for(int i=0;i<2;i++) if(active[i]) {
                    checkCancel();
                    current[i]=decode24(command('j',i+1,""));
                    int remaining=shortestDelta(desired[i],current[i],cpr[i]);
                    if(Math.abs(remaining)<=2 || Integer.signum(remaining)!=direction[i]) {
                        command('L',i+1,"");active[i]=false;
                    } else {
                        double remainingDegrees=Math.abs(remaining*360.0/cpr[i]);
                        double desiredRate=Math.min(rate,Math.max(0.02,remainingDegrees*2));
                        int period=ratePeriod(i,desiredRate);
                        if(Math.abs(period-periods[i])>Math.max(1,periods[i]/10)) {
                            command('I',i+1,encode24(period));periods[i]=period;
                        }
                    }
                }
                progress.update(new Position(current[0],current[1],active[0]||active[1]));
                if(active[0] || active[1]) {
                    try { Thread.sleep(80); } catch(InterruptedException error) {
                        Thread.currentThread().interrupt();throw new IOException("Movement interrupted",error);
                    }
                }
            }
            Position after=position();
            if(after.moving) throw new IOException("Motor stop not confirmed");
            progress.update(after);
        } finally {
            // Even an ambiguous START reply or a dropped position packet requires STOP on both axes.
            stop();
        }
    }
    private int ratePeriod(int index,double degreesPerSecond) throws IOException {
        long period=Math.round(timer[index]*360.0/(cpr[index]*degreesPerSecond));
        if(period<6 || period>0xffffff) throw new IOException("Requested speed outside motor timer range");
        return (int)period;
    }
    private int setRate(int index,double rate) throws IOException {
        int period=ratePeriod(index,rate);command('I',index+1,encode24(period));
        diagnostics += "\nAxis "+(index+1)+": I="+period+", timer="+timer[index]+", CPR="+cpr[index];return period;
    }
    private void goTo(Position here,Position target,boolean fast) throws IOException {
        diagnostics="Native GoTo requested: "+(fast?"Fast":"Slow")+" (mount-controlled stop)";
        int[] current={here.pan,here.tilt}, desired={target.pan,target.tilt};
        int[] delta=new int[2];
        for(int i=0;i<2;i++) {
            delta[i]=shortestDelta(desired[i],current[i],cpr[i]);
            if(Math.abs(delta[i]*360.0/cpr[i])>10.0001)
                throw new IOException("Saved position is over 10 degrees away. Use small moves to return closer first.");
        }
        try {
            for(int i=0;i<2;i++) {
                checkCancel();
                if(delta[i]==0) continue;
                String mode=fast ? (delta[i]<0?"01":"00") : (delta[i]<0?"21":"20");
                command('G',i+1,mode);
                String readback=status(i+1);
                diagnostics += "\nAxis "+(i+1)+": G="+mode+", f="+readback
                        +", high-speed flag="+((Character.digit(readback.charAt(0),16)&4)!=0?"on":"off");
                checkCancel();
                command('S',i+1,encode24(wrap24(current[i]+delta[i])));
            }
            for(int i=0;i<2;i++) {
                checkCancel();
                if(delta[i]!=0) command('J',i+1,"");
            }
        } catch(IOException error) {
            try { stop(); } catch(IOException stopError) { error.addSuppressed(stopError); }
            throw error;
        }
    }
    private void checkCancel() throws IOException { if(cancelled.get()) throw new IOException("Movement cancelled"); }
    double degreesFrom(int raw,int reference,int axis) { return shortestDelta(raw,reference,cpr[axis-1])*360.0/cpr[axis-1]; }
    private String status(int axis) throws IOException {
        String value=command('f',axis,"");
        if(!value.matches("[0-9A-Fa-f]{3}")) throw new IOException("Invalid axis status");
        if((Character.digit(value.charAt(1),16)&2)!=0) throw new IOException("Axis reports blocked motor");
        return value;
    }
    private static boolean running(String status) { return (Character.digit(status.charAt(1),16)&1)!=0; }
    private String command(char cmd,int axis,String data) throws IOException {
        String reply=transport.exchange(":"+cmd+axis+data+"\r");
        if(reply==null || !reply.endsWith("\r") || !reply.startsWith("=")) throw new IOException("GTi rejected " + cmd + axis + ": " + reply);
        return reply.substring(1,reply.length()-1);
    }
    static int decode24(String text) throws IOException {
        if(text==null || !text.matches("[0-9A-Fa-f]{6}")) throw new IOException("Invalid 24-bit motor value");
        return Integer.parseInt(text.substring(4,6)+text.substring(2,4)+text.substring(0,2),16);
    }
    static String encode24(int value) {
        return String.format(Locale.US,"%02X%02X%02X",value&255,(value>>>8)&255,(value>>>16)&255);
    }
    static int wrap24(int value) { return value&0xffffff; }
    static int shortestDelta(int target,int current,int cpr) {
        int raw=(target-current+0x800000)&0xffffff;
        raw-=0x800000;
        int delta=raw%cpr;
        if(delta>cpr/2) delta-=cpr;
        if(delta< -cpr/2) delta+=cpr;
        return delta;
    }
    public void close() { transport.close(); }
}




