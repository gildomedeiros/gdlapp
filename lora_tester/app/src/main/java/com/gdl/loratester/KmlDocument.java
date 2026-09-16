package com.gdl.loratester;

import java.time.Instant;
import java.util.Map;

/** Streaming KML fragments; every coordinate is a received fix, never interpolation. */
final class KmlDocument {
    static final String FOOTER="</Document></kml>\n";
    private final String source;
    private String previousCoordinate;
    private long previousSequence, previousNanos, previousFixNanos, previousSender;

    KmlDocument(String source) { this.source=source; }
    String header(String name) {
        String color=source.equals("phone")?"ffffa438":"ff38a4ff";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>"+escape(name)+"</name>"
            +"<description>"+escape("Source: "+source+". Phone reception timestamps are UTC. Points use recorded latitude/longitude and are clamped to ground; altitude, if available, is metadata. Tracks break at missing sequences, repeated fixes, restarts, USB boundaries or gaps over 1.5 seconds. Events without coordinates remain in this file. Exact raw bytes and NMEA are also saved in the matching JSONL file.")+"</description>"
            +"<Style id=\"position\"><IconStyle><color>"+color+"</color><scale>0.65</scale><Icon><href>https://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href></Icon></IconStyle><LabelStyle><scale>0</scale></LabelStyle><LineStyle><color>"+color+"</color><width>3</width></LineStyle></Style>\n";
    }
    static String escape(Object value) {
        String text=String.valueOf(value); StringBuilder out=new StringBuilder();
        text.codePoints().forEach(c->{
            if(c=='&') out.append("&amp;"); else if(c=='<') out.append("&lt;"); else if(c=='>') out.append("&gt;");
            else if(c=='\"') out.append("&quot;"); else if(c=='\'') out.append("&apos;");
            else if(c==9 || c==10 || c==13 || (c>=32 && c<=0xd7ff) || (c>=0xe000 && c<=0xfffd) || (c>=0x10000 && c<=0x10ffff)) out.appendCodePoint(c);
            else out.append(String.format("[U+%04X]",c));
        });
        return out.toString();
    }
    private static long number(Map<String,Object> values,String key,long fallback) {
        Object value=values.get(key); return value instanceof Number?((Number)value).longValue():fallback;
    }
    private static double decimal(Map<String,Object> values,String key) {
        Object value=values.get(key); return value instanceof Number?((Number)value).doubleValue():Double.NaN;
    }
    String record(Map<String,Object> values) {
        String kind=String.valueOf(values.get("kind"));
        // These high-volume supporting records are preserved in JSONL. GNSS metrics
        // are included in each phone fix and all non-coordinate receiver readings below.
        if(kind.equals("udp_datagram") || kind.equals("usb_chunk") || kind.equals("phone_nmea") || kind.equals("phone_gnss_status")) return "";
        String classification=String.valueOf(values.get("classification"));
        boolean gps=classification.equals("gps") || classification.equals("phone_gps");
        if(kind.equals("wifi_error") || kind.equals("wifi_connected") || kind.equals("wifi_disconnected") || kind.equals("usb_error") || kind.equals("usb_disconnected") || kind.equals("usb_connected")
            || kind.equals("provider_disabled") || kind.equals("gnss_stopped") || kind.equals("session_start")
            || classification.equals("receiver_status")) previousCoordinate=null;
        double lat=decimal(values,"latitude"),lon=decimal(values,"longitude");
        gps=gps && Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat)<=90 && Math.abs(lon)<=180;
        long sequence=number(values,"sequence",-1),now=number(values,"elapsed_realtime_ns",-1);
        long fix=number(values,"fix_elapsed_realtime_ns",now),sender=number(values,"sender_ms",0);
        String coordinate=Double.toString(lon)+","+Double.toString(lat)+",0";
        boolean connect=gps && previousCoordinate!=null && sequence==previousSequence+1 && now>previousNanos
            && now-previousNanos<=1_500_000_000L && fix>previousFixNanos && sender>=previousSender;
        if(Boolean.TRUE.equals(values.get("stale_fix"))) connect=false;
        String label=gps?source+" #"+sequence:source+" "+(values.containsKey("classification")?classification:kind);
        StringBuilder body=new StringBuilder("<Placemark><name>").append(escape(label)).append("</name>");
        body.append("<description>");
        StringBuilder html=new StringBuilder();
        for(Map.Entry<String,Object> entry:values.entrySet()) html.append("<b>").append(escape(entry.getKey())).append(":</b> ").append(escape(entry.getValue())).append("<br/>");
        body.append(escape(html)).append("</description>");
        long wall=number(values,"phone_epoch_ms",-1);
        if(wall>=0) body.append("<TimeStamp><when>").append(Instant.ofEpochMilli(wall)).append("</when></TimeStamp>");
        body.append("<ExtendedData>");
        for(Map.Entry<String,Object> entry:values.entrySet()) body.append("<Data name=\"").append(escape(entry.getKey())).append("\"><value>").append(escape(entry.getValue())).append("</value></Data>");
        body.append("</ExtendedData>");
        if(gps) {
            body.append("<styleUrl>#position</styleUrl><MultiGeometry><Point><altitudeMode>clampToGround</altitudeMode><coordinates>").append(coordinate).append("</coordinates></Point>");
            if(connect) body.append("<LineString><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode><coordinates>").append(previousCoordinate).append(' ').append(coordinate).append("</coordinates></LineString>");
            body.append("</MultiGeometry>");
            previousCoordinate=Boolean.TRUE.equals(values.get("stale_fix"))?null:coordinate;
            previousSequence=sequence; previousNanos=now; previousFixNanos=fix; previousSender=sender;
        } else if(classification.equals("phone_invalid")) previousCoordinate=null;
        return body.append("</Placemark>\n").toString();
    }
}
