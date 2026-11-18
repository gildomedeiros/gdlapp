package au.com.gildo.vtc;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class SettingsFileIOTest {
    private static byte[] data(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static final class Fake implements SettingsFileIO.Backend {
        final Map<String,SettingsFileIO.Entry> entries=new LinkedHashMap<>();
        final Map<String,byte[]> content=new HashMap<>();
        String remembered,lookupId;int creates,renames,writes;
        boolean denyList,denyWrite,denyRename,corruptWrite,failClose,hideListing,collision;
        SettingsFileIO.Entry put(String id,String name,String text){SettingsFileIO.Entry e=new SettingsFileIO.Entry(id,name);entries.put(id,e);content.put(id,data(text));return e;}
        public List<SettingsFileIO.Entry> list()throws IOException{if(denyList)throw new IOException("Permission denied");return hideListing?Collections.emptyList():new ArrayList<>(entries.values());}
        public SettingsFileIO.Entry lookup(String id){lookupId=id;return entries.get(id);}
        public SettingsFileIO.Entry rename(SettingsFileIO.Entry e,String name)throws IOException{
            if(denyRename)throw new IOException("Rename denied");renames++;
            SettingsFileIO.Entry renamed=new SettingsFileIO.Entry(e.id,name);entries.put(e.id,renamed);return renamed;
        }
        public SettingsFileIO.Entry create(String name){creates++;return put("new-"+creates,collision?"setup (1).json":name,"");}
        public void remember(SettingsFileIO.Entry e){remembered=e.id;}
        public InputStream read(SettingsFileIO.Entry e)throws IOException{if(!entries.containsKey(e.id))throw new FileNotFoundException(e.id);return new ByteArrayInputStream(content.get(e.id));}
        public OutputStream write(SettingsFileIO.Entry e)throws IOException{
            if(denyWrite)throw new IOException("Write denied");
            if(!entries.containsKey(e.id))throw new FileNotFoundException(e.id);
            writes++;
            return new ByteArrayOutputStream(){@Override public void close()throws IOException{
                if(failClose)throw new IOException("Provider failed to commit");
                content.put(e.id,corruptWrite?data("old data"):toByteArray());super.close();
            }};
        }
    }
    private static void save(Fake b,String text)throws IOException{
        SettingsFileIO.Entry e=SettingsFileIO.resolve(b,"setup.json",b.remembered,true);
        SettingsFileIO.writeVerified(b,e,data(text));
    }
    @Test public void replacedFileIsRediscoveredAndWrittenInsteadOfStaleUri()throws Exception{
        Fake b=new Fake();b.remembered="deleted";b.put("replacement","setup.json","old coordinates");
        save(b,"new coordinates");
        assertEquals("replacement",b.remembered);assertNull(b.lookupId);assertEquals(0,b.creates);
        assertArrayEquals(data("new coordinates"),b.content.get("replacement"));
    }
    @Test public void externallyRenamedNumberedFileKeepsItsIdentity()throws Exception{
        Fake b=new Fake();b.remembered="24";b.put("24","setup.json","before rename");
        save(b,"after coordinate edit");
        assertEquals("24",b.remembered);assertEquals(0,b.creates);assertEquals(0,b.renames);
    }
    @Test public void currentCanonicalFileWinsOverOldNumberedReference()throws Exception{
        Fake b=new Fake();b.remembered="24";b.put("24","setup (24).json","old numbered");b.put("current","setup.json","current");
        save(b,"new value");assertEquals("current",b.remembered);assertEquals(0,b.creates);
        assertArrayEquals(data("old numbered"),b.content.get("24"));
    }
    @Test public void repeatedSavesAndServiceRestartsNeverCreateMoreFiles()throws Exception{
        Fake b=new Fake();save(b,"first");
        for(int i=0;i<40;i++){b.remembered=null;save(b,"coordinate-"+i);}
        assertEquals(1,b.creates);assertEquals(1,b.entries.size());assertArrayEquals(data("coordinate-39"),b.content.get("new-1"));
    }
    @Test public void onlyNumberedActiveFileIsRenamedInPlace()throws Exception{
        Fake b=new Fake();b.remembered="24";b.put("24","setup (24).json","coordinates");
        b.put("backup","setup-before-1.0.17.json","backup");b.put("landmark","last-landmark.json","motor bookmark");
        save(b,"edit");assertEquals(0,b.creates);assertEquals(1,b.renames);assertEquals("setup.json",b.entries.get("24").name);
        assertArrayEquals(data("backup"),b.content.get("backup"));assertArrayEquals(data("motor bookmark"),b.content.get("landmark"));
    }
    @Test public void deniedDiscoveryDoesNotTryInsertOrUseUnverifiedOldUri()throws Exception{
        Fake b=new Fake();b.denyList=true;b.remembered="old";
        assertThrows(IOException.class,()->save(b,"edit"));assertEquals(0,b.creates);assertEquals(0,b.writes);assertNull(b.lookupId);
    }
    @Test public void deniedWriteDoesNotReplaceExistingFile()throws Exception{
        Fake b=new Fake();b.put("current","setup.json","original");b.denyWrite=true;
        assertThrows(IOException.class,()->save(b,"edit"));assertEquals(0,b.creates);assertArrayEquals(data("original"),b.content.get("current"));
        b.denyWrite=false;save(b,"edit");assertEquals(0,b.creates);assertArrayEquals(data("edit"),b.content.get("current"));
    }
    @Test public void deniedRenameDoesNotCreateAnotherNumberedFile()throws Exception{
        Fake b=new Fake();b.put("24","setup (24).json","original");b.denyRename=true;
        assertThrows(IOException.class,()->save(b,"edit"));assertEquals(0,b.creates);assertEquals(0,b.writes);
    }
    @Test public void successfulWriteWithWrongReadbackIsFailure()throws Exception{
        Fake b=new Fake();b.put("current","setup.json","old data");b.corruptWrite=true;
        IOException e=assertThrows(IOException.class,()->save(b,"new data"));assertTrue(e.getMessage().contains("readback"));assertEquals(0,b.creates);
        b.corruptWrite=false;save(b,"new data");assertArrayEquals(data("new data"),b.content.get("current"));
    }
    @Test public void closeFailureIsNotReportedAsSaved()throws Exception{
        Fake b=new Fake();b.put("current","setup.json","old");b.failClose=true;
        assertThrows(IOException.class,()->save(b,"new"));assertArrayEquals(data("old"),b.content.get("current"));
    }
    @Test public void deletionAfterResolutionDoesNotCreateAReplacement()throws Exception{
        Fake b=new Fake();b.put("current","setup.json","old");
        SettingsFileIO.Entry e=SettingsFileIO.resolve(b,"setup.json",null,true);b.entries.remove("current");
        assertThrows(IOException.class,()->SettingsFileIO.writeVerified(b,e,data("new")));assertEquals(0,b.creates);
    }
    @Test public void failedReadOnlyDiscoveryNeverCreatesFile()throws Exception{
        Fake b=new Fake();assertNull(SettingsFileIO.resolve(b,"setup.json","deleted",false));assertEquals(0,b.creates);
    }
    @Test public void savedIdentityMayRecoverEntryMissingFromCollection()throws Exception{
        Fake b=new Fake();b.hideListing=true;b.remembered="existing";b.put("existing","setup.json","old");
        save(b,"new");assertEquals(0,b.creates);assertArrayEquals(data("new"),b.content.get("existing"));
    }
    @Test public void unexpectedCollisionIsRememberedAndFailsBeforeWrite()throws Exception{
        Fake b=new Fake();b.collision=true;
        assertThrows(IOException.class,()->save(b,"new"));assertEquals(1,b.creates);assertEquals("new-1",b.remembered);assertEquals(0,b.writes);
    }
    @Test public void unrelatedRememberedFileIsNeverOverwritten()throws Exception{
        Fake b=new Fake();b.remembered="bookmark";b.put("bookmark","last-landmark.json","motor position");
        assertNull(SettingsFileIO.resolve(b,"setup.json",b.remembered,false));assertEquals(0,b.creates);assertEquals(0,b.renames);
    }
    @Test public void smallerSecondSaveReplacesAllPreviousBytes()throws Exception{
        Fake b=new Fake();save(b,"{\"latitude\":\"-28.07917000\",\"longitude\":\"153.40100002\"}");
        String text="{\"latitude\":\"\"}";save(b,text);assertEquals(1,b.creates);assertArrayEquals(data(text),b.content.get(b.remembered));
    }
}
