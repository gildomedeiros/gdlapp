package au.com.gildo.vtc;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Serialized transactions; cancellable finite GoTo and low-speed continuous slewing. */
final class GtiClient implements AutoCloseable {
    interface Transport extends AutoCloseable { String exchange(String command)throws IOException; void close(); }
    static final class Position {
        final int pan,tilt;final boolean moving;
        final String panStatus,tiltStatus,panStatusBefore,tiltStatusBefore;
        Position(int pan,int tilt,boolean moving){this(pan,tilt,moving,"","");}
        Position(int pan,int tilt,boolean moving,String panStatus,String tiltStatus){this(pan,tilt,moving,panStatus,tiltStatus,panStatus,tiltStatus);}
        Position(int pan,int tilt,boolean moving,String panStatus,String tiltStatus,String panStatusBefore,String tiltStatusBefore){
            this.pan=pan;this.tilt=tilt;this.moving=moving;this.panStatus=panStatus;this.tiltStatus=tiltStatus;
            this.panStatusBefore=panStatusBefore;this.tiltStatusBefore=tiltStatusBefore;
        }
    }
    final int[] cpr=new int[2];
    String firmware="";
    private final Object ioGate=new Object();
    private final int[] timer=new int[2],period=new int[2],slewDirection=new int[2];
    private volatile boolean continuous;
    boolean continuous(){return continuous;}
    private void motionAck(char op,int axis,String data,BooleanSupplier permitted)throws IOException {
        synchronized(ioGate){guard(permitted);ack(op,axis,data);}
    }
    static int stepPeriod(int frequency,int resolution,double speed)throws IOException {
        if(frequency<=0||resolution<360||!Double.isFinite(speed)||Math.abs(speed)>.5||speed==0)
            throw new IOException("Invalid continuous speed or mount timer");
        double value=frequency*360.0/(resolution*Math.abs(speed));
        // Ceil keeps actual quantized rate at or below the requested maximum.
        if(value<6||value>0xffffff)throw new IOException("Continuous speed is outside the mount timer range");
        return (int)Math.ceil(value);
    }
    private void stopAxis(int axis,BooleanSupplier permitted)throws IOException {
        motionAck('L',axis,"",permitted);
        for(int n=0;n<4;n++){
            guard(permitted);if(!running(status(axis)))return;
            try{Thread.sleep(50);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Axis Stop interrupted",e);}
        }
        throw new IOException("Axis Stop unconfirmed");
    }
    void rates(double pan,double tilt,BooleanSupplier permitted)throws IOException {
        double[] rates={pan,tilt};
        for(double r:rates)if(!Double.isFinite(r)||Math.abs(r)>.5)throw new IOException("Continuous rate must be at most 0.5 degrees/s");
        try {
            for(int i=0;i<2;i++) {
                guard(permitted);int axis=i+1,dir=rates[i]>0?1:rates[i]<0?-1:0;
                if(dir==0) {
                    if(slewDirection[i]!=0){stopAxis(axis,permitted);slewDirection[i]=period[i]=0;}
                    continue;
                }
                if(timer[i]==0){timer[i]=decode24(command('b',axis,""));if(timer[i]<=0)throw new IOException("Invalid GTi timer frequency");}
                int next=stepPeriod(timer[i],cpr[i],rates[i]);
                // Reconcile our last command with actual motor status before updating.
                String f=positionStatus(axis);boolean moving=running(f);
                if(moving&&slewDirection[i]==0)throw new IOException("Unexpected motor movement before continuous start");
                if(moving&&dir!=slewDirection[i]) {
                    stopAxis(axis,permitted);moving=false;
                }
                if(!moving){motionAck('G',axis,"1"+(dir>0?"0":"1"),permitted);period[i]=0;}
                if(period[i]!=next){motionAck('I',axis,encode24(next),permitted);period[i]=next;}
                if(!moving){motionAck('J',axis,"",permitted);}
                slewDirection[i]=dir;
            }
            continuous=slewDirection[0]!=0||slewDirection[1]!=0;
        }catch(IOException e){try{requestStop();}catch(IOException stop){e.addSuppressed(stop);}throw e;}
    }

    boolean initializedDuringConnect,referenceLost;
    private final Transport transport;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    GtiClient(Transport transport){this.transport=transport;}
    void connect()throws IOException {
        for(int a=1;a<=2;a++) {
            cpr[a-1]=decode24(command('a',a,""));
            if(cpr[a-1]<360||cpr[a-1]>0x7fffff)throw new IOException("Invalid mount resolution");
            String f=status(a);
            if(running(f))throw new IOException("Mount is already moving. Stop SynScan tracking first.");
            if((Character.digit(f.charAt(2),16)&1)==0){initializedDuringConnect=true;ack('F',a,"");}
            firmware+=(a==1?"":" / ")+"axis "+a+"="+command('e',a,"");
        }
    }
    Position position()throws IOException {
        return position(()->true);
    }
    Position position(BooleanSupplier permitted)throws IOException {
        // Motor replies are separate samples, not an atomic position/status snapshot.
        // A counter read during deceleration must not become an "arrived" position
        // merely because a later status reply reports stopped.
        checkRead(permitted);String beforeA=positionStatus(1);
        checkRead(permitted);String beforeB=positionStatus(2);
        checkRead(permitted);int pan=decode24(command('j',1,""));
        checkRead(permitted);int tilt=decode24(command('j',2,""));
        checkRead(permitted);String a=positionStatus(1);
        checkRead(permitted);String b=positionStatus(2);
        checkRead(permitted);
        boolean moving=running(beforeA)||running(beforeB)||running(a)||running(b);
        return new Position(pan,tilt,moving,a,b,beforeA,beforeB);
    }
    private static void checkRead(BooleanSupplier permitted) {
        if(!permitted.getAsBoolean())throw new CancellationException("Position read cancelled");
    }
    private String positionStatus(int axis)throws IOException {
        String f=status(axis);
        if((Character.digit(f.charAt(2),16)&1)==0){referenceLost=true;throw new IOException("Mount axis is no longer initialized; reconnect and recalibrate");}
        return f;
    }
    void cancel(){cancelled.set(true);}
    void begin(){cancelled.set(false);}
    void move(Position here,double panDegrees,double tiltDegrees,BooleanSupplier permitted)throws IOException {
        move(here,panDegrees,tiltDegrees,permitted,false);
    }
    void goTo(Position here,double panDegrees,double tiltDegrees,BooleanSupplier permitted)throws IOException {
        move(here,panDegrees,tiltDegrees,permitted,true);
    }
    private void move(Position here,double panDegrees,double tiltDegrees,BooleanSupplier permitted,boolean acquisition)throws IOException {
        move(here,panDegrees,tiltDegrees,permitted,acquisition,false);
    }
    boolean moveNoWait(Position here,double panDegrees,double tiltDegrees,boolean acquisition,BooleanSupplier permitted)throws IOException {
        return move(here,panDegrees,tiltDegrees,permitted,acquisition,true);
    }
    private boolean move(Position here,double panDegrees,double tiltDegrees,BooleanSupplier permitted,boolean acquisition,boolean noWait)throws IOException {
        if(here.moving&&!noWait)throw new IOException("Wait for the current move to finish");
        double[] degrees={panDegrees,tiltDegrees};int[] current={here.pan,here.tilt},steps=new int[2];
        for(int i=0;i<2;i++) {
            double limit=acquisition?45:10;
            if(!Double.isFinite(degrees[i])||Math.abs(degrees[i])>limit+0.000001)throw new IOException("Move exceeds the "+limit+" degree command limit");
            steps[i]=(int)Math.round(degrees[i]*cpr[i]/360.0);
        }
        int acknowledgedSetters=0;
        try {
            for(int i=0;i<2;i++)if(steps[i]!=0) {
                guard(permitted);
                if(running(status(i+1))&&!noWait)throw new IOException("Axis started moving outside VTC");
                guard(permitted);
                String mode=acquisition&&Math.abs(degrees[i])>1?"0":"2";
                motionAck('G',i+1,mode+(steps[i]>0?"0":"1"),permitted);
                acknowledgedSetters++;
                guard(permitted);
                int target=wrap24(current[i]+steps[i]);
                motionAck('S',i+1,encode24(target),permitted);
                // Firmware 0336C5 returned 0x800000 from h instead of the new S target
                // before START in the field log, so h is not a pre-start requirement.
                // Require the documented setter ACK for every finite destination before
                // starting either axis; the controller verifies actual encoder arrival.
            }
            for(int i=0;i<2;i++)if(steps[i]!=0) {guard(permitted);motionAck('J',i+1,"",permitted);}
            return true;
        }catch(IOException e){
            // !2 before any setter ACK means no replacement was accepted. Do not
            // Stop the original finite move or invalidate its calibration.
            if(noWait&&acknowledgedSetters==0&&e instanceof MotorNotStopped)return false;
            try{stop();}catch(IOException stop){e.addSuppressed(stop);}throw e;
        }
    }
    void stop()throws IOException {
        IOException failure=null;
        for(int attempt=0;attempt<3;attempt++) {
            try {
                requestStop();
                for(int check=0;check<4;check++) {
                    boolean pan=running(status(1)),tilt=running(status(2));
                    if(!pan&&!tilt)return;
                    try{Thread.sleep(50);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Stop interrupted",e);}
                }
                failure=new IOException("Both axes have not confirmed stopped");
            }catch(IOException e){failure=e;}
        }
        throw new IOException("Stop unconfirmed after 3 attempts",failure);
    }
    void requestStop()throws IOException {
        cancelled.set(true);IOException failure=null;
        for(int a=1;a<=2;a++)try{ack('L',a,"");}catch(IOException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        continuous=false;java.util.Arrays.fill(slewDirection,0);java.util.Arrays.fill(period,0);
        if(failure!=null)throw failure;
    }
    private void guard(BooleanSupplier permitted)throws IOException {
        if(cancelled.get()||!permitted.getAsBoolean())throw new IOException("Movement cancelled or inputs expired");
    }
    private String status(int axis)throws IOException {
        String f=command('f',axis,"");
        if(!f.matches("[0-9A-Fa-f]{3}"))throw new IOException("Invalid motor status");
        if((Character.digit(f.charAt(1),16)&2)!=0)throw new IOException("Mount reports a blocked motor");
        return f;
    }
    private static boolean running(String f){return (Character.digit(f.charAt(1),16)&1)!=0;}
    private void ack(char c,int axis,String data)throws IOException {
        if(!command(c,axis,data).isEmpty())throw new IOException("Unexpected acknowledgement for "+c+axis);
    }
    private String command(char c,int a,String data)throws IOException {
        String r;
        synchronized(ioGate){r=transport.exchange(":"+c+a+data+"\r");}
        if("!2\r".equals(r))throw new MotorNotStopped("GTi rejected "+c+a+": motor not stopped (!2)");
        if(r==null||!r.startsWith("=")||!r.endsWith("\r"))throw new IOException("GTi rejected "+c+a+": "+r);
        return r.substring(1,r.length()-1);
    }
    private static final class MotorNotStopped extends IOException {MotorNotStopped(String message){super(message);}}
    static int decode24(String s)throws IOException {
        if(s==null||!s.matches("[0-9A-Fa-f]{6}"))throw new IOException("Invalid motor counter");
        return Integer.parseInt(s.substring(4,6)+s.substring(2,4)+s.substring(0,2),16);
    }
    static String encode24(int n){return String.format(Locale.US,"%02X%02X%02X",n&255,(n>>>8)&255,(n>>>16)&255);}
    static int wrap24(int n){return n&0xffffff;}
    static int deltaCounts(int target,int current,int cpr) {
        int delta=((target-current+0x800000)&0xffffff)-0x800000;
        delta%=cpr;
        if(delta>cpr/2)delta-=cpr;if(delta< -cpr/2)delta+=cpr;
        return delta;
    }
    public void close(){cancel();transport.close();}
}

