package dji.v5.ux.sample.showcase.defaultlayout.aiming;
/** VT 3.8: All configuration files must validate before publishing any session settings. */
public final class VtSessionConfig {
    public static final class ShorelineSetupRequired extends IllegalArgumentException {
        ShorelineSetupRequired(String message){super("Shoreline setup required: "+message);}
    }
    public interface Reader {String read(String name)throws Exception;}
    public final ComeToMeSettings movement;
    public final ShorelinePositioning positioning;
    public final String settingsJson,shorelinesJson;
    public final RetreatSettings retreat;
    public final RotationSpeedCurve rotation;
    public final GimbalBandConfig gimbal;
    public final String retreatJson,rotationJson,gimbalJson;
    private VtSessionConfig(String r,String y,String g,RetreatSettings retreat,RotationSpeedCurve rotation,GimbalBandConfig gimbal) {
        this(r,y,g,retreat,rotation,gimbal,null,null,null,null);
    }
    private VtSessionConfig(String r,String y,String g,RetreatSettings retreat,RotationSpeedCurve rotation,GimbalBandConfig gimbal,
            ComeToMeSettings movement,ShorelinePositioning positioning,String settings,String shorelines) {
        retreatJson=r;rotationJson=y;gimbalJson=g;this.retreat=retreat;this.rotation=rotation;this.gimbal=gimbal;
        this.movement=movement;this.positioning=positioning;settingsJson=settings;shorelinesJson=shorelines;
    }
    private static String read(Reader reader,String name)throws Exception {
        String s=reader.read(name);if(s==null)throw new IllegalArgumentException("No configuration folder selected. Use Configuration folder first.");return s;
    }

    public static VtSessionConfig load40(Reader reader) {
        VtSessionConfig result=load(reader);String file="vt_settings.json";
        try {
            String settings=read(reader,file);VtSettingsConfig c=VtSettingsConfig.parse(settings,result.retreat);
            file="vt_shorelines.json";String library=read(reader,file);ShorelineLibrary l=ShorelineLibrary.parse(library);
            ShorelinePositioning positioning=null;
            if(c.movement.enabled) {
                if(l.profiles.isEmpty())throw new ShorelineSetupRequired("No saved shorelines yet. Capture shoreline points A/B or enter their coordinates, then save and select the shoreline.");
                if(c.shorelineId.trim().isEmpty())throw new ShorelineSetupRequired("No shoreline selected. Open Shorelines and tap a saved shoreline to select it.");
                ShorelineLibrary.Profile p;
                try{p=l.find(c.shorelineId);}catch(IllegalArgumentException missing){throw new ShorelineSetupRequired("The selected shoreline is no longer available. Open Shorelines and select a saved shoreline.");}
                positioning=new ShorelinePositioning(p.geometry,p.id,c.mode,c.sidewaysSide,c.alignmentTolerance,result.retreat.enabled?result.retreat.minimumDistance:0,c.angle,c.angleTolerance,c.extraClearance,c.movement.excursionStop());
            }
            return new VtSessionConfig(result.retreatJson,result.rotationJson,result.gimbalJson,result.retreat,result.rotation,result.gimbal,c.movement,positioning,settings,library);
        }catch(ShorelineSetupRequired e){throw e;}catch(Exception e){throw new IllegalArgumentException("Cannot start: "+file+" — "+e.getMessage()+". Correct configuration or capture/select a shoreline, then press Start.",e);}
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
