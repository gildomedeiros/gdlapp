package au.com.gildo.fm;

import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import static org.junit.Assert.*;

public class LiveGpsSourceTest {
    private static final GoTo.Point MOUNT = new GoTo.Point(-28.079233, 153.401026, 4);
    private static final double RANGE = 25;

    @Test public void offsetIncreasesPerPressAndAppliesToEveryNewFixAtTheSameRange() {
        var producer = new LiveGpsSource(1000);
        var first = gps(1, 358);
        var zero = producer.toFilterInput(first, 1100, MOUNT, 2);
        assertSame(zero.raw(), zero.target());

        producer.advance(1150, 2);
        var two = producer.toFilterInput(gps(2, 358), 1200, MOUNT, 2);
        var next = producer.toFilterInput(gps(3, 12), 1250, MOUNT, 2);
        assertEquals(2, two.offsetM(), 0);
        assertEquals(2, arcOffset(two), .00001);
        assertEquals(2, arcOffset(next), .00001);
        assertEquals(2, two.target().elevation(), 0);
        assertEquals(first.lat, two.raw().lat(), 0);
        assertEquals(first.lon, two.raw().lon(), 0);

        producer.advance(1300, 2);
        var four = producer.toFilterInput(gps(4, 359), 1350, MOUNT, 2);
        assertEquals(4, four.offsetM(), 0);
        assertEquals(4, arcOffset(four), .00001);
        assertEquals(new LiveGpsSource.Change(1150, 1, 2, 2), producer.nextChange());
        assertEquals(new LiveGpsSource.Change(1300, 2, 2, 4), producer.nextChange());
        assertNull(producer.nextChange());

        var nextSession = new LiveGpsSource(1400);
        assertEquals(0, nextSession.offsetM(), 0);
        var reset = nextSession.toFilterInput(gps(5, 359), 1500, MOUNT, 2);
        assertSame(reset.raw(), reset.target());
    }

    @Test public void halfMetrePressesAccumulateWithTwoMetrePressesAndCancelExactly() {
        var producer = new LiveGpsSource(1000);
        producer.advance(1100, -0.5);
        var counterclockwise = producer.toFilterInput(gps(1, 0), 1110, MOUNT, 2);
        assertEquals(-0.5, counterclockwise.offsetM(), 0);
        assertEquals(-0.5, arcOffset(counterclockwise), .00001);
        assertEquals("0.5 m counterclockwise", LiveGpsSource.describeOffset(counterclockwise.offsetM()));
        assertEquals(new LiveGpsSource.Change(1100, 1, -0.5, -0.5), producer.nextChange());

        producer.advance(1200, 2);
        var clockwise = producer.toFilterInput(gps(2, 359), 1210, MOUNT, 2);
        assertEquals(1.5, clockwise.offsetM(), 0);
        assertEquals(1.5, arcOffset(clockwise), .00001);
        assertEquals("1.5 m clockwise", LiveGpsSource.describeOffset(clockwise.offsetM()));
        assertEquals(new LiveGpsSource.Change(1200, 2, 2, 1.5), producer.nextChange());

        producer.advance(1300, -2);
        producer.advance(1400, 0.5);
        var zero = producer.toFilterInput(gps(3, 2), 1410, MOUNT, 2);
        assertEquals(0, zero.offsetM(), 0);
        assertSame(zero.raw(), zero.target());
        assertEquals(new LiveGpsSource.Change(1300, 3, -2, -0.5), producer.nextChange());
        assertEquals(new LiveGpsSource.Change(1400, 4, 0.5, 0), producer.nextChange());
    }

    @Test public void aCachedFixKeepsItsOriginalPositionEvenIfTheButtonWasPressed() {
        var producer = new LiveGpsSource(1000);
        producer.advance(1010, 2);
        var cached = producer.toFilterInput(gps(1, 0), 980, MOUNT, 2);
        assertEquals(0, cached.offsetM(), 0);
        assertSame(cached.raw(), cached.target());
        var newFix = producer.toFilterInput(gps(2, 0), 1020, MOUNT, 2);
        assertEquals(2, newFix.offsetM(), 0);
        assertEquals(2, arcOffset(newFix), .00001);
    }

