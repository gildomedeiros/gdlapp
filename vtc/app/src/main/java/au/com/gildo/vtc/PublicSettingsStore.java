package au.com.gildo.vtc;
import android.content.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Recovery is durable; public saves are successful only after exact readback. */
final class PublicSettingsStore {
    private static final String DIRTY="_public_write_pending";
    private final Context context;private final SharedPreferences cache,location;
    private final String name;private final SessionLog log;
    private volatile String storageStatus;
    PublicSettingsStore(Context context,String legacyName,String name,SessionLog log){
        this.context=context.getApplicationContext();this.name=name;this.log=log;
        cache=context.getSharedPreferences(legacyName,0);location=context.getSharedPreferences(legacyName+"_file_location",0);
        storageStatus="Checking "+AndroidSettingsFiles.DIR+name;
    }
    private SettingsFileIO.Entry resolve(AndroidSettingsFiles backend,boolean create)throws IOException{
        return SettingsFileIO.resolve(backend,name,location.getString("uri",null),create);
    }
    synchronized Map<String,String> load()throws IOException{
        if(cache.getBoolean(DIRTY,false)){storageStatus="Pending file save: "+AndroidSettingsFiles.DIR+name;return cached();}
        try{
            AndroidSettingsFiles backend=new AndroidSettingsFiles(context,location,name,log);
            SettingsFileIO.Entry entry=resolve(backend,false);
            if(entry!=null){Map<String,String> values=ConfigJson.settings(new String(SettingsFileIO.bytes(backend.read(entry)),StandardCharsets.UTF_8));cache(values,false);storageStatus="Loaded: "+AndroidSettingsFiles.DIR+name;return values;}
        }catch(Exception e){storageStatus="Cannot read "+name+": "+e;log.error("settings_public_read_failed",e,"file",name);if(cached().isEmpty())throw new IOException("Cannot load "+name+"; existing file was not changed",e);}
        Map<String,String> recovery=cached();if(!recovery.isEmpty()){cache(recovery,true);storageStatus="Pending file save: "+AndroidSettingsFiles.DIR+name;}return recovery;
    }
    synchronized void save(Map<String,String> values)throws IOException{cache(values,true);retry();}
    synchronized void retry(){
        if(!cache.getBoolean(DIRTY,false))return;
        storageStatus="Saving "+AndroidSettingsFiles.DIR+name+"…";
        try{
            AndroidSettingsFiles backend=new AndroidSettingsFiles(context,location,name,log);
            SettingsFileIO.Entry entry=resolve(backend,true);
            byte[] bytes=new JSONObject(ConfigJson.structured(cached())).toString(2).getBytes(StandardCharsets.UTF_8);
            SettingsFileIO.writeVerified(backend,entry,bytes);
            if(!cache.edit().putBoolean(DIRTY,false).commit())throw new IOException("File verified but recovery marker could not be cleared");
            storageStatus="Saved and verified: "+AndroidSettingsFiles.DIR+name+" · "+new java.text.SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date());
            log.record("settings_public_saved","path",AndroidSettingsFiles.DIR+entry.name,"uri",entry.id,"readbackVerified",true,"bytes",bytes.length);
        }catch(Exception e){storageStatus="PUBLIC FILE NOT SAVED: "+e+" — recovery copy retained";log.error("settings_public_write_failed",e,"file",name,"recoveryRetained",true);}
    }
    // UI reads never wait for slow storage I/O.
    String status(){return storageStatus;}
    private Map<String,String> cached(){Map<String,String> values=new LinkedHashMap<>();for(Map.Entry<String,?> e:cache.getAll().entrySet())if(e.getValue() instanceof String)values.put(e.getKey(),(String)e.getValue());return values;}
    private void cache(Map<String,String> values,boolean dirty)throws IOException{
        SharedPreferences.Editor editor=cache.edit().clear();for(Map.Entry<String,String> e:values.entrySet())editor.putString(e.getKey(),e.getValue());
        if(!editor.putBoolean(DIRTY,dirty).commit())throw new IOException("Cannot retain settings recovery copy");
    }
}
