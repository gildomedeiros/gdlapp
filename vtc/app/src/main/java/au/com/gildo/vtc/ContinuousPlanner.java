package au.com.gildo.vtc;

/** Error-only speed control. One user speed cap; fixed slowdown and restart rules. */
final class ContinuousPlanner {
    static final double RESTART_FACTOR=1.5, RESPONSE_SECONDS=2.0;
    private final boolean[] correcting={false,false};
    void reset(){correcting[0]=correcting[1]=false;}
    double rate(int axis,double error,double tolerance,double maximum) {
        if(!Double.isFinite(error))throw new IllegalArgumentException("Invalid pointing error");
        int i=axis-1;
        if(Math.abs(error)<=tolerance){correcting[i]=false;return 0;}
        if(!correcting[i]&&Math.abs(error)<=tolerance*RESTART_FACTOR)return 0;
        correcting[i]=true;
        return Math.copySign(Math.min(maximum,Math.abs(error)/RESPONSE_SECONDS),error);
    }
    static double limitRate(double rate,double position,double min,double max){
        // Reserve travel for a delayed read, watchdog detection and Stop transmission.
        double room=rate>0?max-position:position-min;
        double bounded=Math.min(Math.abs(rate),Math.max(0,room-.05)/12.0);
        return bounded<.005?0:Math.copySign(bounded,rate);
    }
    static boolean gapAllowed(long now,long lastAccepted,double seconds){
        return lastAccepted>=0&&now>=lastAccepted&&now-lastAccepted<seconds*1000;
    }
}
