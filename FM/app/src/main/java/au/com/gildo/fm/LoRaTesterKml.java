package au.com.gildo.fm;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DefaultHandler2;

/** Reads one surfer position per LoRa Tester GPS placemark, ignoring the connecting lines. */
final class LoRaTesterKml extends DefaultHandler2 {
    private final List<GoTo.Point> fixes = new ArrayList<>();
    private final StringBuilder value = new StringBuilder();
    private boolean placemark, point, classificationData;
    private String field, classification, coordinates;

    static List<GoTo.Point> read(InputStream input) throws IOException {
        LoRaTesterKml handler = new LoRaTesterKml();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            reader.setContentHandler(handler);
            reader.setEntityResolver(handler);
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
            reader.parse(new InputSource(input));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Could not read LoRa Tester KML: " + e.getMessage(), e);
        }
        if (handler.fixes.isEmpty()) throw new IOException("No LoRa GPS fixes found in this KML");
        return List.copyOf(handler.fixes);
    }

    @Override public void startDTD(String name, String publicId, String systemId) throws SAXException {
        throw new SAXException("A KML document cannot contain a DOCTYPE");
    }

    @Override public void startElement(String ns, String name, String qName, Attributes attributes) {
        if (name.equals("Placemark")) {
            placemark = true;
            classification = coordinates = null;
        } else if (placemark) {
            if (name.equals("Point")) point = true;
            if (name.equals("Data")) classificationData = "classification".equals(attributes.getValue("name"));
            if ((point && name.equals("coordinates")) || (classificationData && name.equals("value"))) {
                field = name;
                value.setLength(0);
            }
        }
    }

    @Override public void characters(char[] text, int offset, int length) throws SAXException {
        if (field == null) return;
        if (length > 256 - value.length()) throw new SAXException("Invalid GPS field");
        value.append(text, offset, length);
    }

    @Override public void endElement(String ns, String name, String qName) throws SAXException {
        if (!placemark) return;
        if (name.equals(field)) {
            if (field.equals("coordinates")) coordinates = value.toString().trim();
            else classification = value.toString().trim();
            field = null;
        }
        if (name.equals("Data")) classificationData = false;
        if (name.equals("Point")) point = false;
        if (name.equals("Placemark")) {
            if ("gps".equals(classification)) fixes.add(readPoint());
            placemark = false;
        }
    }

    private GoTo.Point readPoint() throws SAXException {
        if (coordinates != null) {
            String[] parts = coordinates.split(",", -1);
            if (parts.length == 2 || parts.length == 3) {
                try {
                    double lon = Double.parseDouble(parts[0]), lat = Double.parseDouble(parts[1]);
                    if (Geo.valid(lat, lon)) return new GoTo.Point(lat, lon, Double.NaN);
                } catch (NumberFormatException ignored) { }
            }
        }
        throw new SAXException("Invalid point at GPS fix " + (fixes.size() + 1));
    }
}
