package au.com.gildo.vtc;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import java.io.*;
import java.util.*;
/** Adapter for MediaStore app-owned files or a user-granted Settings directory. */
final class AndroidSettingsFiles implements SettingsFileIO.Backend {
    static final String DIR="Download/VTC/Settings/",FOLDER_PREFS="vtc_settings_folder";
    private final Context context;private final ContentResolver resolver;private final SharedPreferences location;
    private final String name;private final Uri tree;private final SessionLog log;
    AndroidSettingsFiles(Context c,SharedPreferences location,String name,SessionLog log){context=c;resolver=c.getContentResolver();this.location=location;this.name=name;this.log=log;String t=c.getSharedPreferences(FOLDER_PREFS,0).getString("uri",null);tree=t==null?null:Uri.parse(t);}
    private static String path(String p){return p==null?"":p.replaceAll("/+$","");}
    private Uri parent(){return DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));}
    private File dir(){return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"VTC/Settings");}
    public List<SettingsFileIO.Entry> list()throws IOException {
        List<SettingsFileIO.Entry> entries=new ArrayList<>();
        try{
            if(Build.VERSION.SDK_INT<29&&tree==null){File[] files=dir().listFiles();if(files!=null)for(File f:files)entries.add(new SettingsFileIO.Entry(f.toURI().toString(),f.getName()));return entries;}
            if(tree!=null){
                Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
                try(Cursor c=resolver.query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
                    if(c==null)throw new IOException("Cannot read Settings folder; allow access again");
                    while(c.moveToNext())entries.add(new SettingsFileIO.Entry(DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0)).toString(),c.getString(1)));
                }
            }else{
                // Files includes JSON documents even if a device's Downloads view omits them.
                Uri collection=MediaStore.Files.getContentUri("external");
                try(Cursor c=resolver.query(collection,new String[]{MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.RELATIVE_PATH},
                    MediaStore.MediaColumns.RELATIVE_PATH+" IN (?,?)",new String[]{DIR,path(DIR)},MediaStore.MediaColumns._ID+" DESC")){
                    if(c==null)throw new IOException("Cannot discover Settings folder");
                    while(c.moveToNext())if(path(DIR).equals(path(c.getString(2))))entries.add(new SettingsFileIO.Entry(ContentUris.withAppendedId(collection,c.getLong(0)).toString(),c.getString(1)));
                }
            }
            return entries;
        }catch(SecurityException e){throw new IOException("Android denied Settings access. Tap Allow Settings folder access.",e);}
    }
    public SettingsFileIO.Entry lookup(String id)throws IOException{
        Uri uri=Uri.parse(id);
        if(tree!=null)return null; // Folder listing is authoritative; no obsolete grants.
        if(Build.VERSION.SDK_INT<29){File f=new File(java.net.URI.create(id));return f.isFile()?new SettingsFileIO.Entry(id,f.getName()):null;}
        if(!"media".equals(uri.getAuthority()))return null;
        try(Cursor c=resolver.query(uri,new String[]{MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.RELATIVE_PATH},null,null,null)){
            if(c==null)throw new IOException("Cannot verify old settings reference; no replacement will be created");
            return c.moveToFirst()&&path(DIR).equals(path(c.getString(1)))?new SettingsFileIO.Entry(id,c.getString(0)):null;
        }catch(SecurityException e){throw new IOException("Old settings reference denied. Allow Settings folder access.",e);}
    }
    private SettingsFileIO.Entry metadata(Uri uri)throws IOException {
        try(Cursor c=resolver.query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c==null||!c.moveToFirst())throw new IOException("Cannot verify settings filename");return new SettingsFileIO.Entry(uri.toString(),c.getString(0));
        }
    }
    public SettingsFileIO.Entry rename(SettingsFileIO.Entry entry,String name)throws IOException{
        try{
            if(Build.VERSION.SDK_INT<29&&tree==null){File f=new File(java.net.URI.create(entry.id)),next=new File(dir(),name);if(next.exists()||!f.renameTo(next))throw new IOException("Cannot rename settings");return new SettingsFileIO.Entry(next.toURI().toString(),name);}
            Uri uri=Uri.parse(entry.id);
            if(tree!=null){uri=DocumentsContract.renameDocument(resolver,uri,name);if(uri==null)throw new IOException("Cannot rename settings");}
            else{ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);if(resolver.update(uri,values,null,null)!=1)throw new IOException("Cannot rename settings. Allow Settings folder access.");}
            return metadata(uri);
        }catch(SecurityException e){throw new IOException("Cannot rename settings. Allow Settings folder access.",e);}
    }
    public SettingsFileIO.Entry create(String name)throws IOException {
        try{
            if(tree!=null){Uri uri=DocumentsContract.createDocument(resolver,parent(),"application/json",name);if(uri==null)throw new IOException("Cannot create settings in granted folder");return metadata(uri);}
            if(Build.VERSION.SDK_INT<29){File f=new File(dir(),name);if(!dir().isDirectory()&&!dir().mkdirs())throw new IOException("Cannot create Settings directory");if(!f.createNewFile())throw new IOException("Settings file already exists");return new SettingsFileIO.Entry(f.toURI().toString(),name);}
            if(location.getBoolean("creation_attempted",false)||location.contains("uri")||new File(dir(),name).exists())throw new IOException("Cannot locate the existing settings file. Tap Allow Settings folder access; no new copy created.");
            if(!location.edit().putBoolean("creation_attempted",true).commit())throw new IOException("Cannot retain settings creation marker");
            ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.RELATIVE_PATH,DIR);values.put(MediaStore.MediaColumns.MIME_TYPE,"application/json");
            Uri uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IOException("Cannot create settings file");
            remember(new SettingsFileIO.Entry(uri.toString(),name));return metadata(uri);
        }catch(SecurityException e){throw new IOException("Android denied writing settings. Allow Settings folder access.",e);}
    }
    public void remember(SettingsFileIO.Entry entry)throws IOException{if(!location.edit().putString("uri",entry.id).putString("name",entry.name).commit())throw new IOException("Cannot retain settings location");log.record("settings_file_resolved","requestedName",name,"actualName",entry.name,"uri",entry.id,"folderGrant",tree!=null);}
    public InputStream read(SettingsFileIO.Entry entry)throws IOException{return entry.id.startsWith("file:")?new FileInputStream(new File(java.net.URI.create(entry.id))):resolver.openInputStream(Uri.parse(entry.id));}
    public OutputStream write(SettingsFileIO.Entry entry)throws IOException{return entry.id.startsWith("file:")?new FileOutputStream(new File(java.net.URI.create(entry.id))):resolver.openOutputStream(Uri.parse(entry.id),"wt");}
}
