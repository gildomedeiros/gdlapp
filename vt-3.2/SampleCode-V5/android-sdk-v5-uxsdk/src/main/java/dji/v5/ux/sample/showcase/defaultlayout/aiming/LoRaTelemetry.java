package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** TTGO wire format and sequence acceptance, independent of Android and flight control. */
public final class LoRaTelemetry {
    public final long sequence, senderMs, missed;
    public final double lat, lon, hdop, snr;
    public final int satellites, rssi;

    private LoRaTelemetry(String[] f) {
        sequence = unsigned(f[2]); senderMs = unsigned(f[3]);
        lat = number(f[4]); lon = number(f[5]); satellites = Integer.parseInt(f[6]);
        hdop = number(f[7]); rssi = Integer.parseInt(f[8]); snr = number(f[9]); missed = unsigned(f[10]);
        // CAM3 v2.5: Fixed rejection reasons identify the field without exposing raw coordinates.
        if (!YawAimingMath.coordinateValid(lat, lon)) throw new IllegalArgumentException("Invalid coordinates");
        if (satellites <= 0) throw new IllegalArgumentException("No satellites");
        if (hdop < 0) throw new IllegalArgumentException("Invalid HDOP");
        if (rssi < -32768 || rssi > 32767) throw new IllegalArgumentException("RSSI out of range");
        if (snr < -128 || snr > 127) throw new IllegalArgumentException("SNR out of range");
    }
    public static LoRaTelemetry parse(String line) {
        String[] fields = line.trim().split(",", -1);
        if (fields.length != 11 || !fields[0].equals("RX") || !fields[1].equals("GPS"))
            throw new IllegalArgumentException("Not a GPS packet");
        return new LoRaTelemetry(fields);
    }
    private static long unsigned(String value) {
        if (!value.matches("[0-9]+")) throw new IllegalArgumentException("Invalid counter");
        long n = Long.parseLong(value);
        if (n > 0xffffffffL) throw new IllegalArgumentException("Counter overflow");
        return n;
    }
    private static double number(String value) {
        double n = Double.parseDouble(value);
        if (!Double.isFinite(n)) throw new IllegalArgumentException("Non-finite field");
        return n;
    }
    public static final class Tracker {
        private LoRaTelemetry previous, candidate;
        private int restartCount;
        private long candidateAt;
        public long gaps, repeats, older, restarts;
        // CAM3 v2.5: Observe the accepted baseline for gap logs without changing acceptance rules.
        public long lastSequence() { return previous == null ? -1 : previous.sequence; }
        public boolean accept(LoRaTelemetry next, long now) {
            if (previous == null) { previous = next; return true; }
            long seqDelta = (next.sequence - previous.sequence) & 0xffffffffL;
            long timeDelta = (next.senderMs - previous.senderMs) & 0xffffffffL;
            if (seqDelta == 0) { repeats++; candidate = null; restartCount = 0; return false; }
            if (seqDelta < 0x80000000L && timeDelta > 0 && timeDelta < 0x80000000L) {
                gaps += seqDelta - 1; previous = next; candidate = null; restartCount = 0; return true;
            }
            older++;
            // A restart must present three consecutive increasing packets with both counters lower.
            // One delayed/duplicate packet cannot reset the accepted sequence or refresh the fix.
            if (next.sequence < previous.sequence && next.senderMs < previous.senderMs) {
                boolean follows = candidate != null && next.sequence == candidate.sequence + 1
                        && next.senderMs > candidate.senderMs && now >= candidateAt && now - candidateAt <= 3000;
                if (candidate != null && next.sequence == candidate.sequence) return false;
                restartCount = follows ? restartCount + 1 : 1;
                candidate = next; candidateAt = now;
                if (restartCount >= 3) {
                    restarts++; previous = next; candidate = null; restartCount = 0; return true;
                }
            } else { candidate = null; restartCount = 0; }
            return false;
        }
    }
}
