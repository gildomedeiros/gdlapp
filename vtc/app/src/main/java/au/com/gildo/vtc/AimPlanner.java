package au.com.gildo.vtc;

/** Checks acquisition and reversal permission; never wraps an out-of-sector target through a full turn. */
final class AimPlanner {
    private final Settings s;
    private double range=AimUnits.REFERENCE;
    void distance(double metres){if(Double.isFinite(metres)&&metres>0)range=metres;else throw new IllegalArgumentException("Invalid aiming distance");}
    private final int[] direction={0,0};
    private final long[] lastPermitted={-1,-1};
    AimPlanner(Settings s){this.s=s;}
    void reset(){direction[0]=direction[1]=0;lastPermitted[0]=lastPermitted[1]=-1;}
    double step(int axis,double error,long now) {
        int i=axis-1;
        double tolerance=axis==1?s.panToleranceDegrees(range):s.tiltToleranceDegrees(range);
        if(!Double.isFinite(error))throw new IllegalArgumentException("Invalid aiming error");
        if(Math.abs(error)<=tolerance+1e-10)return 0;
        int requested=error>0?1:-1;
        if(direction[i]!=0&&requested!=direction[i]&&lastPermitted[i]>=0&&now-lastPermitted[i]<s.reverseSeconds*1000)return 0;
        return Math.copySign(Math.min(10,Math.abs(error)),error);
    }
    boolean reversalBlocked(int axis,double step,long now){
        int i=axis-1;return step!=0&&direction[i]!=0&&(step>0?1:-1)!=direction[i]
            &&lastPermitted[i]>=0&&now-lastPermitted[i]<s.reverseSeconds*1000;
    }
    void sent(int axis,double step,long now){if(step!=0){direction[axis-1]=step>0?1:-1;lastPermitted[axis-1]=now;}}
    boolean shouldGoTo(double panError,double tiltError,long now) {
        double panStep=step(1,panError,now),tiltStep=step(2,tiltError,now);
        return Math.max(Math.abs(panError),Math.abs(tiltError))+1e-10>=s.acquisitionDegrees(range)
            &&(panStep!=0||tiltStep!=0)
            &&(Math.abs(panError)<=s.panToleranceDegrees(range)+1e-10||panStep!=0)
            &&(Math.abs(tiltError)<=s.tiltToleranceDegrees(range)+1e-10||tiltStep!=0);
    }
    String reason(int axis,double error,long now) {
        if(Math.abs(error)<=(axis==1?s.panToleranceDegrees(range):s.tiltToleranceDegrees(range)))return "Within aiming tolerance";
        return step(axis,error,now)==0?"Direction reversal hold":"Correction permitted";
    }
    java.util.Map<String,Object> snapshot(long now) {
        java.util.Map<String,Object> out=new java.util.LinkedHashMap<>();
        out.put("panDirection",direction[0]);out.put("tiltDirection",direction[1]);
        out.put("lastPanCommandMs",lastPermitted[0]);out.put("lastTiltCommandMs",lastPermitted[1]);
        out.put("panReverseHoldRemainingMs",lastPermitted[0]<0?0:Math.max(0,s.reverseSeconds*1000-(now-lastPermitted[0])));
        out.put("tiltReverseHoldRemainingMs",lastPermitted[1]<0?0:Math.max(0,s.reverseSeconds*1000-(now-lastPermitted[1])));return out;
    }
}

