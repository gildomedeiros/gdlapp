package au.com.gildo.fm;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static au.com.gildo.fm.Kml.*;

final class LiveTrackingKml {
    record CameraHeading(double bearingDeg, int count, long sampledAt) {}
    record Raw(LoRaTelemetry.RawFix fix, long receivedAt, String rejection, boolean simulated) {
        Raw(LoRaTelemetry.RawFix fix, long receivedAt, String rejection) {
            this(fix, receivedAt, rejection, false);
        }
        GoTo.Point point() { return new GoTo.Point(fix.lat(), fix.lon(), Double.NaN); }
    }

    CameraHeading cameraHeading;
    final List<Raw> rawFixes = new ArrayList<>();
    final List<LiveGpsFilter.Snapshot> publishedFixes = new ArrayList<>();
    final List<LiveGpsSource.Fix> fixes = new ArrayList<>();
    final List<LiveGpsSource.Change> changes = new ArrayList<>();

    void write(Writer out, String attemptId, String summary, Instant pressedAt, long press,
               GoTo.Point mount, GoTo.Point landmark) throws IOException {
        double finalOffset = changes.isEmpty() ? 0 : changes.get(changes.size() - 1).offsetM();
        LiveGpsSource.Fix lastFix = lastAppliedFix();
        open(out, "FM Live Tracking " + attemptId, summary + "\nRaw GPS fixes · " + rawFixes.size()
            + "\nPublished GPS fixes · " + publishedFixes.size()
            + "\nFinal requested offset · " + LiveGpsSource.describeOffset(finalOffset));
        style(out, "mount", "ffffaa00");
        style(out, "landmark", "ff00d7ff");
        style(out, "offset", "ffd24aff");
        style(out, "raw", "fff8a03b");
        style(out, "target", "ff35ab21");
        lineStyle(out, "raw-track", "fff8a03b");
        lineStyle(out, "target-track", "ff35ab21");
        lineStyle(out, "camera-heading", "ff2a6cff");

        out.write("<ExtendedData>\n");
        data(out, "attempt_id", attemptId);
        data(out, "started_utc", pressedAt);
        data(out, "raw_gps_fix_count", rawFixes.size());
        data(out, "published_gps_fix_count", publishedFixes.size());
        data(out, "offset_presses", changes.size());
        data(out, "last_requested_offset_m", LiveGpsSource.formatMetres(finalOffset));
        data(out, "last_applied_offset_m", lastFix == null ? null : LiveGpsSource.formatMetres(lastFix.offsetM()));
        if (cameraHeading != null) {
            data(out, "camera_heading_deg_true", cameraHeading.bearingDeg());
            data(out, "camera_pan_count", cameraHeading.count());
            data(out, "camera_heading_ms_after_start", cameraHeading.sampledAt() - press);
        }
        out.write("</ExtendedData>\n");

        if (mount != null) placemark(out, "Mount position", "mount", mount, "Camera pivot");
        if (landmark != null) placemark(out, "Calibration landmark", "landmark", landmark, "Saved pointing reference");
        writeRawFixes(out, pressedAt, press);
        writePublishedFixes(out, pressedAt, press);
        writeOffsets(out, press, mount);
        if (mount != null && cameraHeading != null) writeCameraHeading(out, mount, press);
        close(out);
    }

    private LiveGpsSource.Fix lastAppliedFix() {
        for (int i = fixes.size()-1; i >= 0; i--) {
            LiveGpsSource.Fix fix = fixes.get(i);
            if (!fix.simulated() && fix.target() != null) return fix;
        }
        return null;
    }

    private void writeCameraHeading(Writer out, GoTo.Point mount, long press) throws IOException {
        GoTo.Point target = publishedFixes.isEmpty() ? null :
            publishedFixes.get(publishedFixes.size() - 1).fix().target();
        double lengthM = target == null ? 0 :
            Geo.distance(mount.lat(), mount.lon(), target.lat(), target.lon());
        if (lengthM < .1) lengthM = 25;
        GoTo.Point aim = Geo.destination(mount, cameraHeading.bearingDeg(), lengthM);
        line(out, "Camera direction (last encoder reading)", "camera-heading", mount, aim,
            String.format(Locale.ROOT, "Heading %.2f° true · Encoder %d\nSampled %d ms after Live Tracking started",
                cameraHeading.bearingDeg(), cameraHeading.count(), cameraHeading.sampledAt() - press));
    }

