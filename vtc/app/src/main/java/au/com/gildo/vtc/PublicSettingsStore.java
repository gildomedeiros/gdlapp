package au.com.gildo.vtc;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Public JSON plus a durable recovery copy and automatic retry of pending writes. */
final class PublicSettingsStore {
    private static final String DIR="Download/VTC/Settings/", DIRTY="_public_write_pending";
    private final Context context;
    private final SharedPreferences cache;
    private final String name;
    private final SessionLog log;
    private String error="";
    PublicSettingsStore(Context context,String legacyName,String name,SessionLog log){
        this.context=context.getApplicationContext();this.name=name;this.log=log;
        cache=context.getSharedPreferences(legacyName,Context.MODE_PRIVATE);
    }
    synchronized Map<String,String> load()throws IOException {
        if(cache.getBoolean(DIRTY,false))return cached();
        try(InputStream in=openRead()) {
            if(in!=null){
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
                while((n=in.read(b))!=-1){bytes.write(b,0,n);if(bytes.size()>131072)throw new IOException("Settings file too large");}
                Map<String,String> values=ConfigJson.settings(bytes.toString(StandardCharsets.UTF_8.name()));
                cache(values,false);error="";return values;
            }
        }catch(Exception e){error=e.toString();log.error("settings_public_read_failed",e,"path",DIR+name);}
        Map<String,String> old=cached();
        if(!old.isEmpty()){cache(old,true);retry();log.record("settings_storage_migration","path",DIR+name,"status",status());}
        return old;
    }
    synchronized void save(Map<String,String> values)throws IOException {cache(values,true);retry();}
    synchronized void retry(){
        if(!cache.getBoolean(DIRTY,false))return;
        try(OutputStream out=openWrite()){
            out.write(new JSONObject(ConfigJson.structured(cached())).toString(2).getBytes(StandardCharsets.UTF_8));out.flush();
        }catch(Exception e){error=e.toString();log.error("settings_public_write_failed",e,"path",DIR+name);return;}
        if(!cache.edit().putBoolean(DIRTY,false).commit()){
            error="Public file saved; could not clear recovery marker";log.event("settings_recovery_marker_failed",DIR+name);return;
        }
        error="";log.record("settings_public_saved","path",DIR+name);
    }
    synchronized String status(){return error.isEmpty()?DIR+name:DIR+name+" — recovery copy retained; retrying: "+error;}
    private Map<String,String> cached(){
        Map<String,String> values=new LinkedHashMap<>();
        for(Map.Entry<String,?> e:cache.getAll().entrySet())if(e.getValue() instanceof String)values.put(e.getKey(),(String)e.getValue());
        return values;
    }
    private void cache(Map<String,String> values,boolean dirty)throws IOException {
        SharedPreferences.Editor e=cache.edit().clear();
        for(Map.Entry<String,String> v:values.entrySet())e.putString(v.getKey(),v.getValue());
        if(!e.putBoolean(DIRTY,dirty).commit())throw new IOException("Cannot retain settings recovery copy");
    }
    @android.annotation.TargetApi(29) // Called only from the API-29+ branches of openRead/openWrite.
    private Uri find() {
        try(Cursor c=context.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            new String[]{MediaStore.MediaColumns._ID},MediaStore.MediaColumns.RELATIVE_PATH+"=? AND "+MediaStore.MediaColumns.DISPLAY_NAME+"=?",
            new String[]{DIR,name},MediaStore.MediaColumns._ID+" DESC")){
            return c!=null&&c.moveToFirst()?ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,c.getLong(0)):null;
        }
    }
    private File file(){return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"VTC/Settings/"+name);}
    private InputStream openRead()throws IOException {
        if(Build.VERSION.SDK_INT>=29){Uri u=find();return u==null?null:context.getContentResolver().openInputStream(u);}
        File f=file();return f.exists()?new FileInputStream(f):null;
    }
    private OutputStream openWrite()throws IOException {
        if(Build.VERSION.SDK_INT>=29){
            Uri u=find();ContentResolver resolver=context.getContentResolver();
            if(u==null){ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,DIR);v.put(MediaStore.MediaColumns.MIME_TYPE,"application/json");
                u=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);}
            if(u==null)throw new IOException("Cannot create "+DIR+name);
            OutputStream out=resolver.openOutputStream(u,"wt");if(out==null)throw new IOException("Cannot open settings");return out;
        }
        File f=file();File parent=f.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Allow storage permission to save settings");
        return new FileOutputStream(f);
    }
}
