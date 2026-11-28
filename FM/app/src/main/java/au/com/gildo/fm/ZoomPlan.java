package au.com.gildo.fm;

import java.util.*;

/** Pure distance-band and optical zoom policy. Uses the configured CCAPI calibration. */
final class ZoomPlan {
    static final double[] STARTS={0,40,60,100,150,200,250,300,350,400,450};
    static final String DEFAULT_BANDS="1, 1, 1.5, 2.5, 4, 5, 6.5, 8, 9.5, 10.5, 12";
    static final double HYSTERESIS=5;
    static final String MAPPING="configured zoom mapping with logarithmic interpolation";
    static final class Input {
        final double distance;
        final long ageMs,aimingSession;
        final boolean valid;
        Input(double distance,long ageMs,boolean valid,long aimingSession) {
            this.distance=distance;this.ageMs=ageMs;this.valid=valid;this.aimingSession=aimingSession;
        }
        boolean canSendFor(long session) {
            return session>=0&&aimingSession==session&&valid&&Double.isFinite(distance)&&distance>0;
        }
    }
    static double[] parse(String text,boolean multipliers){
        text=text.trim();if(multipliers&&text.startsWith("[")){Object parsed=ConfigJson.parse(text);if(!(parsed instanceof List))throw new IllegalArgumentException("zoom_scales must be an array");text=((List<?>)parsed).stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));}
        String[] parts=text.split(",",-1);
        if(parts.length<1||parts.length>(multipliers?20:11)||(!multipliers&&parts.length!=11))
            throw new IllegalArgumentException(multipliers?"Enter 1 to 20 comma-separated multipliers":"Enter 11 band zoom values in distance order");
        double[] result=new double[parts.length];
        for(int i=0;i<parts.length;i++){
            try{result[i]=Double.parseDouble(parts[i].trim());}catch(Exception e){throw new IllegalArgumentException("Enter comma-separated decimal zoom values");}
            double n=result[i];
            if(!Double.isFinite(n)||n<(multipliers?.5:1)||n>(multipliers?7:65)||
                (multipliers&&Math.abs(n*10-Math.rint(n*10))>1e-8))
                throw new IllegalArgumentException(multipliers?"Multipliers must be 0.5–7.0 in 0.1 increments":"Band zoom must be 1–65x");
            if(!multipliers&&i>0&&n<result[i-1])throw new IllegalArgumentException("Band zoom must not decrease with distance");
        }
        return result;
    }
    static double raw(double optical){return new ZoomMapping(ZoomMapping.DEFAULT).raw(optical);}
    static double optical(double raw){return new ZoomMapping(ZoomMapping.DEFAULT).optical(raw);}
    static double width(double distance,double optical){return distance*1.648/optical;}
    private String key="";private long began;private int band=-1;
    void reset(){key="";band=-1;}
    static final class Decision {
        final Map<String,Object> fields;final int target;final boolean enabled;
        Decision(Map<String,Object> fields,int target,boolean enabled){this.fields=fields;this.target=target;this.enabled=enabled;}
    }
    static boolean withinWidth(CameraSettings s,Input gps,Decision d,int current){
        if(!d.enabled||!gps.valid||!s.zoomMode.equals("auto-fit"))return false;
        double desired=((Number)d.fields.get("effectiveFrameWidthMetres")).doubleValue();
        return Math.abs(gps.distance*s.wideFrameFactor/s.zoomMapping.optical(current)-desired)<=desired*.05;
    }
    Decision decide(CameraSettings s,Input input,long now,int min,int max,int step){
        String next=input.aimingSession+"/"+s.zoomMode+"/"+s.values.get("zoomBands")+"/"+s.values.get("zoom_scales")+"/"+s.zoomSwitchSeconds+"/"+s.desiredFrameWidthMetres+"/"+s.wideFrameFactor+"/"+s.values.get("zoomMapping");
        if(!next.equals(key)){key=next;began=now;band=-1;}
        Map<String,Object> f=new LinkedHashMap<>();
        f.put("zoomMode",s.zoomMode);f.put("autoFit",s.zoomMode.equals("auto-fit"));f.put("settings",s.values);
        f.put("mapping",ConfigJson.parse(s.values.get("zoomMapping")));f.put("mappingMethod","log interpolation / end-segment extrapolation");
        f.put("gpsValid",input.valid);f.put("gpsAgeMs",input.ageMs);f.put("distanceM",Double.isFinite(input.distance)?input.distance:null);
        f.put("aimingActive",input.aimingSession>=0);
        if(!s.autoZoom||!input.canSendFor(input.aimingSession)){
            String reason="No fresh valid GPS; holding zoom";
            if(!s.autoZoom)reason="Zoom off; leaving zoom unchanged";
            else if(input.aimingSession<0)reason="Automatic zoom waiting for Start Aiming; reading zoom";
            f.put("reason",reason);
            return new Decision(f,-1,false);
        }
        int phase=(int)((Math.max(0,now-began)/(long)(s.zoomSwitchSeconds*1000))%s.zoomMultipliers.length);
        double scale=s.zoomMultipliers[phase],desired=s.desiredFrameWidthMetres/scale;
        f.put("multiplierIndex",phase);f.put("multiplier",scale);f.put("zoom_scales",s.zoomMultipliers);
        f.put("desiredFrameWidthMetres",s.desiredFrameWidthMetres);f.put("effectiveFrameWidthMetres",desired);f.put("wideFrameFactor",s.wideFrameFactor);f.put("widthToleranceFraction",.05);
        f.put("nextSwitchMonoMs",s.zoomMultipliers.length==1?null:began+((now-began)/(long)(s.zoomSwitchSeconds*1000)+1)*(long)(s.zoomSwitchSeconds*1000));
        double requested;
        if(s.zoomMode.equals("bands")){
            ZoomBands b=s.zoomBands;int nominal=0;
            while(nominal+1<b.starts.length&&input.distance>b.starts[nominal+1])nominal++;
            if(band<0)band=nominal;
            else{
                while(band+1<b.starts.length&&input.distance>b.starts[band+1]+b.buffer)band++;
                while(band>0&&input.distance<=b.starts[band]-b.buffer)band--;
            }
            requested=b.zoom[band]*scale;
            f.put("nominalBandIndex",nominal);f.put("selectedBandIndex",band);
            f.put("bandZoomX",b.zoom[band]);f.put("bufferMetres",b.buffer);
            f.put("bandMinDistanceMetres",b.starts[band]);
            f.put("bandMaxDistanceMetres",band+1<b.starts.length?b.starts[band+1]:null);
            f.put("effectiveFrameWidthMetres",input.distance*s.wideFrameFactor/Math.max(1,Math.min(65,requested)));
            f.put("widthToleranceFraction",null);
        }else requested=s.wideFrameFactor*input.distance/desired;
        double limited=Math.max(1,Math.min(65,requested));
        if(min<0||max<min||step<1)throw new IllegalArgumentException("Invalid camera zoom ability");
        double raw=s.zoomMapping.raw(limited);
        int last=min+((max-min)/step)*step;
        int target=(int)Math.max(min,Math.min(last,min+Math.round((raw-min)/step)*(double)step));
        f.put("requestedOpticalZoom",requested);f.put("limitedOpticalZoom",limited);f.put("unroundedCcapi",raw);f.put("requestedCcapi",target);
        f.put("cameraMin",min);f.put("cameraMax",max);f.put("cameraStep",step);
        f.put("clamped",limited!=requested||raw<min||raw>last);
        f.put("estimatedFrameWidthM",input.distance*s.wideFrameFactor/s.zoomMapping.optical(target));
        f.put("reason",s.zoomMode.equals("bands")?"Distance band and zoom scale":"GPS distance and desired frame width");return new Decision(f,target,true);
    }
}
