package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.database.Cursor;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** VT 3.7: User-granted directory, no broad storage permission and no overwrite of custom files. */
public final class SharedConfigStorage {
    public static final String ROTATION="vt_rotation_speeds.json";
    // VT 3.8: A new file is seeded once per selected folder; deleted files subsequently block Start.
    public static final String RETREAT="vt_retreat_settings.json";
    public static final String SETTINGS="vt_settings.json",WAVE_LINES="vt_wave_lines.json";
    private static final String KEY="configurationTree";
    public static String folder(Context c){return c.getSharedPreferences("vt28",Context.MODE_PRIVATE).getString(KEY,null);}
    private static Uri find(Context c,Uri tree,String name)throws IOException {
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        try(Cursor cursor=c.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)) {
            if(cursor==null)throw new IOException("Cannot list configuration folder");
            Uri result=null;
            while(cursor.moveToNext())if(name.equals(cursor.getString(1))) {
                if(result!=null)throw new IOException("Duplicate configuration filename: "+name);
                result=DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0));
            }
            return result;
        }
    }
    private static String readUri(Context c,Uri file)throws IOException {
        InputStream in=c.getContentResolver().openInputStream(file);
        if(in==null)throw new IOException("Cannot read configuration");return GimbalBandStorage.read(in);
    }
    public static String read(Context c,String name)throws IOException {
        String selected=folder(c);if(selected==null)return null;
        Uri file=find(c,Uri.parse(selected),name);if(file==null)throw new FileNotFoundException(name+" missing in selected folder");
        return readUri(c,file);
    }
    private static ConfigFileMigration.Files files(Context c,Uri tree) {
        return new ConfigFileMigration.Files() {
            public boolean exists(String name)throws IOException{return find(c,tree,name)!=null;}
            public String read(String name)throws IOException{Uri u=find(c,tree,name);if(u==null)throw new FileNotFoundException(name);return readUri(c,u);}
            public void create(String name,String contents)throws IOException {
                if(find(c,tree,name)!=null)throw new IOException("Configuration appeared during setup; select folder again");
                Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
                Uri created=DocumentsContract.createDocument(c.getContentResolver(),parent,"application/json",name);
                if(created==null)throw new IOException("Cannot create "+name);
                try {
                    try(OutputStream out=c.getContentResolver().openOutputStream(created,"w")) {
                        if(out==null)throw new IOException("Cannot write "+name);
                        out.write(contents.getBytes(StandardCharsets.UTF_8));
                    }
                    if(!created.equals(find(c,tree,name))||!contents.equals(readUri(c,created)))throw new IOException("Cannot verify "+name);
                }catch(Exception e){try{DocumentsContract.deleteDocument(c.getContentResolver(),created);}catch(Exception cleanup){e.addSuppressed(cleanup);}throw new IOException("Copy failed: "+name+"; "+e.toString()+"; cleanup failures="+java.util.Arrays.toString(e.getSuppressed()),e);}
            }
        };
    }
    public static boolean ensureRetreat(Context c)throws IOException {
        String selected=folder(c);
        if(selected==null)throw new IOException("No configuration folder selected. Choose Download/VT using Configuration folder.");
        if(selected.equals(c.getSharedPreferences("vt28",Context.MODE_PRIVATE).getString("retreatSeededTree",null)))return false;
        boolean created=ConfigFileMigration.ensure(files(c,Uri.parse(selected)),RETREAT,()->GimbalBandStorage.read(c.getAssets().open(RETREAT)));
        if(!c.getSharedPreferences("vt28",Context.MODE_PRIVATE).edit().putString("retreatSeededTree",selected).commit())throw new IOException("Cannot save retreat file setup state");
        return created;
    }

    public static void ensure40(Context c)throws IOException {
        String selected=folder(c);if(selected==null)throw new IOException("Choose Configuration folder before wave line setup or Start");
        if(selected.equals(c.getSharedPreferences("vt28",Context.MODE_PRIVATE).getString("vt40SeededTree",null)))return;
        for(String name:new String[]{SETTINGS,WAVE_LINES})ConfigFileMigration.ensure(files(c,Uri.parse(selected)),name,()->GimbalBandStorage.read(c.getAssets().open(name)));
        if(!c.getSharedPreferences("vt28",Context.MODE_PRIVATE).edit().putString("vt40SeededTree",selected).commit())throw new IOException("Cannot save VT 4.0 setup state");
    }
    private static void overwrite(Context c,String name,String content)throws IOException {
        Uri tree=Uri.parse(folder(c)),file=find(c,tree,name);if(file==null)throw new FileNotFoundException(name+" missing");
        try(OutputStream out=c.getContentResolver().openOutputStream(file,"wt")) {
            if(out==null)throw new IOException("Cannot write "+name);out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        if(!content.equals(read(c,name)))throw new IOException("Read-back verification failed for "+name);
    }
    /** Direct overwrite, then read-back verification. No backup or rollback file is created. */
    public static void writeVerified(Context c,String name,String content)throws IOException {
        if(!SETTINGS.equals(name)&&!WAVE_LINES.equals(name))throw new IOException("Unsupported wave line file");
        if(folder(c)==null)throw new IOException("Choose Configuration folder first");
        try {overwrite(c,name,content);}catch(Exception e) {
            throw new IOException("Save failed: "+name+" — "+e.getMessage(),e);
        }
    }
    public static void select(Context c,Uri tree)throws IOException {
        c.getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        ConfigFileMigration.Files files=files(c,tree);
        ConfigFileMigration.ensure(files,GimbalBandStorage.NAME,()->{
            File dir=c.getExternalFilesDir(null);File old=dir==null?null:new File(dir,GimbalBandStorage.NAME);
            return old!=null&&old.exists()?GimbalBandStorage.read(new FileInputStream(old)):GimbalBandStorage.read(c.getAssets().open(GimbalBandStorage.NAME));
        });
        ConfigFileMigration.ensure(files,ROTATION,()->GimbalBandStorage.read(c.getAssets().open(ROTATION)));
        ConfigFileMigration.ensure(files,RETREAT,()->GimbalBandStorage.read(c.getAssets().open(RETREAT)));
        for(String name:new String[]{SETTINGS,WAVE_LINES})ConfigFileMigration.ensure(files,name,()->GimbalBandStorage.read(c.getAssets().open(name)));
        if(!c.getSharedPreferences("vt28",Context.MODE_PRIVATE).edit().putString("vt40SeededTree",tree.toString()).putString("retreatSeededTree",tree.toString()).putString(KEY,tree.toString()).commit())throw new IOException("Cannot save folder selection");
    }
}
