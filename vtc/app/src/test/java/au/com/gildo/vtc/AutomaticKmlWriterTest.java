package au.com.gildo.vtc;
import org.junit.Test;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

public class AutomaticKmlWriterTest {
    private File spool()throws IOException{return Files.createTempDirectory("vtc-kml-test").resolve("session.kml").toFile();}
    private FileChannel channel(File file)throws IOException{return new RandomAccessFile(file,"rw").getChannel();}
    private String read(File file)throws IOException{return new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8);}
    private void await(BooleanSupplier condition)throws Exception {
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()&&System.nanoTime()<limit)Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
    @Test public void publicKmlIsValidWhileSessionRemainsOpenAndKeepsItsClosingTags()throws Exception {
        File local=spool(),shared=new File(local.getParentFile(),"public.kml");
        AutomaticKmlWriter kml=new AutomaticKmlWriter(local,()->channel(shared),"test");
        try {
            kml.append(KmlDocumentTest.event("settings_snapshot",1000,"settings",KmlDocumentTest.settings()));
            kml.append(KmlDocumentTest.event("gps_accepted",1500,"telemetry",KmlDocumentTest.gps(1,-28,153)));
            await(()->{try{return KmlDocumentTest.parse(read(shared)).getElementsByTagName("Placemark").getLength()==2;}catch(Exception e){return false;}});
            assertTrue(kml.publicReady());assertFalse(kml.finished());
            kml.append(KmlDocumentTest.event("gps_accepted",2000,"telemetry",KmlDocumentTest.gps(2,-28.001,153)));
        }finally{kml.close();assertTrue(kml.awaitClosed(3000));}
        assertEquals(3,KmlDocumentTest.parse(read(shared)).getElementsByTagName("Placemark").getLength());
        assertEquals(read(local),read(shared));assertTrue(read(shared).endsWith(KmlDocument.FOOTER));
    }
    @Test public void permissionFailureRetriesAndBackfillsCompleteDocument()throws Exception {
        File local=spool(),shared=new File(local.getParentFile(),"public.kml");AtomicInteger attempts=new AtomicInteger();
        AutomaticKmlWriter kml=new AutomaticKmlWriter(local,()->{
            if(attempts.incrementAndGet()==1)throw new IOException("permission pending");return channel(shared);
        },"test",1024,20);
        kml.append(KmlDocumentTest.event("settings_snapshot",1000,"settings",KmlDocumentTest.settings()));
        kml.append(KmlDocumentTest.event("gps_accepted",1500,"telemetry",KmlDocumentTest.gps(1,-28,153)));
        kml.close();assertTrue(kml.awaitClosed(3000));assertTrue(attempts.get()>=2);assertTrue(kml.publicReady());
        assertEquals(read(local),read(shared));assertEquals(2,KmlDocumentTest.parse(read(shared)).getElementsByTagName("Placemark").getLength());
    }
    @Test public void publicWriteFailureRebuildsXmlWithoutDuplicatePlacemarks()throws Exception {
        File local=spool(),shared=new File(local.getParentFile(),"public.kml");AtomicInteger attempts=new AtomicInteger();
        AtomicReference<FileChannel> first=new AtomicReference<>();
        AutomaticKmlWriter kml=new AutomaticKmlWriter(local,()->{
            FileChannel out=channel(shared);if(attempts.incrementAndGet()==1)first.set(out);return out;
        },"test",1024,20);
        await(kml::publicReady);first.get().close();
        kml.append(KmlDocumentTest.event("settings_snapshot",1000,"settings",KmlDocumentTest.settings()));
        kml.close();assertTrue(kml.awaitClosed(3000));assertTrue(attempts.get()>=2);assertTrue(kml.publicReady());
        assertEquals(read(local),read(shared));assertEquals(1,KmlDocumentTest.parse(read(shared)).getElementsByTagName("Placemark").getLength());
    }
    @Test public void persistentFailureKeepsValidPrivateRecoveryDocument()throws Exception {
        File local=spool();AutomaticKmlWriter kml=new AutomaticKmlWriter(local,()->{throw new IOException("full");},"test",1024,20);
        kml.append(KmlDocumentTest.event("gps_accepted",1000,"telemetry",KmlDocumentTest.gps(1,-28,153)));
        kml.close();assertTrue(kml.awaitClosed(3000));assertFalse(kml.publicReady());assertTrue(kml.error().contains("full"));
        assertEquals(1,KmlDocumentTest.parse(read(local)).getElementsByTagName("Placemark").getLength());
    }
    @Test public void slowStorageDoesNotBlockProducersAndDroppedEventsAreMarked()throws Exception {
        File local=spool(),shared=new File(local.getParentFile(),"public.kml");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AutomaticKmlWriter kml=new AutomaticKmlWriter(local,()->{
            entered.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new IOException("test timeout");}
            catch(InterruptedException e){throw new IOException(e);}return channel(shared);
        },"test",2,20);
        try {
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            for(int i=1;i<=3;i++)kml.append(KmlDocumentTest.event("gps_accepted",1000+i,"telemetry",KmlDocumentTest.gps(i,-28,153)));
            assertEquals(1,kml.dropped());
        }finally{release.countDown();kml.close();assertTrue(kml.awaitClosed(3000));}
        assertTrue(read(shared).contains("KML logging gap"));KmlDocumentTest.parse(read(shared));
    }
}
