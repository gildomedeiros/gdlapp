package au.com.gildo.vtc;

import android.net.*;
import android.os.SystemClock;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;

/** Receives the existing VT LoRa wire protocol on the selected Wi-Fi network. */
final class GpsFeed implements AutoCloseable {
    static final class Fix {
        final LoRaTelemetry packet;final long receivedAt,epoch;
        Fix(LoRaTelemetry packet,long receivedAt,long epoch){this.packet=packet;this.receivedAt=receivedAt;this.epoch=epoch;}
    }
    private final Network network;
    private final Settings s;
    private final SessionLog log;
    private final LoRaTelemetry.Tracker tracker=new LoRaTelemetry.Tracker();
    private final ArrayBlockingQueue<Fix> observations=new ArrayBlockingQueue<>(64);
    private volatile boolean running=true;
    private volatile DatagramSocket socket;
    private volatile Fix latest;
    private volatile long lastAcceptedAt=-1;
    long lastAcceptedAt(){return lastAcceptedAt;}
    private final LoRaActivity activity=new LoRaActivity();
    private volatile long continuity;
    private volatile long lastReceiverAt=-1;
    private volatile boolean socketOpen,ended;
    private volatile String connectionError="";
    private volatile String status="Waiting for LoRa GPS";
    GpsFeed(Network network,Settings s,SessionLog log){this.network=network;this.s=s;this.log=log;}
    void start(){Thread t=new Thread(this::receive,"vtc-gps");t.setDaemon(true);t.start();}
    Fix latest(){return latest;}
    Fix poll(){return observations.poll();}
    void clearObservations(){observations.clear();}
    void discardUnusedObservations(String reason) {
        ArrayList<Fix> discarded=new ArrayList<>();
        int count=observations.drainTo(discarded);
        if(count>0)log.record("gps_observations_discarded","reason",reason,"count",count,
            "firstReceivedAtMs",discarded.get(0).receivedAt,"lastReceivedAtMs",discarded.get(count-1).receivedAt,
            "continuity",continuity,"latestFixRetained",true);
    }
    long continuity(){return continuity;}
    String status(){return status;}
    String display(){
        long now=SystemClock.elapsedRealtime();Fix f=latest;
        boolean fresh=f!=null&&f.epoch==continuity&&now>=f.receivedAt&&now-f.receivedAt<=s.gpsAge*1000;
        String accepted=f==null?"No accepted GPS fix":String.format(java.util.Locale.US,
            "Last accepted fix: seq %d · age %.1f s\n%.7f, %.7f",f.packet.sequence,(now-f.receivedAt)/1000.0,f.packet.lat,f.packet.lon);
        return connectionStatus()+"\n"+status+"\nGPS for aiming: "+(fresh?"FRESH":"UNAVAILABLE")+"\n"+accepted+"\n\n"+activity.display(now);
    }
    String connectionStatus() {
        if(!connectionError.isEmpty())return connectionError;
        if(ended)return "Disconnected";
        if(!socketOpen)return "Opening receiver connection";
        long now=SystemClock.elapsedRealtime();
        if(lastReceiverAt<0)return "Waiting for receiver reply";
        if(now-lastReceiverAt>10000)return "No receiver reply for "+((now-lastReceiverAt)/1000)+" s";
        Fix f=latest;
        return f!=null&&f.epoch==continuity&&now>=f.receivedAt&&now-f.receivedAt<=s.gpsAge*1000
            ?"Receiver responding · GPS fresh":"Receiver responding · waiting for valid GPS";
    }
    private void invalidate(String reason) {
        latest=null;observations.clear();continuity++;status=reason;activity.decision(reason);log.event("gps_invalid",reason);
    }
    private void receive() {
        try(DatagramSocket udp=new DatagramSocket(null)) {
            socket=udp;network.bindSocket(udp);udp.bind(new InetSocketAddress(s.receivePort));udp.setSoTimeout(350);socketOpen=true;
            log.record("gps_socket_open","receiverHost",s.receiverHost,"receiverPort",s.receiverPort,"receivePort",s.receivePort,"network",network.toString());
            InetAddress receiver=InetAddress.getByName(s.receiverHost);long hello=-1;
            byte[] buffer=new byte[65535];
            while(running) {
                long now=SystemClock.elapsedRealtime();
                if(hello<0||now-hello>=5000) {
                    byte[] bytes="HELLO".getBytes(StandardCharsets.US_ASCII);
                    udp.send(new DatagramPacket(bytes,bytes.length,receiver,s.receiverPort));hello=now;
                    log.record("gps_hello_tx","message","HELLO","host",s.receiverHost,"port",s.receiverPort,"localPort",s.receivePort);
                }
                DatagramPacket p=new DatagramPacket(buffer,buffer.length);
                try{udp.receive(p);}catch(SocketTimeoutException e){log.record("gps_receive_timeout","timeoutMs",350,"lastAcceptedAtMs",latest==null?null:latest.receivedAt);continue;}
                long at=SystemClock.elapsedRealtime();
                String message=new String(buffer,0,p.getLength(),StandardCharsets.UTF_8);
                boolean trusted=p.getAddress().equals(receiver)&&p.getPort()==s.receiverPort;
                log.record("gps_datagram","receivedAtMs",at,"remoteHost",p.getAddress().getHostAddress(),"remotePort",p.getPort(),"trustedEndpoint",trusted,
                    "byteCount",p.getLength(),"text",message,"rawBase64",android.util.Base64.encodeToString(buffer,0,p.getLength(),android.util.Base64.NO_WRAP));
                if(!trusted){log.event("gps_rejected","Unexpected source address or port");continue;}
                lastReceiverAt=at;activity.datagram(at);
                for(String line:message.split("\n")) {
                    line=line.trim();activity.line(line,at);
                    if(line.equals("GDL_LILYGO_READY")){status="LoRa receiver connected; waiting for GPS";log.record("gps_receiver_ready","receivedAtMs",at);continue;}
                    if(line.startsWith("RAW,NOFIX,")){invalidate("Transmitter has no GPS fix");continue;}
                    try {
                        LoRaTelemetry packet=LoRaTelemetry.parse(line);
                        log.record("gps_parsed","telemetry",packet.fields(),"receivedAtMs",at);
                        long restarts=tracker.restarts;
                        if(!tracker.accept(packet,at)){activity.decision("Received but rejected: duplicate/out-of-order GPS");log.record("gps_rejected","reason","Duplicate/out-of-order sequence","telemetry",packet.fields(),
                            "baselineSequence",tracker.lastSequence(),"repeats",tracker.repeats,"older",tracker.older,"gaps",tracker.gaps,"restarts",tracker.restarts);continue;}
                        if(tracker.restarts!=restarts)invalidate("GPS transmitter restarted; rebuilding evidence");
                        Fix old=latest;
                        if(old!=null&&at>old.receivedAt&&at-old.receivedAt<=s.gpsAge*1000) {
                            double speed=Geo.distance(old.packet.lat,old.packet.lon,packet.lat,packet.lon)/((at-old.receivedAt)/1000.0);
                            if(speed>s.maxGpsSpeed){
                                log.record("gps_rejected","reason","Implausible position jump","speedMps",speed,"limitMps",s.maxGpsSpeed,"telemetry",packet.fields(),"previous",old.packet.fields());
                                // Preserve the last accepted position for the next speed comparison,
                                // but break direction/quiet evidence immediately.
                                observations.clear();continuity++;
                                status="GPS jump rejected; awaiting consistent position";activity.decision(status);continue;
                            }
                        }
                        Fix fix=new Fix(packet,at,continuity);
                        if(!observations.offer(fix)){invalidate("GPS processing backlog; rebuilding evidence");fix=new Fix(packet,at,continuity);observations.offer(fix);}
                        lastAcceptedAt=at;latest=fix;status="Receiving surfer GPS";activity.decision("Latest GPS accepted for aiming");
                        log.record("gps_accepted","telemetry",packet.fields(),"receivedAtMs",at,"continuity",continuity,"queuedObservations",observations.size(),
                            "repeats",tracker.repeats,"older",tracker.older,"gaps",tracker.gaps,"restarts",tracker.restarts);
                    }catch(IllegalArgumentException e){
                        activity.decision("Received but not accepted as GPS: "+e.getMessage());
                        log.error("gps_rejected",e,"rawLine",line);
                        if(line.startsWith("RX,GPS,"))invalidate("Invalid GPS packet: "+e.getMessage());
                    }
                }
            }
        }catch(Exception e){
            if(running){connectionError=LoRaConnectionError.describe(e,s.receivePort);
                log.error("gps_socket_error",e,"running",true,"receivePort",s.receivePort,"userMessage",connectionError);
                invalidate(connectionError);}
            else log.record("gps_socket_close_result","requestedClose",true,"result",e.toString());
        }
        finally{socket=null;socketOpen=false;ended=true;log.event("gps_socket_closed","Receiver ended");}
    }
    public void close(){running=false;ended=true;latest=null;observations.clear();DatagramSocket udp=socket;if(udp!=null)udp.close();}
}

