package au.com.gildo.vtc;
import java.util.*;
/** Monotonic camera calibration, logarithmic in optical zoom. */
final class ZoomMapping {
    static final String DEFAULT="[{\"zoomX\":1,\"ccapiValue\":0},{\"zoomX\":6,\"ccapiValue\":89},{\"zoomX\":10,\"ccapiValue\":102}]";
    private final double[] zoom,ccapi;
    ZoomMapping(String json){
        Object parsed=ConfigJson.parse(json);
        if(!(parsed instanceof List))throw new IllegalArgumentException("zoomMapping must be an array");
        List<?> rows=(List<?>)parsed;
        if(rows.size()<2||rows.size()>100)throw new IllegalArgumentException("Enter 2–100 zoom mapping points");
        zoom=new double[rows.size()];ccapi=new double[rows.size()];
        for(int i=0;i<rows.size();i++){
            if(!(rows.get(i) instanceof Map))throw new IllegalArgumentException("Each zoom mapping point must be an object");
            Map<?,?> row=(Map<?,?>)rows.get(i);
            if(!row.keySet().equals(new HashSet<>(Arrays.asList("zoomX","ccapiValue")))||!(row.get("zoomX") instanceof Number)||!(row.get("ccapiValue") instanceof Number))throw new IllegalArgumentException("Mapping requires numeric zoomX and ccapiValue");
            zoom[i]=((Number)row.get("zoomX")).doubleValue();ccapi[i]=((Number)row.get("ccapiValue")).doubleValue();
            if(!Double.isFinite(zoom[i])||!Double.isFinite(ccapi[i])||zoom[i]<1||zoom[i]>65||ccapi[i]<0||ccapi[i]>1000000||ccapi[i]!=Math.rint(ccapi[i])||(i==0&&zoom[i]!=1)||(i>0&&(zoom[i]<=zoom[i-1]||ccapi[i]<=ccapi[i-1])))throw new IllegalArgumentException("Mapping must start at 1x and increase in both zoomX (1–65) and integer ccapiValue (0–1000000)");
        }
    }
    double raw(double x){
        int i=1;while(i<zoom.length-1&&x>zoom[i])i++;
        return ccapi[i-1]+Math.log(Math.max(1,x)/zoom[i-1])/Math.log(zoom[i]/zoom[i-1])*(ccapi[i]-ccapi[i-1]);
    }
    double optical(double value){
        int i=1;while(i<ccapi.length-1&&value>ccapi[i])i++;
        return Math.max(1,Math.min(65,zoom[i-1]*Math.exp((value-ccapi[i-1])/(ccapi[i]-ccapi[i-1])*Math.log(zoom[i]/zoom[i-1]))));
    }
}
