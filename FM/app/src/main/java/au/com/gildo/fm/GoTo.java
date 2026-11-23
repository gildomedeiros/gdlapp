package au.com.gildo.fm;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

final class GoTo {
    static final double SIDEREAL_DAY_SECONDS=86_164.09065;
    static final int MIN_HS_SIDEREAL=128, MAX_HS_SIDEREAL=800;
    interface Trace { void event(String name, Object... fields); }
    interface SocketFactory { DatagramSocket open() throws IOException; }
    record Point(double lat, double lon, double elevation) {}
    record MountSetup(int panCpr,int tiltCpr,String panFirmware,String tiltFirmware) {}
    record Reference(Point pivot, double bearing, double elevation, int pan, int tilt,
                     int panSign, int tiltSign) {}
    record PositionResult(String report,Long bothStartsAcknowledgedAtMs) {}
    private record Reply(String payload,long receivedAtMs) {}
    final SocketFactory sockets;
    final InetSocketAddress endpoint;
    final Trace trace;
    private long exchangeId;
    private final Set<Integer> replyPorts;
    private final int retainedReplyPorts;

    GoTo(SocketFactory sockets, InetSocketAddress endpoint, Trace trace) {
        this(sockets, endpoint, trace, false);
    }

    GoTo(SocketFactory sockets, InetSocketAddress endpoint, Trace trace, boolean distinctReplyPorts) {
        this(sockets, endpoint, trace, distinctReplyPorts ? Integer.MAX_VALUE : 0);
    }

    GoTo(SocketFactory sockets, InetSocketAddress endpoint, Trace trace, int retainedReplyPorts) {
        this.sockets = sockets;
        this.endpoint = endpoint;
        this.trace = trace;
        this.retainedReplyPorts = retainedReplyPorts;
        this.replyPorts = retainedReplyPorts > 0 ? new LinkedHashSet<>() : null;
    }


    PositionResult goToPosition(MountSetup setup, Point target, Reference ref, BooleanSupplier canStart, long press) throws IOException {
        if (!Geo.valid(target.lat, target.lon) || !Double.isFinite(target.elevation))
            throw new IOException("Invalid target coordinates or elevation");
        double range = Geo.distance(ref.pivot.lat, ref.pivot.lon, target.lat, target.lon);
        double bearing = Geo.bearing(ref.pivot.lat, ref.pivot.lon, target.lat, target.lon);
        double elevation = Geo.elevation(range, ref.pivot.elevation, target.elevation);
        double pan = Geo.delta(bearing, ref.bearing);
        double tilt = elevation - ref.elevation;
        trace.event("target_geometry", "bearing", bearing, "elevation", elevation, "rangeM", range,
            "relativePan", pan, "relativeTilt", tilt);

        int[] cpr = {setup.panCpr(),setup.tiltCpr()}, baseline = new int[2], goal = new int[2], step = new int[2];
        String startIssue = "";
        // POS-01 lists the two encoder reads before the two status checks.
        for (int i=0; i<2; i++) {
            baseline[i]=decode(exchange("j"+(i+1)).payload());
        }
        for (int i=0; i<2; i++) {
            int axis=i+1;
            trace.event("axis_setup","axis",axis,"countsPerRevolution",cpr[i],
                "firmware",i==0?setup.panFirmware():setup.tiltFirmware(),"source","GTi connection");
            if (moving(axis)) throw new IOException("Axis "+axis+" is already moving");
        }
        for (int i=0; i<2; i++) {
            goal[i] = i==0 ? panTarget(bearing,ref,cpr[i]) : tiltTarget(elevation,ref,cpr[i]);
            step[i] = delta(goal[i], baseline[i]);
            trace.event("axis_target", "axis",i+1,"baseline",baseline[i],"target",goal[i],"delta",step[i]);
        }

        for (int i=0; i<2; i++) {
            int axis=i+1;
            setPositionMode(axis, positionMode(step[i], cpr[i]), step[i]);
            setAbsoluteTarget(axis, goal[i]);
        }
        trace.event("setters_accepted");
        if (!canStart.getAsBoolean()) throw new IOException("Move cancelled or selected GPS fix expired before Start");
        int startReplies=0;
        Long firstStartAcknowledgedAtMs=null,bothStartsAcknowledgedAtMs=null;
        long firstStart=now();
        long[] replyMs={-1,-1};
        for (int i=0; i<2; i++) {
            long sentAt=now();
            trace.event("start_requested","axis",i+1,"pressToStartMs",sentAt-press);
            try {
                long receivedAt=startAxis(i+1,null);
                if(firstStartAcknowledgedAtMs==null) firstStartAcknowledgedAtMs=receivedAt;
                startReplies++;
                if(startReplies==2) bothStartsAcknowledgedAtMs=receivedAt;
                replyMs[i]=now()-sentAt;
            }
            catch (IOException e) {
                startIssue=e.getMessage();
                trace.event("start_issue","axis",i+1,"reason",startIssue);
                break;
            }
        }
        long done=now();
        String outcome=startIssue.isEmpty() ? "GoTo sent · pan and tilt Start acknowledged" :
            "GoTo Start issue: "+startIssue+"\nStart acknowledgments: "+startReplies+" of 2";
        String report=String.format(Locale.US,
            "%s\nTarget %.7f, %.7f · %.2f m elevation\nBearing %.2f° true · Elevation angle %+.2f° · Range %.1f m"+
            "\nGTi pan %d → %d counts · Tilt %d → %d counts"+
            "\nRequest to first Start reply %s · Press to Start %d ms · Pan Start reply %s · Tilt Start reply %s"+
            "\nPress to result %d ms · Arrival unverified",
            outcome,target.lat,target.lon,target.elevation,bearing,elevation,range,
            baseline[0],goal[0],baseline[1],goal[1],
            duration(firstStartAcknowledgedAtMs==null?-1:firstStartAcknowledgedAtMs-press),
            firstStart-press,duration(replyMs[0]),duration(replyMs[1]),done-press);
        return new PositionResult(report,bothStartsAcknowledgedAtMs);
    }

