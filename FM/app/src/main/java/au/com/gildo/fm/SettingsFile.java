package au.com.gildo.fm;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import android.media.MediaScannerConnection;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

final class SettingsFile {
    static final String NAME="FM-settings.json";
    static final String VERSION_KEY="Settings version";
    private static final String ROW="downloadSettingsUri";
    private static final String GENERATED_NAME="FM-settings(?: \\([0-9]+\\))?\\.json";
    private static final String FOLDER="FM";
    private static final String DIRECTORY=Environment.DIRECTORY_DOWNLOADS+"/"+FOLDER;
    private static final String PATH=DIRECTORY+"/";
    private static final String STORAGE_PROVIDER="com.android.externalstorage.documents";
    private static final String DOWNLOADS_PROVIDER="com.android.providers.downloads.documents";
    private static final String TREE="linkedSettingsFolder";
    private static final int ACCESS=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    static final String LOCATION=PATH+NAME;

    record Snapshot(String[] values,Object version) {
        Snapshot withValues(String[] updated) { return new Snapshot(updated,version); }

        String displayVersion() {
            if(version==null || version==JSONObject.NULL) return "Not set";
            if(!(version instanceof String || version instanceof Number)) return "Use text or a number";
            String label=version.toString().trim();
            return label.isEmpty()?"Not set":label;
        }

        JSONObject document() throws Exception {
            JSONObject document=settingsDocument(values);
            document.getJSONObject("settings").put(VERSION_KEY,version);
            return document;
        }
    }
    record Loaded(Snapshot snapshot,String location,String warning,byte[] contents) {}

    record LastLoad(String version,long loadedAtMs,byte[] contents) {
        boolean matches(byte[] bytes) { return java.util.Arrays.equals(contents,bytes); }

        String labelAt(long nowMs) {
            long seconds=(nowMs-loadedAtMs)/1000;
            String age;
            if(seconds<1) age="just now";
            else if(seconds<60) age=seconds+" s ago";
            else if(seconds<3600) age=(seconds/60)+" min ago";
            else if(seconds<86400) age=(seconds/3600)+" h ago";
            else age=(seconds/86400)+" d ago";
            return version+" ["+age+"]";
        }
    }
    private record DownloadRow(Uri uri,String name) {}

    static Uri initialFolder() {
        return DocumentsContract.buildDocumentUri(STORAGE_PROVIDER,"primary:"+DIRECTORY);
    }

    static String[] cached(SharedPreferences prefs) {
        String[] values=FmService.DEFAULTS.clone();
        for(int i=0;i<values.length;i++) values[i]=prefs.getString(FmService.KEYS[i],values[i]);
        return values;
    }

    static void cache(SharedPreferences prefs,String[] values) {
        SharedPreferences.Editor edit=prefs.edit();
        for(int i=0;i<values.length;i++) edit.putString(FmService.KEYS[i],values[i]);
        edit.apply();
    }

    static Loaded read(Context context,SharedPreferences prefs) throws Exception {
        DownloadRow row=locate(context,prefs);
        return row==null?null:loadFile(context,prefs,row.uri(),row.name());
    }

    private static DownloadRow locate(Context context,SharedPreferences prefs) throws IOException {
        ContentResolver resolver=context.getContentResolver();
        String tree=prefs.getString(TREE,"");
        if(!tree.isEmpty()) return new DownloadRow(linkedFile(resolver,Uri.parse(tree)),NAME);
        if(Build.VERSION.SDK_INT<29) {
            File file=legacy();
            if(!file.exists()) return null;
            return new DownloadRow(Uri.fromFile(file),NAME);
        }
        return find(resolver,prefs);
    }

    static byte[] contents(Context context,SharedPreferences prefs) throws IOException {
        DownloadRow row=locate(context,prefs);
        if(row==null) return null;
        try(InputStream in=context.getContentResolver().openInputStream(row.uri())) { return readBytes(in); }
    }

