package au.com.gildo.fm;

import java.io.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Builds raw GPS fixes for saved sequences and continuous surfer simulation. */
final class SurferModes {
    static final int DEFAULT_RIDE_SECONDS = 5;
    static final String RIDE_SECONDS_PREF = "surferRideSeconds";
    static final String DEFAULT_CONTINUOUS_SECONDS = "3";
    private static final int MAX_RIDE_SECONDS = 3600;
    private static final int MAX_FIXES = 10_000;
    private static final String HEADER = "latitude,longitude";

    enum Mode {
        SITTING(1,"Sitting or waiting"), SLOW(2,"Moving slowly / slow paddling"),
        PADDLE_SAME(3,"Paddle in the ride direction"), PADDLE_TURN(4,"Paddle toward the peak, then turn"),
        TAKEOFF(5,"Takeoff"), RIDING(6,"Wave riding");

        final int number;
        final String label;
        Mode(int number, String label) { this.number = number; this.label = label; }

        String durationPreference() { return "Simulator mode " + number + " duration (s)"; }
    }

    // Relative positions from the 6 November VT examples. The recorded patterns have two samples per second.
    private static final double[] SITTING_M = {.982,.982,.982,.982,0,0,0,.982,.982,.982};
    private static final double[][] SLOW_EN_M = {{0,0},{-.982,-1.112},{-.982,-1.112},{-.982,-1.112},
        {-.982,-2.224},{-1.964,-2.224},{-1.964,-2.224},{-1.964,-3.336},{-1.964,-3.336},{-1.964,-3.336}};
    private static final double[] RIDE_M = {0,1.964,3.929,5.893,9.822,11.787,13.751,15.716,18.662,20.627,22.591};

    static final class Continuous {
        final Mode mode;
        final double bearing;
        private Mode segment;
        private List<GoTo.Point> fixes;
        private int next = 1;

        Continuous(Mode mode, GoTo.Point start, double bearing) {
            this.mode = mode;
            this.bearing = bearing;
            segment = mode;
            fixes = generate(start, bearing, List.of(segment), DEFAULT_RIDE_SECONDS, 2);
        }

        GoTo.Point advance() {
            if (next == fixes.size()) {
                next = 1;
                if (mode != Mode.SITTING) {
                    segment = switch (segment) {
                        case PADDLE_TURN -> Mode.PADDLE_SAME;
                        case TAKEOFF -> Mode.RIDING;
                        default -> segment;
                    };
                    fixes = generate(fixes.get(fixes.size() - 1), bearing, List.of(segment), DEFAULT_RIDE_SECONDS, 2);
                }
            }
            return fixes.get(next++);
        }
    }

    static long continuousDurationMs(String seconds) {
        try {
            long milliseconds = new BigDecimal(seconds.trim().replace(',', '.')).movePointRight(3).longValueExact();
            if (milliseconds > 0) return milliseconds;
        } catch (NumberFormatException | ArithmeticException ignored) { }
        throw new IllegalArgumentException("Enter a positive duration in seconds, with up to three decimal places");
    }

    static List<Mode> parseSequence(String sequence) {
        if (sequence.trim().isEmpty()) throw new IllegalArgumentException("Enter a sequence such as 1,2,3,4,1");
        List<Mode> modes = new ArrayList<>();
        Mode[] available = Mode.values();
        for (String item : sequence.split(",", -1)) {
            String number = item.trim();
            Mode match = null;
            for (Mode mode : available) {
                if (number.equals(Integer.toString(mode.number))) {
                    match = mode;
                    break;
                }
            }
            if (match == null)
                throw new IllegalArgumentException("Enter a behaviour from 1 to 6 at position " + (modes.size() + 1));
            modes.add(match);
        }
        return List.copyOf(modes);
    }

