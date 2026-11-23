package au.com.gildo.fm;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import static au.com.gildo.fm.Kml.*;

final class ReplayKml {
    record Viewpoint(int index,int csvRow,GoTo.Point point,boolean succeeded,String details) {}

    static void write(Writer out,String attemptId,String summary,GoTo.Point mount,GoTo.Point landmark,
                      List<Viewpoint> points) throws IOException {
        write(out,attemptId,summary,mount,landmark,points,false);
    }

    static void write(Writer out,String attemptId,String summary,GoTo.Point mount,GoTo.Point landmark,
                      List<Viewpoint> points,boolean highSpeed) throws IOException {
        String successful=highSpeed?"checkpoint-observed":"start-acknowledged";
        String unsuccessful=highSpeed?"checkpoint-unobserved":"start-unsuccessful";
        open(out,(highSpeed?"FM HS replay ":"FM replay ")+attemptId,summary);
        style(out,successful,"ff00ff00");
        style(out,unsuccessful,"ff0000ff");
        if(mount!=null) style(out,"mount","ffffaa00");
        if(landmark!=null) style(out,"landmark","ff00d7ff");

        if(mount!=null) placemark(out,"Mount","mount",mount,"Configured mount and camera pivot location");
        if(landmark!=null)
            placemark(out,"Calibration landmark","landmark",landmark,"Configured pointing reference location");

        for(Viewpoint vp:points) {
            String status=highSpeed?(vp.succeeded()?"Checkpoint observed":"Checkpoint unobserved"):
                (vp.succeeded()?"Start acknowledged":"Start unsuccessful");
            String style=vp.succeeded()?successful:unsuccessful;
            String name="Viewpoint "+vp.index()+" · CSV row "+vp.csvRow()+" · "+status;
            placemark(out,name,style,vp.point(),vp.details());
        }

        close(out);
    }

}
