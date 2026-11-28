package au.com.gildo.fm;

import java.util.Locale;

/** Immutable display data. Distances and width refer to the horizontal plane at the surfer. */
record LiveMapFrame(GoTo.Point mount,GoTo.Point landmark,GoTo.Point surfer,boolean gpsFresh,
                    Double heading,boolean headingFresh,String pan,Double zoom,boolean zoomFresh,double wideFactor) {
    double range() { return distance(surfer); }
    double distance(GoTo.Point p) { return mount==null || p==null ? Double.NaN : Geo.distance(mount.lat(),mount.lon(),p.lat(),p.lon()); }
    double bearing(GoTo.Point p) { return distance(p)>=.1 ? Geo.bearing(mount.lat(),mount.lon(),p.lat(),p.lon()) : 0; }
    double width() { return zoom==null ? Double.NaN : range()*wideFactor/zoom; }
    boolean coverageKnown() { return range()>=.1 && gpsFresh && headingFresh && heading!=null && zoomFresh && Double.isFinite(width()); }
    boolean inFrame() { return coverageKnown() && Math.abs(Geo.delta(bearing(surfer),heading))<=Math.toDegrees(Math.atan2(width()/2,range())); }
    static String dashboardStatus(LiveMapFrame frame) {
        String appliedZoom=frame==null?"—":frame.zoomText();
        double range=frame==null?Double.NaN:frame.range();
        String rangeAge=Double.isFinite(range) && !frame.gpsFresh?" (stale)":"";
        return "Applied zoom "+appliedZoom+" · Surfer-to-mount "+metres(range)+rangeAge;
    }
    String status() {
        String coverage=coverageKnown()?(inFrame()?"Inside estimated frame":"Potentially outside frame"):"Frame coverage uncertain";
        return String.format(Locale.US,"%s · Pan: %s%nSurfer %s · Range %s · Zoom %s%nHeading %s · Frame width %s",
            coverage,pan,surfer==null?"awaiting GPS":gpsFresh?"GPS fresh":"GPS stale",metres(range()),
            zoomText(),
            heading==null?"—":String.format(Locale.US,"%.1f°%s",heading,headingFresh?"":" (last reading)"),metres(width()));
    }
    private String zoomText() { return zoom==null?"—":String.format(Locale.US,"%.1f×%s",zoom,zoomFresh?"":" (stale)"); }
    private static String metres(double value) { return Double.isFinite(value)?String.format(Locale.US,"%.1f m",value):"—"; }
}