    static List<GoTo.Point> generate(GoTo.Point landmark, double bearing, List<Mode> modes, int rideSeconds, int rateHz) {
        if (rateHz != 1 && rateHz != 2) throw new IllegalArgumentException("Select 1 or 2 raw fixes per second");
        if (modes.isEmpty()) throw new IllegalArgumentException("Enter at least one surfer behaviour");
        if (modes.contains(Mode.RIDING) && (rideSeconds < 1 || rideSeconds > MAX_RIDE_SECONDS))
            throw new IllegalArgumentException("Wave riding duration must be 1–3600 whole seconds");

        List<GoTo.Point> points = new ArrayList<>();
        points.add(landmark);
        for (Mode mode : modes) {
            int segmentStart = points.size();
            GoTo.Point start = points.get(segmentStart - 1);
            switch (mode) {
                case SITTING -> along(points, start, bearing, SITTING_M);
                case SLOW -> {
                    double rotation = bearing - Math.toDegrees(Math.atan2(-1.964, -3.336));
                    for (double[] offset : SLOW_EN_M) {
                        double heading = rotation + Math.toDegrees(Math.atan2(offset[0], offset[1]));
                        points.add(at(start, heading, Math.hypot(offset[0], offset[1])));
                    }
                }
                case PADDLE_SAME -> along(points, start, bearing, 0,.8,.8,1.8,2.9,2.9,4.4,5.9);
                case PADDLE_TURN -> along(points, start, bearing, -.8,-.8,-1.9,-3.2,-4.5,-4.5,-3.7,-2.6,-1.1,.6);
                case TAKEOFF -> along(points, start, bearing, 0,.5,.5,1.4,2.8,4.8,7.1,9.6);
                case RIDING -> {
                    int intervals = rideSeconds * rateHz;
                    for (int i = 1; i <= intervals; i++) {
                        double sample = (double)i * (RIDE_M.length - 1) / intervals;
                        int from = Math.min((int)sample, RIDE_M.length - 2);
                        double distance = RIDE_M[from] + (sample - from) * (RIDE_M[from + 1] - RIDE_M[from]);
                        points.add(at(start, bearing, distance * rideSeconds / DEFAULT_RIDE_SECONDS));
                    }
                }
            }
            if (mode != Mode.RIDING && rateHz == 1) {
                // Sample the half-second pattern at whole seconds before saving the new CSV.
                List<GoTo.Point> segment = points.subList(segmentStart, points.size());
                int kept = 0;
                for (int i = 1; i < segment.size(); i += 2) segment.set(kept++, segment.get(i));
                segment.subList(kept, segment.size()).clear();
            }
            if (points.size() > MAX_FIXES)
                throw new IllegalArgumentException("Surfer sequence exceeds " + MAX_FIXES + " GPS fixes");
        }
        return List.copyOf(points);
    }

    private static void along(List<GoTo.Point> points, GoTo.Point start, double bearing, double... metres) {
        for (double distance : metres) points.add(at(start, bearing, distance));
    }

    private static GoTo.Point at(GoTo.Point start, double bearing, double metres) {
        return metres == 0 ? start : Geo.destination(start, bearing, metres);
    }

    static String encode(List<GoTo.Point> points) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (GoTo.Point point : points) csv.append(point.lat()).append(',').append(point.lon()).append('\n');
        return csv.toString();
    }

    static List<GoTo.Point> decode(BufferedReader file) throws IOException {
        String header = file.readLine();
        if (header == null || !HEADER.equals(header.replace("\uFEFF", "").trim()))
            throw new IOException("Expected latitude,longitude on the first line");
        List<GoTo.Point> points = new ArrayList<>();
        int row = 1;
        for (String line; (line = file.readLine()) != null;) {
            row++;
            if (line.isBlank()) continue;
            if (points.size() == MAX_FIXES) throw new IOException("Too many GPS fixes (maximum " + MAX_FIXES + ")");
            String[] fields = line.split(",", -1);
            if (fields.length == 2) {
                try {
                    double lat = Double.parseDouble(fields[0]);
                    double lon = Double.parseDouble(fields[1]);
                    if (Geo.valid(lat, lon)) {
                        points.add(new GoTo.Point(lat, lon, Double.NaN));
                        continue;
                    }
                } catch (NumberFormatException ignored) { }
            }
            throw new IOException("Invalid GPS coordinates on line " + row);
        }
        if (points.isEmpty()) throw new IOException("No GPS fixes in the file");
        return List.copyOf(points);
    }
}
