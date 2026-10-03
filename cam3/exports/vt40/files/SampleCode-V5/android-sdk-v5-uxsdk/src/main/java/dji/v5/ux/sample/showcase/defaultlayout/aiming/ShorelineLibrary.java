package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import com.google.gson.*;
import java.util.*;
/** Named straight shoreline profiles. Selected ID lives only in vt_settings.json. */
public final class ShorelineLibrary {
    public static final class Profile {
        public final String id,name,method,capturedAt;
        public final ShorelineGeometry geometry;
        public final double aScatter,bScatter;
        public final int aFixes,bFixes;
        public Profile(String id,String name,ShorelineGeometry g,String method,String date,double aScatter,double bScatter,int aFixes,int bFixes) {
            if(id.trim().isEmpty()||name.trim().isEmpty()||id.length()>200||name.length()>200)throw new IllegalArgumentException("Shoreline ID/name required, <=200 characters");
            if(!method.equals("lora")&&!method.equals("manual"))throw new IllegalArgumentException("captureMethod must be lora or manual");
            if(date.trim().isEmpty())throw new IllegalArgumentException("capturedAt required");
            if(!Double.isFinite(aScatter)||!Double.isFinite(bScatter)||aScatter<0||bScatter<0||aScatter>100||bScatter>100||aFixes<0||bFixes<0)throw new IllegalArgumentException("Invalid capture quality");
            this.id=id;this.name=name;geometry=g;this.method=method;capturedAt=date;this.aScatter=aScatter;this.bScatter=bScatter;this.aFixes=aFixes;this.bFixes=bFixes;
        }
    }
    public final List<Profile> profiles;
    public ShorelineLibrary(List<Profile> profiles) {
        if(profiles.size()>100)throw new IllegalArgumentException("Maximum 100 shorelines");
        Set<String> ids=new HashSet<>();for(Profile p:profiles)if(!ids.add(p.id))throw new IllegalArgumentException("Duplicate shoreline ID: "+p.id);
        this.profiles=Collections.unmodifiableList(new ArrayList<>(profiles));
    }
    public Profile find(String id) {for(Profile p:profiles)if(p.id.equals(id))return p;throw new IllegalArgumentException("Selected shoreline '"+id+"' missing; capture/select a shoreline before Start");}
    private static double[] point(JsonObject o,String k) {
        JsonObject p=VtJsonFields.object(o,k);VtJsonFields.keys(p,"latitude","longitude");
        return new double[]{VtJsonFields.number(p,"latitude",-85,85),VtJsonFields.number(p,"longitude",-180,180)};
    }
    public static ShorelineLibrary parse(String raw) {
        JsonObject o=StrictConfigJson.object(raw);VtJsonFields.keys(o,"version","shorelines");VtJsonFields.number(o,"version",1,1);
        if(!o.get("shorelines").isJsonArray())throw new IllegalArgumentException("shorelines must be an array");
        List<Profile> ps=new ArrayList<>();
        for(JsonElement e:o.getAsJsonArray("shorelines")) {
            if(!e.isJsonObject())throw new IllegalArgumentException("Shoreline must be an object");JsonObject p=e.getAsJsonObject();
            VtJsonFields.keys(p,"id","name","pointA","pointB","seaSide","captureMethod","capturedAt","quality");
            double[] a=point(p,"pointA"),b=point(p,"pointB");JsonObject q=VtJsonFields.object(p,"quality");
            VtJsonFields.keys(q,"pointAFixes","pointBFixes","pointAScatterMetres","pointBScatterMetres");
            double af=VtJsonFields.number(q,"pointAFixes",0,10000),bf=VtJsonFields.number(q,"pointBFixes",0,10000);
            if(af!=Math.rint(af)||bf!=Math.rint(bf))throw new IllegalArgumentException("Fix counts must be whole numbers");
            ps.add(new Profile(VtJsonFields.string(p,"id"),VtJsonFields.string(p,"name"),new ShorelineGeometry(a[0],a[1],b[0],b[1],VtJsonFields.string(p,"seaSide")),
                VtJsonFields.string(p,"captureMethod"),VtJsonFields.string(p,"capturedAt"),VtJsonFields.number(q,"pointAScatterMetres",0,100),VtJsonFields.number(q,"pointBScatterMetres",0,100),(int)af,(int)bf));
        }
        return new ShorelineLibrary(ps);
    }
    public String json() {
        JsonObject root=new JsonObject();root.addProperty("version",1);JsonArray a=new JsonArray();root.add("shorelines",a);
        for(Profile p:profiles) {
            JsonObject o=new JsonObject();a.add(o);o.addProperty("id",p.id);o.addProperty("name",p.name);
            JsonObject x=new JsonObject(),y=new JsonObject();x.addProperty("latitude",p.geometry.aLat);x.addProperty("longitude",p.geometry.aLon);
            y.addProperty("latitude",p.geometry.bLat);y.addProperty("longitude",p.geometry.bLon);o.add("pointA",x);o.add("pointB",y);
            o.addProperty("seaSide",p.geometry.seaSide);o.addProperty("captureMethod",p.method);o.addProperty("capturedAt",p.capturedAt);
            JsonObject q=new JsonObject();o.add("quality",q);q.addProperty("pointAFixes",p.aFixes);q.addProperty("pointBFixes",p.bFixes);
            q.addProperty("pointAScatterMetres",p.aScatter);q.addProperty("pointBScatterMetres",p.bScatter);
        }
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }
}