    static Loaded linkFolder(Context context,SharedPreferences prefs,Uri tree,int resultFlags) throws Exception {
        requireFmFolder(tree);
        if((resultFlags&ACCESS)!=ACCESS)
            throw new IOException("Allow read and write access to the Download/FM folder");
        ContentResolver resolver=context.getContentResolver();
        resolver.takePersistableUriPermission(tree,ACCESS);
        boolean alreadyLinked=tree.toString().equals(prefs.getString(TREE,""));
        try {
            Loaded loaded=readLinked(context,prefs,tree);
            if(!prefs.edit().putString(TREE,tree.toString()).commit())
                throw new IOException("Could not remember the FM settings folder");
            return loaded;
        } catch(Exception e) {
            if(!alreadyLinked) {
                try { resolver.releasePersistableUriPermission(tree,ACCESS); }
                catch(SecurityException ignored) { }
            }
            throw e;
        }
    }

    private static void requireFmFolder(Uri tree) throws IOException {
        if(DocumentsContract.isTreeUri(tree)) {
            String document=DocumentsContract.getTreeDocumentId(tree);
            if(STORAGE_PROVIDER.equals(tree.getAuthority())&&document.equals("primary:"+DIRECTORY)) return;
            if(DOWNLOADS_PROVIDER.equals(tree.getAuthority())&&
                document.equals("raw:"+new File(Environment.getExternalStorageDirectory(),DIRECTORY).getPath())) return;
        }
        throw new IOException("Select Internal storage → Download → FM");
    }

    private static Uri linkedFile(ContentResolver resolver,Uri tree) throws IOException {
        Uri file=findLinkedFile(resolver,tree,NAME);
        if(file!=null) return file;
        throw new FileNotFoundException(LOCATION+" is missing. Restore it in the linked FM folder.");
    }

    static Uri linkedFolder(SharedPreferences prefs) {
        String tree=prefs.getString(TREE,"");
        return tree.isEmpty()?null:Uri.parse(tree);
    }