    private static String duration(long elapsed) { return elapsed<0?"unavailable":elapsed+" ms"; }

    long stopBothMotors() throws IOException {
        return ack("K3");
    }

    record MotionStatus(boolean panMoving, boolean tiltMoving) {
        boolean stopped() { return !panMoving && !tiltMoving; }
    }

    MotionStatus readMotionStatus() throws IOException {
        return new MotionStatus(moving(1),moving(2));
    }

    record Encoders(int pan,int tilt) {}

    interface BeforeInitialization { void run() throws IOException; }

    Encoders captureReference() throws IOException {
        return new Encoders(decode(exchange("j1").payload()),decode(exchange("j2").payload()));
    }

    MountSetup connect(BeforeInitialization beforeInitialization) throws IOException {
        int panCpr=countsPerRevolution(1),tiltCpr=countsPerRevolution(2);
        String panFirmware=firmware(1),tiltFirmware=firmware(2);
        AxisStatus pan=status(1), tilt=status(2);
        if(pan.moving()||tilt.moving()) throw new IOException("Wait for both GTi axes to stop before connecting");
        if(!pan.initialized()||!tilt.initialized()) {
            beforeInitialization.run();
            if(!pan.initialized()) ack("F1");
            if(!tilt.initialized()) ack("F2");
            requireStopped();
        }
        return new MountSetup(panCpr,tiltCpr,panFirmware,tiltFirmware);
    }

    private int countsPerRevolution(int axis) throws IOException {
        int cpr=decode(exchange("a"+axis).payload());
        if(cpr<360||cpr>0x7fffff) throw new IOException("Invalid counts per revolution on axis "+axis);
        return cpr;
    }

    private String firmware(int axis) throws IOException {
        String reply=exchange("e"+axis).payload();
        if(!reply.matches("[0-9A-Fa-f]{6}")) throw new IOException("Invalid firmware reply on axis "+axis);
        return reply;
    }



    private void requireStopped() throws IOException {
        if(moving(1)||moving(2)) throw new IOException("Wait for both GTi axes to stop before connecting");
    }

    private record AxisStatus(boolean moving,boolean initialized) {}

