package au.com.gildo.vtc;
import java.io.*;
import java.util.*;
/** Tested file identity and write verification policy, independent of Android. */
final class SettingsFileIO {
    static final class Entry {final String id,name;Entry(String id,String name){this.id=id;this.name=name;}}
    interface Backend {
        List<Entry> list()throws IOException;
        Entry lookup(String id)throws IOException;
        Entry rename(Entry entry,String name)throws IOException;
        Entry create(String name)throws IOException;
        void remember(Entry entry)throws IOException;
        InputStream read(Entry entry)throws IOException;
        OutputStream write(Entry entry)throws IOException;
    }
    static Entry resolve(Backend b,String name,String remembered,boolean create)throws IOException {
        List<Entry> entries=b.list();
        // Current filename wins over any saved identifier, including a deleted/replaced file.
        for(Entry e:entries)if(name.equals(e.name)){b.remember(e);return e;}
        Entry candidate=null;
        if(remembered!=null){Entry e=b.lookup(remembered);if(e!=null&&SettingsFileNames.matches(name,e.name))candidate=e;}
        if(candidate==null)for(Entry e:entries)if(SettingsFileNames.matches(name,e.name)){candidate=e;break;}
        if(candidate!=null){
            Entry e=name.equals(candidate.name)?candidate:b.rename(candidate,name);
            b.remember(e);
            if(!name.equals(e.name))throw new IOException("Android did not assign "+name+". Allow Settings folder access.");
            return e;
        }
        if(!create)return null;
        Entry e=b.create(name);b.remember(e);
        if(!name.equals(e.name))throw new IOException("A settings file already exists outside VTC access. Allow Settings folder access; no further copies will be created.");
        return e;
    }
    static byte[] bytes(InputStream in)throws IOException {
        if(in==null)throw new IOException("Cannot open settings file");
        try(InputStream stream=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int count;
            while((count=stream.read(buffer))!=-1){out.write(buffer,0,count);if(out.size()>131072)throw new IOException("Settings file too large");}
            return out.toByteArray();
        }
    }
    static void writeVerified(Backend b,Entry entry,byte[] bytes)throws IOException {
        try(OutputStream out=b.write(entry)){if(out==null)throw new IOException("Cannot open settings for writing");out.write(bytes);out.flush();}
        if(!Arrays.equals(bytes,bytes(b.read(entry))))throw new IOException("Settings readback did not match the saved data");
    }
}
