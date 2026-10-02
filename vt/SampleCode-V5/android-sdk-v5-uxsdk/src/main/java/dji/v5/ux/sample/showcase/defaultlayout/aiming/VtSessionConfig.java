package dji.v5.ux.sample.showcase.defaultlayout.aiming;
/** VT 3.8: All configuration files must validate before publishing any session settings. */
public final class VtSessionConfig {
    public interface Reader {String read(String name)throws Exception;}
    public final RetreatSettings retreat;
    public final RotationSpeedCurve rotation;
    public final GimbalBandConfig gimbal;
    public final String retreatJson,rotationJson,gimbalJson;
    private VtSessionConfig(String r,String y,String g,RetreatSettings retreat,RotationSpeedCurve rotation,GimbalBandConfig gimbal) {
        retreatJson=r;rotationJson=y;gimbalJson=g;this.retreat=retreat;this.rotation=rotation;this.gimbal=gimbal;
    }
    private static String read(Reader reader,String name)throws Exception {
        String s=reader.read(name);if(s==null)throw new IllegalArgumentException("No configuration folder selected. Use Configuration folder first.");return s;
    }
    public static VtSessionConfig load(Reader reader) {
        String file="vt_retreat_settings.json";
        try {
            String r=read(reader,file);RetreatSettings retreat=RetreatJsonConfig.parse(r);
            file="vt_rotation_speeds.json";String y=read(reader,file);RotationSpeedCurve rotation=RotationSpeedConfig.parse(y);
            file="vt_gimbal_bands.json";String g=read(reader,file);GimbalBandConfig gimbal=GimbalBandConfig.parse(g);
            return new VtSessionConfig(r,y,g,retreat,rotation,gimbal);
        }catch(Exception e){throw new IllegalArgumentException("Cannot start: "+file+" — "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())+". Correct the file or folder access, then press Start.",e);}
    }
}