    private AxisStatus status(int axis) throws IOException {
        String value=exchange("f"+axis).payload();
        if(!value.matches("[0-9A-Fa-f]{3}")) throw new IOException("Invalid status on axis "+axis);
        int flags=Character.digit(value.charAt(1),16);
        if((flags&2)!=0) throw new IOException("Axis "+axis+" motor blocked");
        return new AxisStatus((flags&1)!=0,(Character.digit(value.charAt(2),16)&1)!=0);
    }

    boolean moving(int axis) throws IOException {
        AxisStatus state=status(axis);
        if(!state.initialized()) throw new IOException("GTi axis "+axis+" is uninitialized. Tap Connect GTi, then recalibrate");
        return state.moving();
    }

    record PanSample(int count,long timeMs) {}

    PanSample readPan() throws IOException { return readEncoder(1); }

    PanSample readEncoder(int axis) throws IOException {
        Reply reply=exchange("j"+axis);
        return new PanSample(decode(reply.payload()),reply.receivedAtMs());
    }

    record PanTiming(int frequency,int ratio) {
        int period(double countsPerSecond) throws IOException {
            long value=Math.round(frequency*(double)ratio/countsPerSecond);
            if(frequency==0||ratio==0||value<6||value>0xffffff)
                throw new IOException("Requested HS speed cannot be encoded from the GTi timer and ratio");
            return (int)value;
        }
    }

    PanTiming readHighSpeedPanTiming() throws IOException { return readHighSpeedTiming(1); }

    PanTiming readHighSpeedTiming(int axis) throws IOException {
        int frequency=decode(exchange("b"+axis).payload());
        String ratioPayload=exchange("g"+axis).payload();
        int ratio=ratioPayload.matches("[0-9A-Fa-f]{2}")?Integer.parseInt(ratioPayload,16):decode(ratioPayload);
        if (frequency == 0 || ratio == 0) throw new IOException("Invalid GTi HS timing for axis "+axis);
        return new PanTiming(frequency,ratio);
    }

    int highSpeedPanPeriod(PanTiming timing,double countsPerSecond) throws IOException {
        return highSpeedPeriod(1,timing,countsPerSecond);
    }

    int highSpeedPeriod(int axis,PanTiming timing,double countsPerSecond) throws IOException {
        int period=timing.period(countsPerSecond);
        trace.event(axis==1?"hs_pan_period":"hs_tilt_period","timerHz",timing.frequency(),"highSpeedRatio",timing.ratio(),
            "period",period,"countsPerSecond",countsPerSecond,"axis",axis);
        return period;
    }

    void prepareHighSpeedPan(int direction,int period) throws IOException {
        setHighSpeedMode(1,direction);
        setStepPeriod(1,period);
    }

    long setHighSpeedMode(int axis,int direction) throws IOException { return ack("G"+axis+"3"+(direction>0?"0":"1")); }
    long setStepPeriod(int axis,int period) throws IOException { return ack("I"+axis+encode(period)); }

    static String positionMode(int step, int cpr) { return Math.abs(step) * 360.0 / cpr > 1 ? "0" : "2"; }

    long setPositionMode(int axis, String mode, int step) throws IOException {
        return ack("G" + axis + mode + (step < 0 ? "1" : "0"));
    }

    long setAbsoluteTarget(int axis, int count) throws IOException { return ack("S" + axis + encode(count)); }

    long startAxis(int axis,LongConsumer starting) throws IOException {
        long received=ack("J"+axis,starting);
        trace.event("start_acknowledged","axis",axis,"receivedAtMs",received);
        return received;
    }

    long stopPan(LongConsumer sending) throws IOException { return ack("K1",sending); }

    long stopAxis(int axis,LongConsumer sent) throws IOException {
        String command="K"+axis;
        return requireAck(command,exchange(command,null,sent));
    }
    boolean panMoving() throws IOException { return moving(1); }

    static int panTarget(double bearing,Reference reference,int cpr) {
        double pan=Geo.delta(bearing,reference.bearing())*reference.panSign();
        return (reference.pan()+(int)Math.round(pan*cpr/360))&0xffffff;
    }

    static int tiltTarget(double elevation,Reference reference,int cpr) {
        double tilt=(elevation-reference.elevation())*reference.tiltSign();
        return (reference.tilt()+(int)Math.round(tilt*cpr/360))&0xffffff;
    }

