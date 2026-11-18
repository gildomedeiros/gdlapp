package au.com.gildo.vtc;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class ZoomMappingTest {
    @Test public void suppliedTableRoundTripsAndEveryAnchorIsUsed(){
        List<?> rows=(List<?>)ConfigJson.parse(ZoomMapping.DEFAULT);
        assertEquals(102,rows.size());
        Settings s=new Settings(Map.of("zoomMapping",ZoomMapping.DEFAULT));
        Settings loaded=new Settings(ConfigJson.settings(LogJson.encode(ConfigJson.structured(s.values))));
        assertEquals(s.values,loaded.values);
        for(Object item:rows){
            Map<?,?> row=(Map<?,?>)item;
            assertEquals(((Number)row.get("ccapiValue")).doubleValue(),loaded.zoomMapping.raw(((Number)row.get("zoomX")).doubleValue()),1e-8);
        }
    }
    @Test public void sharedCommandIsFiniteAndMonotonic(){
        ZoomMapping m=new ZoomMapping(ZoomMapping.DEFAULT);
        assertEquals(60,m.raw(3.2),1e-8);assertEquals(60,m.raw(3.3),1e-8);
        assertEquals(Math.sqrt(3.2*3.3),m.optical(60),1e-8);
        double previous=0;
        for(double v=0;v<=201;v+=.05){double x=m.optical(v);assertTrue(Double.isFinite(x));assertTrue(x>=previous);previous=x;}
    }
    private String mapping(String points){
        StringJoiner out=new StringJoiner(",","[","]");
        for(String pair:points.split(";")){String[] p=pair.split(":");out.add("{\"zoomX\":"+p[0]+",\"ccapiValue\":"+p[1]+"}");}return out.toString();
    }
    @Test public void repeatedEndpointValuesDoNotDivideByZero(){
        ZoomMapping m=new ZoomMapping(mapping("1:0;2:0;3:10;4:20;5:20"));
        for(double v:new double[]{-1,0,1,10,19,20,21})assertTrue(Double.isFinite(m.optical(v)));
        assertEquals(Math.sqrt(2),m.optical(0),1e-8);assertEquals(Math.sqrt(20),m.optical(20),1e-8);
    }
    @Test public void rejectsDecreasingOrUnusableMappings(){
        for(String points:new String[]{"1:0;2:0","1:0;2:10;3:9","1:0;1:10"})
            assertThrows(IllegalArgumentException.class,()->new ZoomMapping(mapping(points)));
    }
    @Test public void existingCustomMappingStillWorks(){
        ZoomMapping m=new ZoomMapping(mapping("1:0;6:89;10:102"));
        assertEquals(102,m.raw(10),1e-8);assertEquals(10,m.optical(102),1e-8);
    }
}