    static Uri findLinkedFile(ContentResolver resolver,Uri tree,String filename) throws IOException {
        requireFmFolder(tree);
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        try(Cursor c=resolver.query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)) {
            if(c!=null) while(c.moveToNext()) {
                if(filename.equals(c.getString(1)))
                    return DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0));
            }
        }
        return null;
    }

    private static Loaded readLinked(Context context,SharedPreferences prefs,Uri tree) throws Exception {
        try { return loadFile(context,prefs,linkedFile(context.getContentResolver(),tree),NAME); }
        catch(SecurityException e) {
            throw new IOException("Access to "+LOCATION+" expired. Use Link FM settings folder again.",e);
        }
    }

    private static Loaded loadFile(Context context,SharedPreferences prefs,Uri uri,String name) throws Exception {
        ContentResolver resolver=context.getContentResolver();
        byte[] contents;
        try(InputStream in=resolver.openInputStream(uri)) { contents=readBytes(in); }
        JSONObject document=parseDocument(contents);
        JSONObject fields=document.getJSONObject("settings");
        String[] values=decode(fields,cached(prefs));
        String warning="";
        if(upgradeSettingsFields(fields,values)) {
            try {
                byte[] bytes=encode(document);
                if("file".equals(uri.getScheme())) writeLegacy(bytes);
                else writeBytes(resolver,uri,bytes);
                contents=bytes;
                refreshFileMetadata(context,name);
            } catch(IOException|SecurityException failure) {
                warning="Could not update settings in "+PATH+name+" · "+failure.getMessage();
            }
        }
        return new Loaded(new Snapshot(values,fields.opt(VERSION_KEY)),PATH+name,warning,contents);
    }

    private static byte[] readBytes(InputStream in) throws IOException {
        if(in==null) throw new IOException("Cannot open "+LOCATION);
        ByteArrayOutputStream data=new ByteArrayOutputStream();
        byte[] buffer=new byte[4096];
        for(int n;(n=in.read(buffer))!=-1;) {
            if(data.size()+n>MAX_BYTES) throw new IOException("Settings file exceeds 1 MB");
            data.write(buffer,0,n);
        }
        return data.toByteArray();
    }

    static JSONObject parseDocument(byte[] bytes) throws Exception {
        JSONObject json=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(json.optInt("schema")!=1) throw new IOException("Unsupported FM settings format");
        return json;
    }

    private static final int MAX_BYTES=1024*1024;

    static Snapshot readSnapshot(JSONObject fields,String[] previous) throws Exception {
        return new Snapshot(decode(fields,previous),fields.opt(VERSION_KEY));
    }

    static String[] decode(JSONObject fields,String[] previous) throws Exception {
        String[] values=previous.clone();
        for(int i=0;i<values.length;i++) if(fields.has(FmService.KEYS[i])) {
            Object entry=fields.get(FmService.KEYS[i]);
            if(!(entry instanceof String) && !(entry instanceof Number)
                && !((i==FmService.TILT_DISABLE || i==FmService.MANUAL_CAMERA) && entry instanceof Boolean)
                && !(i==CameraSettings.BANDS && entry instanceof JSONObject)
                && !((i==CameraSettings.MAPPING || i==CameraSettings.SCALES) && entry instanceof JSONArray))
                throw new IOException("Invalid setting: "+FmService.KEYS[i]);
            values[i]=entry.toString();
        }
        if(fields.has(MountCoordinates.KEY)) MountCoordinates.apply(fields.getString(MountCoordinates.KEY),values);
        if(fields.has(LandmarkCalibration.COORDINATES))
            MountCoordinates.apply(fields.getString(LandmarkCalibration.COORDINATES),values,
                LandmarkCalibration.LANDMARK_LAT,LandmarkCalibration.LANDMARK_LON,LandmarkCalibration.COORDINATES);
        FmService.hsGapWarningM(values);
        FmService.validateLiveSettings(values);
        new CameraSettings(values);
        LandmarkCalibration.clearStaleReference(previous,values);
        return values;
    }

    /** Merge only fields changed by this operation into the most recent valid external file. */
    static String[] mergeEdit(String[] base,String[] edited,String[] disk) {
        String[] merged=disk.clone();
        for(int i=0;i<merged.length;i++) if(!java.util.Objects.equals(base[i],edited[i])) merged[i]=edited[i];
        LandmarkCalibration.clearStaleReference(disk,merged);
        return merged;
    }

    static void requireUnchanged(String[] started,String[] saved,int... fields) throws IOException {
        for(int field:fields) if(!started[field].equals(saved[field]))
            throw new IOException(FmService.KEYS[field]+" changed during the operation. Keeping the edited value.");
    }

    private static Object fileValue(int index,String value) throws Exception {
        if(index==FmService.TILT_DISABLE || index==FmService.MANUAL_CAMERA) return Boolean.valueOf(value);
        if(index==CameraSettings.BANDS) return new JSONObject(value);
        if(index==CameraSettings.MAPPING) return new JSONArray(value);
        if(index==CameraSettings.SCALES) return new JSONArray(ZoomPlan.parse(value,true));
        return value;
    }

    // Keep existing values and unrelated keys when upgrading an older public file.
    static boolean upgradeSettingsFields(JSONObject fields,String[] values) throws Exception {
        // Live Tracking now starts at maximum and selects restart speed from range.
        boolean changed=fields.remove("Live HS pan speed (× sidereal)")!=null;
        if(!fields.has(VERSION_KEY)) {
            fields.put(VERSION_KEY,"");
            changed=true;
        }
        for(int i=0;i<values.length;i++) {
            if(i!=6 && i!=7 && i!=FmService.TILT_DISABLE && i<CameraSettings.FIRST) continue;
            String key=FmService.KEYS[i];
            boolean table=i==CameraSettings.BANDS || i==CameraSettings.MAPPING || i==CameraSettings.SCALES;
            if(!fields.has(key) || (table && fields.get(key) instanceof String)) {
                fields.put(key,fileValue(i,values[i]));
                changed=true;
            }
        }
        return changed;
    }

    private static byte[] encode(JSONObject document) throws Exception {
        byte[] bytes=(document.toString(2)+"\n").getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES) throw new IOException("Settings file exceeds 1 MB");
        return bytes;
    }

    private static void refreshFileMetadata(Context context,String name) {
        File path=new File(legacy().getParentFile(),name);
        MediaScannerConnection.scanFile(context,new String[]{path.getPath()},null,null);
    }

    static JSONObject settingsDocument(String[] values) throws Exception {
        JSONObject settings=new JSONObject();
        for(int i=0;i<values.length;i++) {
            if(i==MountCoordinates.LATITUDE) settings.put(MountCoordinates.KEY,MountCoordinates.format(values));
            else if(i==LandmarkCalibration.LANDMARK_LAT)
                settings.put(LandmarkCalibration.COORDINATES,
                    MountCoordinates.format(values,LandmarkCalibration.LANDMARK_LAT,LandmarkCalibration.LANDMARK_LON));
            else if(i!=MountCoordinates.LONGITUDE&&i!=LandmarkCalibration.LANDMARK_LON)
                settings.put(FmService.KEYS[i],fileValue(i,values[i]));
        }
        return new JSONObject().put("schema",1).put("settings",settings);
    }

    static byte[] write(Context context,SharedPreferences prefs,Snapshot snapshot) throws Exception {
        byte[] bytes=encode(snapshot.document());
        ContentResolver resolver=context.getContentResolver();
        String tree=prefs.getString(TREE,"");
        if(!tree.isEmpty()) {
            try { writeBytes(resolver,linkedFile(resolver,Uri.parse(tree)),bytes); refreshFileMetadata(context,NAME); }
            catch(SecurityException e) {
                throw new IOException("Access to "+LOCATION+" expired. Use Link FM settings folder again.",e);
            }
            return bytes;
        }
        if(Build.VERSION.SDK_INT<29) {
            writeLegacy(bytes);
            refreshFileMetadata(context,NAME);
            return bytes;
        }
        DownloadRow found=find(resolver,prefs);
        Uri row=found==null?null:found.uri();
        boolean created=row==null;
        if(created) {
            ContentValues info=new ContentValues();
            info.put(MediaStore.MediaColumns.DISPLAY_NAME,NAME);
            info.put(MediaStore.MediaColumns.RELATIVE_PATH,PATH);
            info.put(MediaStore.MediaColumns.MIME_TYPE,"application/json");
            info.put(MediaStore.MediaColumns.IS_PENDING,1);
            row=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,info);
            if(row==null) throw new IOException("Android could not create "+LOCATION);
        }
        try {
            if(created) {
                DownloadRow pending=describe(resolver,row);
                if(pending==null||!NAME.equals(pending.name()))
                    throw new IOException(LOCATION+" already exists or is inaccessible. Use Link FM settings folder.");
            }
            writeBytes(resolver,row,bytes);
            if(created) {
                ContentValues published=new ContentValues();
                published.put(MediaStore.MediaColumns.IS_PENDING,0);
                if(resolver.update(row,published,null,null)!=1) throw new IOException("Could not publish settings file");
            }
            prefs.edit().putString(ROW,row.toString()).apply();
            refreshFileMetadata(context,found==null?NAME:found.name());
        } catch(Exception e) {
            if(created) resolver.delete(row,null,null);
            throw e;
        }
        return bytes;
    }

    private static void writeLegacy(byte[] bytes) throws IOException {
        File destination=legacy();
        File dir=destination.getParentFile();
        if(!dir.isDirectory()&&!dir.mkdirs()) throw new IOException("Cannot create the folder for "+LOCATION);
        File staging=File.createTempFile("FM-settings-",null,dir);
        try {
            try(FileOutputStream out=new FileOutputStream(staging)) { out.write(bytes); }
            Files.move(staging.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING);
        } finally { if(staging.exists()) staging.delete(); }
    }

    private static void writeBytes(ContentResolver resolver,Uri row,byte[] bytes) throws IOException {
        try(OutputStream out=resolver.openOutputStream(row,"wt")) {
            if(out==null) throw new IOException("Cannot write "+LOCATION);
            out.write(bytes);
        }
    }

    private static DownloadRow find(ContentResolver resolver,SharedPreferences prefs) {
        try(Cursor c=resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,new String[]{MediaStore.MediaColumns._ID},
            MediaStore.MediaColumns.DISPLAY_NAME+"=? AND "+MediaStore.MediaColumns.RELATIVE_PATH+"=?",
            new String[]{NAME,PATH},null)) {
            if(c!=null&&c.moveToFirst())
                return new DownloadRow(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,c.getLong(0)),NAME);
        }
        String stored=prefs.getString(ROW,"");
        return stored.isEmpty()?null:describe(resolver,Uri.parse(stored));
    }

    private static DownloadRow describe(ContentResolver resolver,Uri row) {
        try(Cursor c=resolver.query(row,new String[]{MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.RELATIVE_PATH},
            null,null,null)) {
            if(c!=null&&c.moveToFirst()) {
                String name=c.getString(0);
                if(name!=null&&name.matches(GENERATED_NAME)&&PATH.equals(c.getString(1))) return new DownloadRow(row,name);
            }
        } catch(SecurityException ignored) { }
        return null;
    }

    private static File legacy() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),FOLDER+"/"+NAME);
    }
}