    static double panHeading(int count,Reference reference,int cpr) {
        return Geo.wrap(reference.bearing()+delta(count,reference.pan())*reference.panSign()*360.0/cpr);
    }

    private long ack(String command) throws IOException { return ack(command,null); }

    private long ack(String command,LongConsumer starting) throws IOException {
        return requireAck(command,exchange(command,starting));
    }

    private static long requireAck(String command,Reply reply) throws IOException {
        if (!reply.payload().isEmpty()) throw new IOException("Unexpected acknowledgment for :"+command);
        return reply.receivedAtMs();
    }

    private Reply exchange(String command) throws IOException { return exchange(command,null); }

    private Reply exchange(String command,LongConsumer starting) throws IOException {
        return exchange(command,starting,null);
    }

    private DatagramSocket openReplySocket() throws IOException {
        if (replyPorts == null) return sockets.open();
        // A delayed reply can arrive even after its socket was closed.
        for (int attempt = 0; attempt < 16; attempt++) {
            DatagramSocket socket = sockets.open();
            if (!socket.isBound()) {
                try { socket.bind(new InetSocketAddress(0)); }
                catch (IOException | RuntimeException failure) {
                    socket.close();
                    throw failure;
                }
            }
            if (replyPorts.add(socket.getLocalPort())) {
                if (replyPorts.size() > retainedReplyPorts) {
                    var oldest = replyPorts.iterator();
                    oldest.next();
                    oldest.remove();
                }
                return socket;
            }
            socket.close();
        }
        throw new IOException("Could not reserve a distinct GTi reply port");
    }

    private Reply exchange(String command,LongConsumer starting,LongConsumer sent) throws IOException {
        String wire=":"+command+"\r";
        byte[] bytes=wire.getBytes(StandardCharsets.US_ASCII);
        long id=++exchangeId;
        int axis=Character.digit(command.charAt(1),10),localPort=-1;
        try (DatagramSocket socket=openReplySocket()) {
            socket.connect(endpoint);
            socket.setSoTimeout(700);
            long tx=now();
            localPort=socket.getLocalPort();
            trace.event("gti_tx","exchange",id,"wire",wire,"axis",axis,
                "localPort",localPort,"remote",endpoint.toString(),"txMs",tx);
            if(starting!=null) starting.accept(tx);
            socket.send(new DatagramPacket(bytes,bytes.length,endpoint));
            if(sent!=null) sent.accept(tx);
            byte[] buffer=new byte[128];
            DatagramPacket packet=new DatagramPacket(buffer,buffer.length);
            socket.receive(packet);
            long rx=now();
            String reply=new String(packet.getData(),0,packet.getLength(),StandardCharsets.US_ASCII);
            trace.event("gti_rx","exchange",id,"wire",reply,"bytesHex",hex(buffer,packet.getLength()),"rxMs",rx,"rttMs",rx-tx,
                "axis",axis,"localPort",localPort);
            if (!reply.startsWith("=")||!reply.endsWith("\r"))
                throw new IOException("GTi rejected :"+command+" ("+reply.trim()+")");
            return new Reply(reply.substring(1,reply.length()-1),rx);
        } catch(IOException e) {
            trace.event("gti_exchange_failed","exchange",id,"command",command,"reason",e.toString(),"socketRetired",true,
                "axis",axis,"localPort",localPort);
            throw new IOException(":"+command+" "+(e instanceof SocketTimeoutException?"reply timed out":e.getMessage()),e);
        }
    }
    private static String hex(byte[] bytes,int n) {
        StringBuilder value=new StringBuilder(n*2);
        for(int i=0;i<n;i++) value.append(String.format(Locale.US,"%02X",bytes[i]&255));
        return value.toString();
    }
    static int decode(String v) throws IOException {
        if(!v.matches("[0-9a-fA-F]{6}")) throw new IOException("Invalid GTi counter "+v);
        return Integer.parseInt(v.substring(4,6)+v.substring(2,4)+v.substring(0,2),16);
    }
    static String encode(int v) { return String.format(Locale.US,"%02X%02X%02X",v&255,(v>>>8)&255,(v>>>16)&255); }
    static int delta(int target,int current) { return ((target-current+0x800000)&0xffffff)-0x800000; }
    static long now() { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
}


