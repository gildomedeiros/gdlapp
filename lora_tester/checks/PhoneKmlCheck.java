package com.gdl.loratester;

import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;

public final class PhoneKmlCheck {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static Map<String,Object> fix(long sequence,long nanos){
        Map<String,Object> p=new LinkedHashMap<>();p.put("kind","derived_reading");p.put("classification","gps");
        p.put("sequence",sequence);p.put("elapsed_realtime_ns",nanos);p.put("phone_epoch_ms",1789518289000L+nanos/1_000_000);
        p.put("latitude",-28.07921);p.put("longitude",153.40115);p.put("sender_ms",nanos/1_000_000);p.put("rssi",-80);return p;
    }
    private static org.w3c.dom.Document parse(String content)throws Exception{
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }
    private static String sentence(String body){int checksum=0;for(char c:body.toCharArray())checksum^=c;return "$"+body+"*"+String.format("%02X",checksum);}
    public static void main(String[] args)throws Exception{
        KmlDocument doc=new KmlDocument("lora");String content=doc.header("A & B");
        String a=doc.record(fix(1,1_000_000_000L)),b=doc.record(fix(2,2_000_000_000L));
        check(!a.contains("<LineString>") && b.contains("<LineString>"),"Consecutive fixes connect");
        String gap=doc.record(fix(4,4_000_000_000L));check(!gap.contains("<LineString>"),"Gap does not connect");
        String duplicate=doc.record(fix(4,5_000_000_000L));check(!duplicate.contains("<LineString>"),"Duplicate retained without connecting");
        doc.record(Map.of("kind","usb_error","detail","lost USB"));
        String reconnect=doc.record(fix(5,6_000_000_000L));check(!reconnect.contains("<LineString>"),"Reconnect boundary");
        String event=doc.record(Map.of("kind","derived_reading","classification","malformed","text","bad\u0000&<]]>"));
        check(!event.contains("<Point>"),"Bad reading has no invented coordinate");
        var xml=parse(content+a+b+gap+duplicate+reconnect+event+KmlDocument.FOOTER);
        check(xml.getElementsByTagNameNS("http://www.opengis.net/kml/2.2","Point").getLength()==5,"All five received points retained");
        KmlDocument phone=new KmlDocument("phone");
        Map<String,Object> p=fix(1,10_000_000_000L);p.put("classification","phone_gps");p.put("fix_elapsed_realtime_ns",9_000_000_000L);p.put("sender_ms",null);p.put("rssi",null);
        String first=phone.record(p);p.put("sequence",2L);p.put("elapsed_realtime_ns",11_000_000_000L);
        String repeated=phone.record(p);check(!repeated.contains("<LineString>"),"Repeated GNSS fix is not connected");
        p.put("sequence",3L);p.put("elapsed_realtime_ns",12_000_000_000L);p.put("fix_elapsed_realtime_ns",11_000_000_000L);p.put("stale_fix",true);
        String stale=phone.record(p);check(!stale.contains("<LineString>"),"Stale GNSS fix is not connected");
        parse(phone.header("phone")+first+repeated+stale+KmlDocument.FOOTER);
        check(phone.record(Map.of("kind","phone_nmea")).isEmpty(),"Raw NMEA stays in raw backup");
        // Simulate the seek/replace-footer strategy: every saved state must be valid XML.
        File test=File.createTempFile("lora-kml-check",".kml");
        try(RandomAccessFile file=new RandomAccessFile(test,"rw")){
            byte[] header=doc.header("test").getBytes(StandardCharsets.UTF_8);file.write(header);long end=header.length;
            for(String fragment:new String[]{a,b,event}){
                file.seek(end);byte[] bytes=fragment.getBytes(StandardCharsets.UTF_8);file.write(bytes);end+=bytes.length;
                file.write(KmlDocument.FOOTER.getBytes(StandardCharsets.UTF_8));file.setLength(file.getFilePointer());
                file.seek(0);byte[] saved=new byte[(int)file.length()];file.readFully(saved);parse(new String(saved,StandardCharsets.UTF_8));
            }
        }finally{if(!test.delete())test.deleteOnExit();}
        NmeaFix nmea=new NmeaFix();
        nmea.accept(sentence("GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"),1_000_000_000L);
        check(nmea.current(2_000_000_000L)==0.9,"NMEA GGA HDOP");
        check(nmea.current(5_000_000_000L)==null,"Old HDOP expires");
        nmea.accept(sentence("GNGGA,123519,4807.038,N,01131.000,E,0,00,0.9,545.4,M,46.9,M,,"),6_000_000_000L);
        check(nmea.current(6_000_000_000L)==null,"No-fix sentence clears HDOP");
        nmea.accept("$GNGGA,broken*00",7_000_000_000L);check(nmea.current(7_000_000_000L)==null,"Bad checksum ignored");
        System.out.println("PASS: KML parsing, XML escaping, gaps/reconnects, repeated and stale phone fixes, incremental file validity, optional HDOP and expiry");
    }
}
