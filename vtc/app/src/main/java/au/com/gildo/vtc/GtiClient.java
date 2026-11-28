package au.com.gildo.vtc;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Serialized transactions; cancellable finite GoTo and continuous slewing with explicit high-speed transitions. */
final class GtiClient implements AutoCloseable {
    interface Transport extends AutoCloseable {
        String exchange(String command)throws IOException;
        default String exchange(String command,Runnable sent)throws IOException {sent.run();return exchange(command);}
        default String exchange(String command,Runnable sent,int timeoutMs)throws IOException {return exchange(command,sent);}
        default void send(String command,Runnable sent)throws IOException {send(command);sent.run();}
        default void send(String command)throws IOException {throw new IOException("Transport does not support asynchronous sends");}
        void close();
    }
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
    static final double MAX_SLEW_SPEED=800.0*360/86164.0905;
    static final double LOW_SPEED_LIMIT=.5;
    static double effectiveRate(double requested){return Math.copySign(Math.min(Math.abs(requested),MAX_SLEW_SPEED),requested);}
    private final int[] highRatio=new int[2];
    private final boolean[] highMode=new boolean[2];
    private final Object ioGate=new Object();
    private final int[] timer=new int[2],period=new int[2],slewDirection=new int[2];
    private volatile boolean continuous;
    boolean continuous(){return continuous;}
    private void motionAck(char op,int axis,String data,BooleanSupplier permitted)throws IOException {
        synchronized(ioGate){guard(permitted);ack(op,axis,data);}
    }
    static int stepPeriod(int frequency,int resolution,double speed)throws IOException {
        if(frequency<=0||resolution<360||!Double.isFinite(speed)||Math.abs(speed)>5||speed==0)
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
    int encoder(int axis,BooleanSupplier permitted)throws IOException {
        checkRead(permitted);
        return decode24(command('j',axis,""));
    }
    int encoder(int axis,BooleanSupplier permitted,int timeoutMs)throws IOException {
        checkRead(permitted);
        return decode24(parseReply('j',axis,transport.exchange(":j"+axis+"\r",()->{},timeoutMs)));
    }
    boolean goToAxis(int axis,int target,double degrees,boolean noWait,BooleanSupplier permitted,
                     BooleanSupplier verifyStart,Runnable starting)throws IOException {
        return goToAxis(axis,target,degrees,noWait,permitted,()->verifyStart.getAsBoolean()?target:null,starting);
    }
    boolean goToAxis(int axis,int target,double degrees,boolean noWait,BooleanSupplier permitted,
                     java.util.function.Supplier<Integer> verifyStart,Runnable starting)throws IOException {
        return attemptGoToAxis(axis,target,degrees,noWait,permitted,verifyStart,starting).started();
    }

    record GoToResult(boolean started,boolean startSent,String reason,String command,String reply) {}

    GoToResult attemptGoToAxis(int axis,int target,double degrees,boolean noWait,BooleanSupplier permitted,
                               java.util.function.Supplier<Integer> verifyStart,Runnable starting)throws IOException {
        GoToResult cancelledResult=new GoToResult(false,false,"control_cancelled",null,null);
        synchronized(ioGate){
            if(cancelled.get()||!permitted.getAsBoolean())return cancelledResult;
            char op='G';
            try {
                axisCommand(op,axis,(Math.abs(degrees)>1?"0":"2")+(degrees>0?"0":"1"),noWait);
                if(cancelled.get()||!permitted.getAsBoolean())return cancelledResult;
                op='S';
                axisCommand(op,axis,encode24(target),noWait);
                while(!cancelled.get()&&permitted.getAsBoolean()){
                    Integer updated=verifyStart.get();
                    if(updated==null)return new GoToResult(false,false,"pre_start_check",null,null);
                    if(updated==target){
                        op='J';
                        axisCommand(op,axis,"",noWait,starting);
                        return new GoToResult(true,true,null,null,null);
                    }
                    double updatedDegrees=degrees+deltaCounts(updated,target,cpr[axis-1])*360.0/cpr[axis-1];
                    if((Math.abs(degrees)>1)!=(Math.abs(updatedDegrees)>1)){
                        op='G';
                        axisCommand(op,axis,(Math.abs(updatedDegrees)>1?"0":"2")+(updatedDegrees>0?"0":"1"),noWait);
                    }
                    degrees=updatedDegrees;
                    target=updated;
                    op='S';
                    axisCommand(op,axis,encode24(target),noWait);
                }
                return cancelledResult;
            }catch(MotorNotStopped rejected){
                return new GoToResult(false,op=='J',"gti_motor_not_stopped",":"+op+axis,"!2");
            }
        }
    }
    void stopTrackingAxis(int axis,BooleanSupplier permitted,Runnable sent)throws IOException {
        synchronized(ioGate){
            if(cancelled.get()||!permitted.getAsBoolean())return;
            axisCommand('K',axis,"",true,sent);
            slewDirection[axis-1]=period[axis-1]=0;
            highMode[axis-1]=false;
            continuous=slewDirection[0]!=0||slewDirection[1]!=0;
        }
    }
    private void axisCommand(char op,int axis,String data,boolean noWait)throws IOException {
        axisCommand(op,axis,data,noWait,()->{});
    }
    private void axisCommand(char op,int axis,String data,boolean noWait,Runnable sent)throws IOException {
        String request=":"+op+axis+data+"\r";
        if(noWait)transport.send(request,sent);
        else if(!parseReply(op,axis,transport.exchange(request,sent)).isEmpty())
            throw new IOException("Unexpected acknowledgement for "+op+axis);
    }
    void rates(double pan,double tilt,BooleanSupplier permitted)throws IOException {
        rates(pan,tilt,permitted,0);
    }
    void rateAxis(int axis,double rate,BooleanSupplier permitted)throws IOException {
        rates(axis==1?rate:0,axis==2?rate:0,permitted,axis);
    }
    private void rates(double pan,double tilt,BooleanSupplier permitted,int selectedAxis)throws IOException {
        double[] rates={pan,tilt};
        for(double r:rates)if(!Double.isFinite(r)||Math.abs(r)>5)throw new IOException("Continuous rate must be at most 5 degrees/s");
        rates[0]=effectiveRate(pan);rates[1]=effectiveRate(tilt);
        try {
            for(int i=0;i<2;i++) {
                if(selectedAxis!=0&&selectedAxis!=i+1)continue;
                guard(permitted);int axis=i+1,dir=rates[i]>0?1:rates[i]<0?-1:0;
                if(dir==0) {
                    if(slewDirection[i]!=0){stopAxis(axis,permitted);slewDirection[i]=period[i]=0;}
                    continue;
                }
                if(timer[i]==0){timer[i]=decode24(command('b',axis,""));if(timer[i]<=0)throw new IOException("Invalid GTi timer frequency");}
                boolean high=Math.abs(rates[i])>LOW_SPEED_LIMIT;
                if(high&&highRatio[i]==0){
                    String ratio=command('g',axis,"");
                    if(!ratio.matches("[0-9A-Fa-f]{2}"))throw new IOException("Invalid GTi high-speed ratio");
                    highRatio[i]=Integer.parseInt(ratio,16);
                    if(highRatio[i]<1)throw new IOException("Invalid GTi high-speed ratio");
                }
                int next=stepPeriod(timer[i],cpr[i],rates[i]/(high?highRatio[i]:1));
                // Reconcile our last command with actual motor status before updating.
                String f=positionStatus(axis,permitted);boolean moving=running(f);
                if(moving&&slewDirection[i]==0)throw new IOException("Unexpected motor movement before continuous start");
                if(moving&&(dir!=slewDirection[i]||highMode[i]!=high||(high&&period[i]!=next))) {
                    stopAxis(axis,permitted);moving=false;
                }
                if(!moving){motionAck('G',axis,(high?"3":"1")+(dir>0?"0":"1"),permitted);period[i]=0;}
                if(period[i]!=next){motionAck('I',axis,encode24(next),permitted);period[i]=next;}
                if(!moving){motionAck('J',axis,"",permitted);}
                slewDirection[i]=dir;highMode[i]=high;
            }
            continuous=slewDirection[0]!=0||slewDirection[1]!=0;
        }catch(IOException e){try{requestStop();}catch(IOException stop){e.addSuppressed(stop);}throw e;}
    }

    boolean initializedDuringConnect,referenceLost;
    private final Transport transport;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    GtiClient(Transport transport){this.transport=transport;}
    void connect()throws IOException {connect(null,()->true);}
    void reconnect(GtiClient previous,BooleanSupplier permitted)throws IOException {
        connect(java.util.Objects.requireNonNull(previous),permitted);
    }

    private void connect(GtiClient previous,BooleanSupplier permitted)throws IOException {
        for(int axis=1;axis<=2;axis++) {
            checkRead(permitted);
            int resolution=decode24(command('a',axis,""));
            if(previous!=null&&resolution!=previous.cpr[axis-1]) {
                // Position and resolution replies have the same six-digit format.
                int expected=previous.cpr[axis-1];
                checkRead(permitted);
                int reread=decode24(command('a',axis,""));
                if(reread!=expected)throw new IOException("GTi axis "+axis+" counts per revolution: expected "
                    +expected+", read "+resolution+", then "+reread);
                resolution=reread;
            }
            if(resolution<360||resolution>0x7fffff)throw new IOException("Invalid mount resolution");
            cpr[axis-1]=resolution;

            if(previous!=null) {
                positionStatus(axis,permitted);
            } else {
                checkRead(permitted);
                String status=status(axis);
                if(running(status))throw new IOException("Mount is already moving. Stop SynScan tracking first.");
                if(!initialized(status)) {
                    initializedDuringConnect=true;
                    ack('F',axis,"");
                }
            }
            checkRead(permitted);
            firmware+=(axis==1?"":" / ")+"axis "+axis+"="+command('e',axis,"");
        }
        if(previous!=null&&!firmware.equalsIgnoreCase(previous.firmware))
            throw new IOException("GTi firmware during reconnect: expected "+previous.firmware+", read "+firmware);
    }

    Position position()throws IOException {
        return position(()->true);
    }
    Position position(BooleanSupplier permitted)throws IOException {
        // Motor replies are separate samples, not an atomic position/status snapshot.
        // A counter read during deceleration must not become an "arrived" position
        // merely because a later status reply reports stopped.
        String beforeA=positionStatus(1,permitted);
        String beforeB=positionStatus(2,permitted);
        checkRead(permitted);int pan=decode24(command('j',1,""));
        checkRead(permitted);int tilt=decode24(command('j',2,""));
        String a=positionStatus(1,permitted);
        String b=positionStatus(2,permitted);
        checkRead(permitted);
        boolean moving=running(beforeA)||running(beforeB)||running(a)||running(b);
        return new Position(pan,tilt,moving,a,b,beforeA,beforeB);
    }
    private static void checkRead(BooleanSupplier permitted) {
        if(!permitted.getAsBoolean())throw new CancellationException("Position read cancelled");
    }
    private String positionStatus(int axis,BooleanSupplier permitted)throws IOException {
        checkRead(permitted);
        String status=status(axis);
        if(initialized(status))return status;

        checkRead(permitted);
        status=status(axis);
        checkRead(permitted);
        if(initialized(status))return status;

        referenceLost=true;
        throw new IOException("GTi axis "+axis+" reported uninitialized twice");
    }

    private static boolean initialized(String status) {
        return (Character.digit(status.charAt(2),16)&1)!=0;
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
        continuous=false;java.util.Arrays.fill(slewDirection,0);java.util.Arrays.fill(period,0);java.util.Arrays.fill(highMode,false);
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
        return parseReply(c,a,r);
    }
    private String parseReply(char c,int a,String r)throws IOException {
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

