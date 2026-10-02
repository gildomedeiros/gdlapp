package dji.v5.ux.sample.showcase.defaultlayout.aiming;
/** VT 3.7: Immutable interpolated magnitudes; null curve preserves the pre-JSON response. */
public final class RotationSpeedCurve {
    private final double[][] normal,riding;
    public RotationSpeedCurve(double[][] normal,double[][] riding) {
        this.normal=copy(normal,false);this.riding=copy(riding,true);
    }
    private static double[][] copy(double[][] rows,boolean ride) {
        if(rows==null || rows.length<2 || rows.length>32)throw new IllegalArgumentException("Use 2-32 rotation points");
        double[][] result=new double[rows.length][2];double previous=-1,previousSpeed=-1;
        boolean tolerance=ride;
        for(int i=0;i<rows.length;i++) {
            if(rows[i]==null || rows[i].length!=2)throw new IllegalArgumentException("Invalid rotation point");
            double e=rows[i][0],s=rows[i][1];
            if(!Double.isFinite(e)||!Double.isFinite(s)||e<=previous||e>180||s<0||s>30||s<previousSpeed)
                throw new IllegalArgumentException("Errors must increase 0-180; speeds must not decrease and must be 0-30");
            if(i==0 && (e!=0 || s!=0))throw new IllegalArgumentException("First point must be 0 degrees / 0 speed");
            if(!ride && e<=3 && s!=0)throw new IllegalArgumentException("Normal tolerance is 3 degrees");
            if(e==3 && s==0)tolerance=true;
            result[i][0]=e;result[i][1]=s;previous=e;previousSpeed=s;
        }
        if(!tolerance)throw new IllegalArgumentException("Normal curve requires 3 degrees / 0 speed");
        return result;
    }
    public double speed(double error,boolean ride) {
        if(!Double.isFinite(error))throw new IllegalArgumentException("Invalid heading error");
        double e=Math.abs(error);double[][] rows=ride?riding:normal;
        for(int i=1;i<rows.length;i++)if(e<=rows[i][0]) {
            double fraction=(e-rows[i-1][0])/(rows[i][0]-rows[i-1][0]);
            return rows[i-1][1]+fraction*(rows[i][1]-rows[i-1][1]);
        }
        return rows[rows.length-1][1];
    }
    public static double desired(double error,boolean riding,double maxRate,RotationSpeedCurve curve) {
        double effective=Math.max(0,Math.abs(error)-(riding?0:YawAimingMath.ALIGNMENT_DEGREES));
        if(effective==0)return 0;
        double magnitude=curve==null?effective*.5:curve.speed(error,riding);
        return Math.copySign(Math.min(maxRate,magnitude),error);
    }
}
