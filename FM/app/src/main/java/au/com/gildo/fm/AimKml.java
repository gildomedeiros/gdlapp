package au.com.gildo.fm;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;
import static au.com.gildo.fm.Kml.*;

final class AimKml {
    static void write(Writer out,String attemptId,String summary,GoTo.Point mount,GoTo.Point landmark,
                      LoRaTelemetry fix,AimAtSurfer.Attempt aim) throws IOException {
        Double start=heading(aim,aim.baseline);
        Double crossing=aim.crossing==null?null:heading(aim,aim.lastSampleCount);
        Double end=heading(aim,aim.finalCount);
        Integer crossingCount=aim.crossing==null?null:aim.lastSampleCount;
        Double bearing=aim.target==null?null:aim.target.angleDeg();
        Double range=aim.target==null?null:aim.target.rangeM();
        String headings="\nHeadings true (°) · Start "+degrees(start)+" · Target "+degrees(bearing)
            +" · Crossing "+degrees(crossing)+" · Final "+degrees(end);
        open(out,"FM Aim at Surfer HS "+attemptId,summary+headings);
        style(out,"mount","ffffaa00");
        style(out,"landmark","ff00d7ff");
        style(out,"surfer","ff35ab21");
        lineStyle(out,"initial-heading","ffeb7a29");
        lineStyle(out,"target-heading","ff35ab21");
        lineStyle(out,"final-heading","ff1e73f8");
        lineStyle(out,"final-gap","ffb840ac");

        out.write("<ExtendedData>\n");
        data(out,"attempt_id",attemptId);
        data(out,"outcome",aim.outcome);
        data(out,"issue",aim.issue);
        data(out,"gps_packet",aim.packet);
        data(out,"gps_age_at_selection_ms",aim.ageAtSelection);
        data(out,"target_range_m",range);
        data(out,"start_heading_deg_true",start);
        data(out,"target_bearing_deg_true",bearing);
        data(out,"crossing_heading_deg_true",crossing);
        data(out,"final_heading_deg_true",end);
        data(out,"reference_bearing_deg_true",aim.reference==null?null:aim.reference.bearing());
        data(out,"reference_pan_count",aim.reference==null?null:aim.reference.pan());
        data(out,"pan_counts_per_revolution",aim.panCpr);
        data(out,"start_pan_count",aim.baseline);
        data(out,"target_pan_count",aim.target==null?null:aim.target.count());
        data(out,"planned_stop_pan_count",aim.goal);
        data(out,"crossing_pan_count",crossingCount);
        data(out,"final_pan_count",aim.finalCount);
        data(out,"final_gap_m",aim.finalGapM);
        data(out,"final_error_counts",aim.errorCounts);
        data(out,"coast_counts",aim.coastCounts);
        data(out,"commanded_hs_rate_x",aim.sidereal);
        data(out,"step_period",aim.motorPeriod);
        data(out,"button_to_start_reply_ms",AimAtSurfer.Attempt.since(aim.firstStartReply(),aim.press));
        data(out,"button_to_pan_start_reply_ms",AimAtSurfer.Attempt.since(aim.startAck,aim.press));
        data(out,"button_to_full_stop_ms",AimAtSurfer.Attempt.since(aim.stopped,aim.press));
        AimAtSurfer.Attempt tilt=aim.tilt;
        if(tilt!=null) {
            data(out,"tilt_outcome",tilt.outcome);
            data(out,"tilt_issue",tilt.issue);
            data(out,"tilt_target_elevation_m",tilt.targetElevationM);
            if(tilt.target!=null) {
                data(out,"tilt_target_angle_deg",tilt.target.angleDeg());
                data(out,"tilt_target_count",tilt.target.count());
            }
            data(out,"tilt_baseline_count",tilt.baseline);
            data(out,"tilt_goto_mode",tilt.gotoMode);
            data(out,"tilt_direction",tilt.direction);
            data(out,"button_to_tilt_start_send_ms",AimAtSurfer.Attempt.since(tilt.startSent,aim.press));
            data(out,"button_to_tilt_start_reply_ms",AimAtSurfer.Attempt.since(tilt.startAck,aim.press));
            data(out,"pan_stop_reply_to_tilt_start_reply_ms",AimAtSurfer.Attempt.since(tilt.startAck,aim.stopAck));
            if(tilt.startSent!=null) data(out,"tilt_final_position","unconfirmed");
        }
        out.write("</ExtendedData>\n");

        if(mount!=null) {
            String details="Mount and camera pivot"+headings;
            if(aim.reference!=null) details+="\nCalibration reference · "+degrees(aim.reference.bearing())
                +"° true at "+aim.reference.pan()+" counts";
            placemark(out,"Mount","mount",mount,details);
        }
        if(landmark!=null) placemark(out,"Calibration landmark","landmark",landmark,"Configured pointing reference");
        GoTo.Point surfer=null;
        if(fix!=null) {
            surfer=new GoTo.Point(fix.lat,fix.lon,Double.NaN);
            placemark(out,"Surfer GPS target","surfer",surfer,String.format(Locale.ROOT,
                "GPS #%d · %d ms old at selection\nSatellites %d · HDOP %.2f · Receiver time %d ms",
                fix.sequence,aim.ageAtSelection,fix.satellites,fix.hdop,fix.senderMs));
        }
        if(mount!=null&&range!=null) {
            if(start!=null) headingLine(out,"Starting heading","initial-heading",mount,start,range);
            if(surfer!=null) line(out,"Target bearing "+degrees(bearing)+"° true","target-heading",mount,surfer,
                String.format(Locale.ROOT,"Heading from the mount to the selected GPS fix · %.2f m",range));
            if(end!=null) {
                GoTo.Point endpoint=headingLine(out,"Final heading","final-heading",mount,end,range);
                if(surfer!=null&&aim.finalGapM!=null&&aim.finalGapM>0) {
                    String gap=String.format(Locale.ROOT,"%.2f m",aim.finalGapM);
                    line(out,"Final gap · "+gap,"final-gap",endpoint,surfer,
                        "Measured encoder error projected at the surfer's range · "+gap);
                }
            }
        }
        close(out);
    }

    private static GoTo.Point headingLine(Writer out,String name,String styleId,GoTo.Point mount,double heading,
                                          double range) throws IOException {
        GoTo.Point endpoint=Geo.destination(mount,heading,range);
        line(out,name+" "+degrees(heading)+"° true",styleId,mount,endpoint,
            String.format(Locale.ROOT,"Calibrated encoder heading projected %.2f m from the mount",range));
        return endpoint;
    }

    private static Double heading(AimAtSurfer.Attempt aim,Integer count) {
        return count==null||aim.reference==null||aim.panCpr==null?null:GoTo.panHeading(count,aim.reference,aim.panCpr);
    }

    private static String degrees(Double value) {
        return value==null?"—":String.format(Locale.ROOT,"%.2f",value);
    }
}
