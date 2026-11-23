package au.com.gildo.fm;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;

final class Kml {
    static final String MIME_TYPE="application/vnd.google-earth.kml+xml";

    static void open(Writer out,String name,String summary) throws IOException {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"+
            "<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n"+
            "<name>"+escape(name)+"</name>\n");
        description(out,summary);
    }

    static void close(Writer out) throws IOException {
        out.write("</Document>\n</kml>\n");
    }

    static void lineStyle(Writer out,String id,String color) throws IOException {
        out.write("<Style id=\""+id+"\"><LineStyle><color>"+color+"</color><width>4</width></LineStyle></Style>\n");
    }

    static void line(Writer out,String name,String style,GoTo.Point from,GoTo.Point to,String details) throws IOException {
        out.write("<Placemark>\n<name>"+escape(name)+"</name>\n");
        description(out,details);
        out.write("<styleUrl>#"+style+"</styleUrl>\n<LineString><tessellate>1</tessellate><coordinates>");
        out.write(String.format(Locale.ROOT,"%.9f,%.9f %.9f,%.9f",from.lon(),from.lat(),to.lon(),to.lat()));
        out.write("</coordinates></LineString>\n</Placemark>\n");
    }

    static void data(Writer out,String key,Object value) throws IOException {
        if(value!=null) out.write("<Data name=\""+key+"\"><value>"+escape(value.toString())+"</value></Data>\n");
    }

    static void placemark(Writer out,String name,String style,GoTo.Point point,String details) throws IOException {
        String coordinates=String.format(Locale.ROOT,"%.9f,%.9f",point.lon(),point.lat());
        String position=String.format(Locale.ROOT,"Latitude %.9f · Longitude %.9f",point.lat(),point.lon());
        if(Double.isFinite(point.elevation())) {
            String elevation=String.format(Locale.ROOT,"%.3f",point.elevation());
            coordinates+=","+elevation;
            position+=" · Elevation "+elevation+" m";
        }
        out.write("<Placemark>\n<name>"+escape(name)+"</name>\n");
        description(out,details+"\n"+position);
        out.write("<styleUrl>#"+style+"</styleUrl>\n"+
            "<Point><coordinates>"+coordinates+"</coordinates></Point>\n</Placemark>\n");
    }

    static void style(Writer out,String id,String color) throws IOException {
        out.write("<Style id=\""+id+"\"><IconStyle><color>"+color+"</color></IconStyle>"+
            "<LabelStyle><color>"+color+"</color></LabelStyle></Style>\n");
    }

    static void description(Writer out,String value) throws IOException {
        out.write("<description><![CDATA[");
        out.write(escape(value).replace("\r\n","\n").replace('\r','\n').replace("\n","<br/>"));
        out.write("]]></description>\n");
    }

    static String escape(String value) {
        StringBuilder safe=new StringBuilder(value.length());
        value.codePoints().forEach(ch->{
            switch(ch) {
                case '&' -> safe.append("&amp;");
                case '<' -> safe.append("&lt;");
                case '>' -> safe.append("&gt;");
                default -> {
                    if(ch==9||ch==10||ch==13||ch>=0x20&&ch<=0xd7ff||
                        ch>=0xe000&&ch<=0xfffd||ch>=0x10000) safe.appendCodePoint(ch);
                }
            }
        });
        return safe.toString();
    }

}
