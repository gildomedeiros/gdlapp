package au.com.gildo.vtc;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;
public class V118Test {
    @Test public void deliveredSettingsRoundTrip()throws Exception{
        try(java.io.InputStream in=getClass().getResourceAsStream("/VTC-1.0.18-setup-autofit.json")){
            Settings s=new Settings(ConfigJson.settings(new String(in.readAllBytes(),StandardCharsets.UTF_8)));
            assertFalse(s.autoZoom);assertEquals(40,s.desiredFrameWidthMetres,0);assertEquals(1.648,s.wideFrameFactor,0);
            assertEquals(2,s.panTolerance,0);assertEquals(.5,s.tiltTolerance,0);assertEquals(12.51,s.magnitude,0);
            assertEquals(s.values,new Settings(ConfigJson.settings(LogJson.encode(ConfigJson.structured(s.values)))).values);
        }
    }
    @Test public void customMappingChangesCommandAndWidthReadback(){
        String map="[{\"zoomX\":1,\"ccapiValue\":0},{\"zoomX\":4,\"ccapiValue\":80},{\"zoomX\":16,\"ccapiValue\":160}]";
        Settings s=new Settings(Map.of("autoFit","1","zoomMapping",map,"desiredFrameWidthMetres","40","wideFrameFactor","1.6"));
        ZoomPlan.Decision d=new ZoomPlan().decide(s,new ZoomPlan.Input(100,0,true,1),0,0,201,1);
        assertEquals(80,d.target);assertEquals(40,((Number)d.fields.get("estimatedFrameWidthM")).doubleValue(),1e-9);
        assertEquals(120,s.zoomMapping.raw(8),1e-8);assertEquals(32,s.zoomMapping.optical(200),1e-8);
        assertTrue(s.onlyZoomChanged(new Settings(Map.of("autoFit","0","desiredFrameWidthMetres","20"))));
    }
    @Test public void validatesMappingAndFactors(){
        for(String bad:List.of("[]","{}","[{\"zoomX\":1,\"ccapiValue\":0}]","[{\"zoomX\":2,\"ccapiValue\":0},{\"zoomX\":6,\"ccapiValue\":89}]","[{\"zoomX\":1,\"ccapiValue\":89},{\"zoomX\":6,\"ccapiValue\":0}]"))assertThrows(IllegalArgumentException.class,()->new ZoomMapping(bad));
        for(String key:List.of("wideFrameFactor","desiredFrameWidthMetres"))for(String bad:List.of("0","-1","NaN"))assertThrows(IllegalArgumentException.class,()->new Settings(Map.of(key,bad)));
    }
    @Test public void scaleHalvesWidthAndLimitsReported(){
        Settings s=new Settings(Map.of("autoFit","1","zoom_scales","[1,2]"));ZoomPlan p=new ZoomPlan();
        ZoomPlan.Decision a=p.decide(s,new ZoomPlan.Input(250,0,true,1),0,0,201,1),b=p.decide(s,new ZoomPlan.Input(250,0,true,1),10000,0,201,1);
        assertEquals(40,((Number)a.fields.get("effectiveFrameWidthMetres")).doubleValue(),0);
        assertEquals(20,((Number)b.fields.get("effectiveFrameWidthMetres")).doubleValue(),0);assertTrue(b.target>a.target);
        assertEquals(true,p.decide(s,new ZoomPlan.Input(10000,0,true,1),11000,0,201,1).fields.get("clamped"));
    }
}
