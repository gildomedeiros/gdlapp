package au.com.gildo.vtc;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Separate preferences survive setup saves, disconnects and app restarts. */
final class LandmarkStore {
    private final PublicSettingsStore store;
    LandmarkStore(Context context,SessionLog log){store=new PublicSettingsStore(context,"vtc_last_landmark","last-landmark.json",log);}
    void retry(){store.retry();}
    SavedLandmark load() throws IOException {
        Map<String,?> saved=store.load();if(saved.isEmpty())return null;
        Map<String,String> values=new LinkedHashMap<>();
        for(Map.Entry<String,?> e:saved.entrySet()) {
            if(!(e.getValue() instanceof String))throw new IllegalArgumentException("Invalid saved landmark field "+e.getKey());
            values.put(e.getKey(),(String)e.getValue());
        }
        return SavedLandmark.restore(values);
    }
    void save(SavedLandmark landmark)throws IOException {
        store.save(landmark.values());
    }
}
