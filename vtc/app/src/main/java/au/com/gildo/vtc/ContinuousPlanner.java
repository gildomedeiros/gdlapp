package au.com.gildo.vtc;

/** Low-speed proportional correction and one fixed maximum rate for high-speed catch-up. */
final class ContinuousPlanner {
    static final double RESTART_FACTOR=1.5, RESPONSE_SECONDS=2.0;
    static final double LOW_SPEED_CAP=.5, HIGH_RETURN_SECONDS=.5, HIGH_ENTRY_MARGIN=.25;
    private final boolean[] correcting={false,false};
    private final boolean[] high={false,false};
    void reset(){correcting[0]=correcting[1]=high[0]=high[1]=false;}
    static double highReturnError(double tolerance,double maximum){
        return Math.max(tolerance*RESTART_FACTOR,Math.max(LOW_SPEED_CAP*RESPONSE_SECONDS,maximum*HIGH_RETURN_SECONDS));
    }
    static double highEntryError(double tolerance,double maximum){return highReturnError(tolerance,maximum)+HIGH_ENTRY_MARGIN;}
    double rate(int axis,double error,double tolerance,double maximum) {
        if(!Double.isFinite(error))throw new IllegalArgumentException("Invalid pointing error");
        int i=axis-1;
        if(Math.abs(error)<=tolerance){correcting[i]=high[i]=false;return 0;}
        if(!correcting[i]&&Math.abs(error)<=tolerance*RESTART_FACTOR)return 0;
        correcting[i]=true;
        if(maximum>LOW_SPEED_CAP){
            if(high[i]&&Math.abs(error)<=highReturnError(tolerance,maximum))high[i]=false;
            else if(!high[i]&&Math.abs(error)>highEntryError(tolerance,maximum))high[i]=true;
        }else high[i]=false;
        // No intermediate high speeds: changing that rate while running requires Stop.
        if(high[i])return Math.copySign(maximum,error);
        return Math.copySign(Math.min(Math.min(maximum,LOW_SPEED_CAP),Math.abs(error)/RESPONSE_SECONDS),error);
    }
    static double limitRate(double rate,double position,double min,double max){
        // Reserve travel for a delayed read, watchdog detection and Stop transmission.
        double room=rate>0?max-position:position-min;
        double bounded=Math.min(Math.abs(rate),Math.max(0,room-.05)/12.0);
        // A sector limit must not create a stream of decreasing high-speed periods.
        // Switch directly to low-speed correction if there is insufficient room for the maximum.
        if(Math.abs(rate)>LOW_SPEED_CAP&&bounded<Math.abs(rate))bounded=Math.min(LOW_SPEED_CAP,bounded);
        return bounded<.005?0:Math.copySign(bounded,rate);
    }
    static boolean gapAllowed(long now,long lastAccepted,double seconds){
        return lastAccepted>=0&&now>=lastAccepted&&now-lastAccepted<seconds*1000;
    }
}
