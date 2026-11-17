package au.com.gildo.vtc;

import java.util.*;

/** Pure distance-band and optical zoom policy. CCAPI mapping is provisional, not measured. */
final class ZoomPlan {
    static final double[] STARTS={0,40,60,100,150,200,250,300,350,400,450};
    static final String DEFAULT_BANDS="1, 1, 1.5, 2.5, 4, 5, 6.5, 8, 9.5, 10.5, 12";
    static final double HYSTERESIS=5;
    static final String MAPPING="provisional log interpolation: (1x,0), (6x,89), (10x,102); extrapolate above 10x";
    static final class Input {
        final double distance;final long ageMs;final boolean valid;
        Input(double distance,long ageMs,boolean valid){this.distance=distance;this.ageMs=ageMs;this.valid=valid;}
    }
    static double[] parse(String text,boolean multipliers){
        String[] parts=text.split(",",-1);
        if(parts.length<1||parts.length>(multipliers?20:11)||(!multipliers&&parts.length!=11))
            throw new IllegalArgumentException(multipliers?"Enter 1 to 20 comma-separated multipliers":"Enter 11 band zoom values in distance order");
        double[] result=new double[parts.length];
        for(int i=0;i<parts.length;i++){
            try{result[i]=Double.parseDouble(parts[i].trim());}catch(Exception e){throw new IllegalArgumentException("Enter comma-separated decimal zoom values");}
            double n=result[i];
            if(!Double.isFinite(n)||n<(multipliers?.5:1)||n>(multipliers?3:65)||
                (multipliers&&Math.abs(n*10-Math.rint(n*10))>1e-8))
                throw new IllegalArgumentException(multipliers?"Multipliers must be 0.5–3.0 in 0.1 increments":"Band zoom must be 1–65x");
            if(!multipliers&&i>0&&n<result[i-1])throw new IllegalArgumentException("Band zoom must not decrease with distance");
        }
        return result;
    }
    static double raw(double optical){
        if(optical<=1)return 0;
        return optical<=6?89*Math.log(optical)/Math.log(6):89+13*Math.log(optical/6)/Math.log(10.0/6);
    }
    static double optical(double raw){return raw<=89?Math.exp(raw*Math.log(6)/89):6*Math.exp((raw-89)*Math.log(10.0/6)/13);}
    static double width(double distance,double optical){return distance*1.64825201164/optical;}
    private String key="";private long began;private int band=-1;
    void reset(){key="";band=-1;}
    static final class Decision {
        final Map<String,Object> fields;final int target;final boolean enabled;
        Decision(Map<String,Object> fields,int target,boolean enabled){this.fields=fields;this.target=target;this.enabled=enabled;}
    }
    Decision decide(Settings s,Input input,long now,int min,int max,int step){
        String next=s.autoZoom+"/"+s.values.get("zoomMultipliers")+"/"+s.zoomSwitchSeconds+"/"+s.values.get("zoomBands");
        if(!next.equals(key)){key=next;began=now;band=-1;}
        Map<String,Object> f=new LinkedHashMap<>();
        f.put("automaticZoom",s.autoZoom);f.put("settings",s.values);f.put("bandStartsM",STARTS);f.put("bandOpticalZoom",s.zoomBands);
        f.put("mapping",MAPPING);f.put("hysteresisM",HYSTERESIS);f.put("gpsValid",input.valid);f.put("gpsAgeMs",input.ageMs);
        f.put("distanceM",Double.isFinite(input.distance)?input.distance:null);
        int phase=(int)(((now-began)/(long)(s.zoomSwitchSeconds*1000))%s.zoomMultipliers.length);
        double multiplier=s.zoomMultipliers[phase];
        f.put("multiplierIndex",phase);f.put("multiplier",multiplier);f.put("multipliers",s.zoomMultipliers);
        f.put("nextSwitchMonoMs",s.zoomMultipliers.length==1?null:began+((now-began)/(long)(s.zoomSwitchSeconds*1000)+1)*(long)(s.zoomSwitchSeconds*1000));
        if(!s.autoZoom||!input.valid||!Double.isFinite(input.distance)||input.distance<0){
            f.put("reason",!s.autoZoom?"Automatic zoom off":"No fresh valid GPS; holding zoom");return new Decision(f,-1,false);
        }
        int nominal=0;for(int i=1;i<STARTS.length;i++)if(input.distance>=STARTS[i])nominal=i;
        if(band<0)band=nominal;
        while(band<STARTS.length-1&&input.distance>=STARTS[band+1]+HYSTERESIS)band++;
        while(band>0&&input.distance<STARTS[band]-HYSTERESIS)band--;
        double requested=s.zoomBands[band]*multiplier,limited=Math.max(1,Math.min(65,requested));
        if(min<0||max<min||step<1)throw new IllegalArgumentException("Invalid camera zoom ability");
        int target=min+(int)Math.round((raw(limited)-min)/step)*step;
        target=Math.max(min,Math.min(min+((max-min)/step)*step,target));
        f.put("bandIndex",band);f.put("nominalBandIndex",nominal);f.put("hysteresisHeld",band!=nominal);
        f.put("bandStartM",STARTS[band]);f.put("bandEndM",band==STARTS.length-1?null:STARTS[band+1]);
        f.put("above500m",input.distance>500);f.put("baseOpticalZoom",s.zoomBands[band]);f.put("requestedOpticalZoom",requested);
        f.put("limitedOpticalZoom",limited);f.put("unroundedCcapi",raw(limited));f.put("requestedCcapi",target);
        f.put("cameraMin",min);f.put("cameraMax",max);f.put("cameraStep",step);
        f.put("clamped",limited!=requested||raw(limited)<min||raw(limited)>max);
        f.put("estimatedFrameWidthM",width(input.distance,limited));f.put("reason","Distance band and multiplier");
        return new Decision(f,target,true);
    }
}