    @Test public void kmlContainsBothTracksAndEachPressWithItsApplicationStatus() throws Exception {
        var producer = new LiveGpsSource(1000);
        var report = new LiveTrackingKml();
        var original = producer.toFilterInput(gps(1, 10), 1050, MOUNT, 2);
        recordRaw(report, original);
        recordPublished(report, original, 1);
        producer.advance(1100, 0.5);
        report.changes.add(producer.nextChange());
        var shifted = producer.toFilterInput(gps(2, 11), 1200, MOUNT, 2);
        recordRaw(report, shifted);
        recordPublished(report, shifted, 2);
        var rejected = LoRaTelemetry.rawFix("RX,GPS,9,1230,-28.0792,153.4011,0,1.15,-85,7.0,0");
        assertNotNull(rejected);
        report.rawFixes.add(new LiveTrackingKml.Raw(rejected, 1230, "No satellites"));
        producer.advance(1300, -2);
        report.changes.add(producer.nextChange());

        StringWriter out = new StringWriter();
        report.write(out, "example-01", "Stop accepted", Instant.parse("2026-11-22T10:00:00Z"), 1000,
            MOUNT, new GoTo.Point(-28.079118, 153.401297, 3));
        String xml = out.toString();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        Element raw = placemark(document, "Raw GPS track");
        Element target = placemark(document, "Tracking target path");
        assertTrue(raw.getTextContent().contains(coordinate(shifted.raw())));
        assertTrue(target.getTextContent().contains(coordinate(shifted.target())));
        assertTrue(placemark(document, "● Raw GPS #9").getTextContent().contains("No satellites"));
        assertTrue(placemark(document, "◆ Published revision 2").getTextContent().contains("Source GPS #2"));
        assertEquals(2, target.getElementsByTagNameNS("*", "coordinates").item(0).getTextContent().trim().split("\\s+").length);
        assertTrue(placemark(document, "0.5 m clockwise").getTextContent().contains("First applied to GPS #2"));
        assertTrue(placemark(document, "1.5 m counterclockwise").getTextContent().contains("No usable fix used this offset"));
        assertNotNull(placemark(document, "Mount position"));
        assertNotNull(placemark(document, "Calibration landmark"));
        assertTrue(xml.contains("<Data name=\"last_applied_offset_m\"><value>0.5</value>"));
        assertTrue(xml.contains("<Data name=\"last_requested_offset_m\"><value>-1.5</value>"));
    }

    @Test public void counterclockwisePressesCanCancelAndPassThroughZero() {
        var producer = new LiveGpsSource(1000);
        producer.advance(1100, 2);
        producer.advance(1200, -2);
        var zero = producer.toFilterInput(gps(1, 2), 1250, MOUNT, 2);
        assertEquals(2, zero.offsetPressNumber());
        assertSame(zero.raw(), zero.target());

        producer.advance(1300, -2);
        var negative = producer.toFilterInput(gps(2, 2), 1350, MOUNT, 2);
        assertEquals(-2, negative.offsetM(), 0);
        assertEquals(-2, arcOffset(negative), .00001);
        assertEquals("2 m counterclockwise", LiveGpsSource.describeOffset(negative.offsetM()));
    }

