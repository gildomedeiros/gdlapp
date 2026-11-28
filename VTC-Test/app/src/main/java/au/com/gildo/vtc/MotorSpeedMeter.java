package au.com.gildo.vtc;
import java.util.Locale;
/** Sample-average motor counter speed; not physical optical-axis speed or peak slew rate. */
final class MotorSpeedMeter {
    private String lastMoving = "";
    private long lastTime;
    private GtiClient.Position last;
    String sample(GtiClient.Position position,int[] cpr,long nanos) {
        String result="Measured motor rate: waiting for next sample";
        if(last!=null) {
            double seconds=(nanos-lastTime)/1e9;
            if(seconds>=0.03 && seconds<=5) {
                double pan=GtiClient.shortestDelta(position.pan,last.pan,cpr[0])*360.0/cpr[0]/seconds;
                double tilt=GtiClient.shortestDelta(position.tilt,last.tilt,cpr[1])*360.0/cpr[1]/seconds;
                result=String.format(Locale.US,"Sample-average motor rate: pan %.3f°/s • tilt %.3f°/s",pan,tilt);
                if (Math.abs(pan)>0.00001 || Math.abs(tilt)>0.00001) lastMoving=result;
                else if (!lastMoving.isEmpty()) result += "\nLast non-zero interval: " + lastMoving;
            }
        }
        last=position;lastTime=nanos;return result;
    }
    void reset() { last=null;lastTime=0;lastMoving=""; }
}