    private void writeRawFixes(Writer out, Instant pressedAt, long press) throws IOException {
        out.write("<Folder><name>Raw GPS fixes</name>\n");
        writeTrack(out, "Raw GPS track", "raw", rawFixes, Raw::point);
        for (Raw raw : rawFixes) {
            LoRaTelemetry.RawFix fix = raw.fix();
            String name = fix.sequence() == null ? "● Raw GPS" : "● Raw GPS #" + fix.sequence();
            String details = (raw.simulated() ? "Simulator fix · " : "Receiver fix · ") + pressedAt.plusMillis(raw.receivedAt() - press)
                + (fix.senderMs() == null ? "" : "\nReceiver time · " + fix.senderMs() + " ms")
                + (raw.rejection() == null ? "" : "\nReceiver rejection · " + raw.rejection());
            placemark(out, name, "raw", raw.point(), details);
        }
        out.write("</Folder>\n");
    }

    private void writePublishedFixes(Writer out, Instant pressedAt, long press) throws IOException {
        out.write("<Folder><name>Published GPS fixes</name>\n");
        writeTrack(out, "Tracking target path", "target", publishedFixes, item -> item.fix().target());
        for (LiveGpsFilter.Snapshot item : publishedFixes) {
            String details = (item.fix().simulated() ? "Simulator GPS #" : "Source GPS #") + item.fix().packet().sequence
                + "\nGP direction · " + item.direction().name()
                + "\nGP state · " + item.state().name()
                + "\nPublished · " + pressedAt.plusMillis(item.publishedAt() - press)
                + "\nPosition revision · " + item.revision();
            placemark(out, "◆ Published revision " + item.revision(), "target", item.fix().target(), details);
        }
        out.write("</Folder>\n");
    }

    private static <T> void writeTrack(Writer out, String name, String styleId, List<T> points,
                                       Function<T,GoTo.Point> pick) throws IOException {
        if (points.size() < 2) return;
        out.write("<Placemark><name>" + escape(name) + "</name><styleUrl>#" + styleId + "-track</styleUrl>\n"
            + "<LineString><tessellate>1</tessellate><coordinates>\n");
        for (T item : points) {
            GoTo.Point point = pick.apply(item);
            out.write(String.format(Locale.ROOT, "%.9f,%.9f\n", point.lon(), point.lat()));
        }
        out.write("</coordinates></LineString></Placemark>\n");
    }

    private void writeOffsets(Writer out, long press, GoTo.Point mount) throws IOException {
        if (changes.isEmpty()) return;
        out.write("<Folder><name>Simulation button presses</name>\n");
        int next = 0;
        for (LiveGpsSource.Change change : changes) {
            while (next < fixes.size()) {
                LiveGpsSource.Fix candidate = fixes.get(next);
                if (candidate.offsetPressNumber() > change.pressNumber()
                    || (candidate.offsetPressNumber() == change.pressNumber() && candidate.target() != null)) break;
                next++;
            }
            LiveGpsSource.Fix first = next < fixes.size() ? fixes.get(next) : null;
            boolean applied = first != null && first.offsetPressNumber() == change.pressNumber() && first.target() != null;
            String details = "Press " + change.pressNumber() + " · " + LiveGpsSource.describeOffset(change.stepM())
                + "\n" + (change.pressedAt() - press) + " ms after Live Tracking started"
                + (applied ? "\nFirst applied to GPS #" + first.packet().sequence
                    + "\nRaw position · " + first.raw().lat() + ", " + first.raw().lon()
                    : "\nNo usable fix used this offset");
            String name = LiveGpsSource.describeOffset(change.offsetM());
            if (applied) placemark(out, name, "offset", first.target(), details);
            else if (mount != null) placemark(out, name, "offset", mount, details);
        }
        out.write("</Folder>\n");
    }
}

