package au.com.gildo.fm;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

final class SettingsFile {
    static final String NAME="FM-settings.json";
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

    record Loaded(String[] values,String location) {}
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
        ContentResolver resolver=context.getContentResolver();
        String tree=prefs.getString(TREE,"");
        if(!tree.isEmpty())
            return new Loaded(readLinked(resolver,prefs,Uri.parse(tree)),LOCATION);
        if(Build.VERSION.SDK_INT<29) {
            File file=legacy();
            if(!file.exists()) return null;
            try(InputStream in=new FileInputStream(file)) { return new Loaded(decode(in,prefs),LOCATION); }
        }
        DownloadRow row=find(resolver,prefs);
        if(row==null) return null;
        try(InputStream in=resolver.openInputStream(row.uri())) {
            return new Loaded(decode(in,prefs),PATH+row.name());
        }
    }

    static String[] linkFolder(Context context,SharedPreferences prefs,Uri tree,int resultFlags) throws Exception {
        requireFmFolder(tree);
        if((resultFlags&ACCESS)!=ACCESS)
            throw new IOException("Allow read and write access to the Download/FM folder");
        ContentResolver resolver=context.getContentResolver();
        resolver.takePersistableUriPermission(tree,ACCESS);
        boolean alreadyLinked=tree.toString().equals(prefs.getString(TREE,""));
        try {
            String[] values=readLinked(resolver,prefs,tree);
            if(!prefs.edit().putString(TREE,tree.toString()).commit())
                throw new IOException("Could not remember the FM settings folder");
            return values;
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

    private static String[] readLinked(ContentResolver resolver,SharedPreferences prefs,Uri tree) throws Exception {
        try(InputStream in=resolver.openInputStream(linkedFile(resolver,tree))) { return decode(in,prefs); }
        catch(SecurityException e) {
            throw new IOException("Access to "+LOCATION+" expired. Use Link FM settings folder again.",e);
        }
    }

    private static String[] decode(InputStream in,SharedPreferences prefs) throws Exception {
        if(in==null) throw new IOException("Cannot open "+LOCATION);
        ByteArrayOutputStream data=new ByteArrayOutputStream();
        byte[] buffer=new byte[4096];
        for(int n;(n=in.read(buffer))!=-1;) {
            if(data.size()+n>65536) throw new IOException("Settings file exceeds 64 KB");
            data.write(buffer,0,n);
        }
        JSONObject json=new JSONObject(new String(data.toByteArray(),StandardCharsets.UTF_8));
        if(json.optInt("schema")!=1) throw new IOException("Unsupported FM settings format");
        JSONObject fields=json.getJSONObject("settings");
        String[] previous=cached(prefs);
        String[] values=previous.clone();
        for(int i=0;i<values.length;i++) if(fields.has(FmService.KEYS[i])) {
            Object entry=fields.get(FmService.KEYS[i]);
            if(!(entry instanceof String) && !(entry instanceof Number))
                throw new IOException("Invalid setting: "+FmService.KEYS[i]);
            values[i]=entry.toString();
        }
        if(fields.has(MountCoordinates.KEY)) MountCoordinates.apply(fields.getString(MountCoordinates.KEY),values);
        if(fields.has(LandmarkCalibration.COORDINATES))
            MountCoordinates.apply(fields.getString(LandmarkCalibration.COORDINATES),values,
                LandmarkCalibration.LANDMARK_LAT,LandmarkCalibration.LANDMARK_LON,LandmarkCalibration.COORDINATES);
        FmService.hsGapWarningM(values);
        FmService.validateLiveSettings(values);
        LandmarkCalibration.clearStaleReference(previous,values);
        return values;
    }

    static void write(Context context,SharedPreferences prefs,String[] values) throws Exception {
        JSONObject settings=new JSONObject();
        for(int i=0;i<values.length;i++) {
            if(i==MountCoordinates.LATITUDE) settings.put(MountCoordinates.KEY,MountCoordinates.format(values));
            else if(i==LandmarkCalibration.LANDMARK_LAT)
                settings.put(LandmarkCalibration.COORDINATES,
                    MountCoordinates.format(values,LandmarkCalibration.LANDMARK_LAT,LandmarkCalibration.LANDMARK_LON));
            else if(i!=MountCoordinates.LONGITUDE&&i!=LandmarkCalibration.LANDMARK_LON)
                settings.put(FmService.KEYS[i],values[i]);
        }
        byte[] bytes=(new JSONObject().put("schema",1).put("settings",settings).toString(2)+"\n")
            .getBytes(StandardCharsets.UTF_8);
        ContentResolver resolver=context.getContentResolver();
        String tree=prefs.getString(TREE,"");
        if(!tree.isEmpty()) {
            try { writeBytes(resolver,linkedFile(resolver,Uri.parse(tree)),bytes); }
            catch(SecurityException e) {
                throw new IOException("Access to "+LOCATION+" expired. Use Link FM settings folder again.",e);
            }
            return;
        }
        if(Build.VERSION.SDK_INT<29) {
            File destination=legacy();
            File dir=destination.getParentFile();
            if(!dir.isDirectory()&&!dir.mkdirs()) throw new IOException("Cannot create the folder for "+LOCATION);
            File staging=File.createTempFile("FM-settings-",null,dir);
            try {
                try(FileOutputStream out=new FileOutputStream(staging)) { out.write(bytes); }
                Files.move(staging.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING);
            } finally { if(staging.exists()) staging.delete(); }
            return;
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
        } catch(Exception e) {
            if(created) resolver.delete(row,null,null);
            throw e;
        }
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

