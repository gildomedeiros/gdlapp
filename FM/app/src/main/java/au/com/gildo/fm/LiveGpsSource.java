package au.com.gildo.fm;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Queue;

/** Prepares Live Tracking filter input and preserves the original receiver fix. */
final class LiveGpsSource {
    enum Direction { CLOCKWISE, COUNTERCLOCKWISE }

    record Change(long pressedAt, long pressNumber, double stepM, double offsetM) {}

    record Fix(LoRaTelemetry packet, long receivedAt, GoTo.Point raw, GoTo.Point target,
               double offsetM, long offsetPressNumber, boolean simulated) {}

    private final long startedAt;
    private final Queue<Change> changes = new ArrayDeque<>();
    private double offsetM;
    private long pressNumber;

    LiveGpsSource(long startedAt) { this.startedAt = startedAt; }

    synchronized double offsetM() { return offsetM; }

    synchronized void advance(long pressedAt, double stepM) {
        offsetM += stepM;
        changes.add(new Change(pressedAt, ++pressNumber, stepM, offsetM));
    }

    static String formatMetres(double metres) {
        return BigDecimal.valueOf(metres).stripTrailingZeros().toPlainString();
    }

    static String describeOffset(double metres) {
        if (metres == 0) return "0 m";
        return formatMetres(Math.abs(metres)) + (metres > 0 ? " m clockwise" : " m counterclockwise");
    }

    synchronized Change nextChange() { return changes.poll(); }

    Fix toFilterInput(LoRaTelemetry packet, long receivedAt, GoTo.Point mount, double elevation) {
        return toFilterInput(packet, receivedAt, mount, elevation, false);
    }

    synchronized Fix toFilterInput(LoRaTelemetry packet, long receivedAt, GoTo.Point mount, double elevation,
                                   boolean simulated) {
        boolean useOffset = !simulated && receivedAt >= startedAt;
        double offset = useOffset ? offsetM : 0;
        GoTo.Point raw = new GoTo.Point(packet.lat, packet.lon, elevation);
        GoTo.Point target = offset == 0 ? raw : rotateAroundMount(mount, raw, offset);
        return new Fix(packet, receivedAt, raw, target, offset, useOffset ? pressNumber : 0, simulated);
    }

    private static GoTo.Point rotateAroundMount(GoTo.Point mount, GoTo.Point raw, double offset) {
        double rangeM = Geo.distance(mount.lat(), mount.lon(), raw.lat(), raw.lon());
        if (rangeM < .1) return null;

        double bearing = Geo.bearing(mount.lat(), mount.lon(), raw.lat(), raw.lon());
        double bearingOffset = Math.toDegrees((offset % (2 * Math.PI * rangeM)) / rangeM);
        GoTo.Point position = Geo.destination(mount, Geo.wrap(bearing + bearingOffset), rangeM);
        return new GoTo.Point(position.lat(), position.lon(), raw.elevation());
    }
}

