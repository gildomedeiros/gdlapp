package au.com.gildo.vtc;
import java.util.Locale;

/** Display-only receiver activity; never supplies or refreshes an aiming fix. */
final class LoRaActivity {
    private long packets,messages,lastPacket=-1,lastMessage=-1;
    private String received="No surfer message received",decision="Waiting for surfer telemetry";
    synchronized void datagram(long at){packets++;lastPacket=at;}
    synchronized void line(String line,long at){
        if(line.isEmpty()||line.equals("GDL_LILYGO_READY"))return;
        messages++;lastMessage=at;
        String raw=line.replaceAll("[\\p{Cntrl}]"," ");if(raw.length()>320)raw=raw.substring(0,320)+"…";
        received="Latest message: "+raw;
        try{
            LoRaTelemetry p=LoRaTelemetry.parse(line);
            received=String.format(Locale.US,"Latest received GPS · seq %d\n%.7f, %.7f\nSatellites %d · HDOP %.2f · RSSI %d dBm · SNR %.1f\nMissed %d\n%s",
                p.sequence,p.lat,p.lon,p.satellites,p.hdop,p.rssi,p.snr,p.missed,received);
        }catch(IllegalArgumentException ignored){/* NOFIX and unknown formats remain visible verbatim. */}
    }
    synchronized void decision(String value){decision=value;}
    synchronized String display(long now){
        return "Receiver packets: "+packets+" · last "+age(lastPacket,now)+
            "\nSurfer/data messages: "+messages+" · last "+age(lastMessage,now)+"\n"+decision+"\n"+received;
    }
    private static String age(long at,long now){return at<0?"not received":String.format(Locale.US,"%.1f s ago",Math.max(0,now-at)/1000.0);}
}
