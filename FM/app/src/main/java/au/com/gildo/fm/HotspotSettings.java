package au.com.gildo.fm;

import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** File-backed connection credentials, kept separate from the settings arrays used in run logs. */
record HotspotSettings(String name,String password) {
    static final String NAME_KEY="Wi-Fi hotspot name", PASSWORD_KEY="Wi-Fi hotspot password";
    static final HotspotSettings DEFAULT=new HotspotSettings("GDL_LORA","gdl12345");

    HotspotSettings {
        if(name.isEmpty() || name.getBytes(StandardCharsets.UTF_8).length>32)
            throw new IllegalArgumentException("Wi-Fi hotspot name must be 1–32 UTF-8 bytes");
        if(!password.isEmpty() && !password.matches("[ -~]{8,63}"))
            throw new IllegalArgumentException("Use an 8–63 character printable ASCII Wi-Fi password or leave it empty for an open network");
    }

    static HotspotSettings read(JSONObject fields) {
        return new HotspotSettings(text(fields,NAME_KEY,DEFAULT.name),text(fields,PASSWORD_KEY,DEFAULT.password));
    }

    private static String text(JSONObject fields,String key,String fallback) {
        if(!fields.has(key)) return fallback;
        if(fields.opt(key) instanceof String value) return value;
        throw new IllegalArgumentException(key+" must be a JSON string");
    }

    void writeTo(JSONObject fields) throws JSONException {
        fields.put(NAME_KEY,name);
        fields.put(PASSWORD_KEY,password);
    }

    static HotspotSettings cached(SharedPreferences prefs) {
        return new HotspotSettings(prefs.getString(NAME_KEY,DEFAULT.name),prefs.getString(PASSWORD_KEY,DEFAULT.password));
    }

    void cache(SharedPreferences prefs) {
        prefs.edit().putString(NAME_KEY,name).putString(PASSWORD_KEY,password).apply();
    }

    @Override public String toString() { return name; }
}