    @Test public void kmlTracksRepeatedOffsetsByPressAndDrawsTheMeasuredCameraHeading() throws Exception {
        var producer = new LiveGpsSource(1000);
        var report = new LiveTrackingKml();
        producer.advance(1100, -2);
        report.changes.add(producer.nextChange());
        producer.advance(1150, 2);
        report.changes.add(producer.nextChange());
        producer.advance(1180, -2);
        report.changes.add(producer.nextChange());
        var fix = producer.toFilterInput(gps(7, 35), 1200, MOUNT, 2);
        recordRaw(report, fix);
        recordPublished(report, fix, 1);
        report.cameraHeading = new LiveTrackingKml.CameraHeading(121.5, 8050000, 1240);

        StringWriter out = new StringWriter();
        report.write(out, "example-02", "Stop accepted", Instant.parse("2026-11-22T10:00:00Z"), 1000, MOUNT, null);
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(out.toString())));
        var elements = doc.getElementsByTagNameNS("*", "Placemark");
        int counterclockwisePresses = 0;
        for (int i = 0; i < elements.getLength(); i++) {
            Element mark = (Element)elements.item(i);
            if ("2 m counterclockwise".equals(mark.getElementsByTagNameNS("*", "name").item(0).getTextContent())) {
                if (counterclockwisePresses == 0) assertTrue(mark.getTextContent().contains("No usable fix used this offset"));
                else assertTrue(mark.getTextContent().contains("First applied to GPS #7"));
                counterclockwisePresses++;
            }
        }
        assertEquals(2, counterclockwisePresses);
        Element heading = placemark(doc, "Camera direction (last encoder reading)");
        assertEquals(1, heading.getElementsByTagNameNS("*", "LineString").getLength());
        String[] positions = heading.getElementsByTagNameNS("*", "coordinates").item(0).getTextContent().split(" ");
        assertEquals(coordinate(MOUNT), positions[0]);
        String[] end = positions[1].split(",");
        double endLat = Double.parseDouble(end[1]), endLon = Double.parseDouble(end[0]);
        assertEquals(121.5, Geo.bearing(MOUNT.lat(), MOUNT.lon(), endLat, endLon), .001);
        assertEquals(RANGE, Geo.distance(MOUNT.lat(), MOUNT.lon(), endLat, endLon), .001);
        assertTrue(out.toString().contains("<Data name=\"camera_heading_ms_after_start\"><value>240</value>"));
    }

    private static void recordRaw(LiveTrackingKml report, LiveGpsSource.Fix fix) {
        var packet = fix.packet();
        report.fixes.add(fix);
        report.rawFixes.add(new LiveTrackingKml.Raw(
            new LoRaTelemetry.RawFix(packet.sequence, packet.senderMs, packet.lat, packet.lon), fix.receivedAt(), null));
    }

    private static void recordPublished(LiveTrackingKml report, LiveGpsSource.Fix fix, long revision) {
        report.publishedFixes.add(new LiveGpsFilter.Snapshot(fix, LiveGpsSource.Direction.CLOCKWISE,
            LiveGpsFilter.State.MOVING, revision, fix.receivedAt(), fix.receivedAt(), java.util.List.of(), fix.target(), 0));
    }

    private static double arcOffset(LiveGpsSource.Fix fix) {
        GoTo.Point raw = fix.raw(), target = fix.target();
        double targetRange = Geo.distance(MOUNT.lat(), MOUNT.lon(), target.lat(), target.lon());
        assertEquals(RANGE, targetRange, .00001);
        double rawBearing = Geo.bearing(MOUNT.lat(), MOUNT.lon(), raw.lat(), raw.lon());
        double targetBearing = Geo.bearing(MOUNT.lat(), MOUNT.lon(), target.lat(), target.lon());
        return Math.toRadians(Geo.delta(targetBearing, rawBearing)) * targetRange;
    }

    private static LoRaTelemetry gps(int packet, double bearing) {
        var point = Geo.destination(MOUNT, bearing, RANGE);
        return LoRaTelemetry.parse(String.format(Locale.ROOT,
            "RX,GPS,%d,%d,%.12f,%.12f,8,1.15,-85,7.0,0", packet, packet, point.lat(), point.lon()));
    }

    private static String coordinate(GoTo.Point point) {
        return String.format(Locale.ROOT, "%.9f,%.9f", point.lon(), point.lat());
    }

    private static Element placemark(Document document, String name) {
        var elements = document.getElementsByTagNameNS("*", "Placemark");
        for (int i=0; i<elements.getLength(); i++) {
            Element point = (Element)elements.item(i);
            if (name.equals(point.getElementsByTagNameNS("*", "name").item(0).getTextContent())) return point;
        }
        throw new AssertionError("Missing placemark " + name);
    }
}

