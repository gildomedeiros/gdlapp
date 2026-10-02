package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.util.*;
/** VT 3.8: Reject duplicate keys instead of silently accepting the last setting. */
public final class StrictConfigJson {
    private static void value(JsonReader r,int depth)throws IOException {
        if(depth>16)throw new IllegalArgumentException("JSON nesting exceeds 16 levels");
        switch(r.peek()) {
        case BEGIN_OBJECT:
            r.beginObject();Set<String> keys=new HashSet<>();
            while(r.hasNext()){String name=r.nextName();if(!keys.add(name))throw new IllegalArgumentException("Duplicate field: "+name);value(r,depth+1);}r.endObject();break;
        case BEGIN_ARRAY:
            r.beginArray();while(r.hasNext())value(r,depth+1);r.endArray();break;
        default:r.skipValue();
        }
    }
    public static JsonObject object(String raw) {
        if(raw==null||raw.length()>65536)throw new IllegalArgumentException("File missing or larger than 64 KiB");
        try {
            JsonReader r=new JsonReader(new StringReader(raw));r.setLenient(false);value(r,0);
            if(r.peek()!=JsonToken.END_DOCUMENT)throw new IllegalArgumentException("Unexpected content after JSON object");
            JsonReader parse=new JsonReader(new StringReader(raw));parse.setLenient(false);
            JsonElement e=com.google.gson.internal.Streams.parse(parse);
            if(!e.isJsonObject())throw new IllegalArgumentException("Configuration must be a JSON object");return e.getAsJsonObject();
        }catch(IOException e){throw new IllegalArgumentException("Invalid JSON: "+e.getMessage(),e);}
    }
}
