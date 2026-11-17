package au.com.gildo.vtc;
import java.util.*;

final class ZoomBands {
    final double buffer;final double[] starts,zoom;
    ZoomBands(String json){
        Object parsed=ConfigJson.parse(json);if(!(parsed instanceof Map))throw new IllegalArgumentException("zoomBands must contain bufferMetres and bands");
        Map<?,?> m=(Map<?,?>)parsed;
        if(!m.keySet().equals(new HashSet<>(Arrays.asList("bufferMetres","bands"))))throw new IllegalArgumentException("zoomBands requires only bufferMetres and bands");
        buffer=number(m.get("bufferMetres"));if(buffer<0||buffer>100)throw new IllegalArgumentException("bufferMetres must be 0–100");
        if(!(m.get("bands") instanceof List))throw new IllegalArgumentException("bands must be an array");
        List<?> rows=(List<?>)m.get("bands");if(rows.isEmpty()||rows.size()>100)throw new IllegalArgumentException("Use 1–100 zoom bands");
        starts=new double[rows.size()];zoom=new double[rows.size()];double previous=0;
        for(int i=0;i<rows.size();i++){
            if(!(rows.get(i) instanceof Map))throw new IllegalArgumentException("Each band must be an object");Map<?,?> row=(Map<?,?>)rows.get(i);
            if(!row.keySet().equals(new HashSet<>(Arrays.asList("maxDistanceMetres","zoomX"))))throw new IllegalArgumentException("Each band needs maxDistanceMetres and zoomX");
            starts[i]=previous;zoom[i]=number(row.get("zoomX"));if(zoom[i]<1||zoom[i]>65)throw new IllegalArgumentException("zoomX must be 1–65");
            Object upper=row.get("maxDistanceMetres");
            if(i==rows.size()-1){if(upper!=null)throw new IllegalArgumentException("Last band maxDistanceMetres must be null");}
            else{double end=number(upper);if(end<=previous||end>10000)throw new IllegalArgumentException("Band distances must increase, up to 10000 m");previous=end;}
        }
    }
    private static double number(Object o){if(!(o instanceof Number)||!Double.isFinite(((Number)o).doubleValue()))throw new IllegalArgumentException("Band values must be JSON numbers");return ((Number)o).doubleValue();}
    static String legacy(String text){
        double[] values=ZoomPlan.parse(text,false);List<Object> rows=new ArrayList<>();
        double[] ends={40,60,100,150,200,250,300,350,400,450,500};
        for(int i=0;i<values.length;i++){Map<String,Object> r=new LinkedHashMap<>();r.put("maxDistanceMetres",ends[i]);r.put("zoomX",values[i]);rows.add(r);}
        Map<String,Object> last=new LinkedHashMap<>();last.put("maxDistanceMetres",null);last.put("zoomX",values[values.length-1]);rows.add(last);
        Map<String,Object> out=new LinkedHashMap<>();out.put("bufferMetres",5);out.put("bands",rows);return LogJson.encode(out);
    }
}
