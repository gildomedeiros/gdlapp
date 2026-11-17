package au.com.gildo.vtc;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class AutomaticLogWriterTest {
    private String read(Path path)throws IOException{return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}
    private File spool()throws IOException{return Files.createTempDirectory("vtc-log-test").resolve("session.jsonl").toFile();}
    @Test public void writesPublicFileWhileRunningWithoutSaveOrClose()throws Exception {
        File local=spool(),publicFile=new File(local.getParentFile(),"public.jsonl");
        AutomaticLogWriter log=new AutomaticLogWriter(local,()->new FileOutputStream(publicFile));
        try {
            log.append("{\"event\":\"waiting_for_calibration\"}");
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while((!publicFile.exists()||publicFile.length()==0)&&System.nanoTime()<end)Thread.sleep(10);
            assertTrue(log.publicReady());assertFalse(log.finished());
            assertEquals("{\"event\":\"waiting_for_calibration\"}\n",read(publicFile.toPath()).replace("\r\n","\n"));
        }finally{log.close();assertTrue(log.awaitClosed(3000));}
    }
    @Test public void closingDrainsAllAcceptedEventsInOrder()throws Exception {
        File local=spool();ByteArrayOutputStream publicBytes=new ByteArrayOutputStream();
        AutomaticLogWriter log=new AutomaticLogWriter(local,()->publicBytes);
        StringBuilder expected=new StringBuilder();
        for(int i=0;i<200;i++){String row="{\"record\":"+i+"}";log.append(row);expected.append(row).append(System.lineSeparator());}
        log.close();assertTrue(log.awaitClosed(3000));
        assertEquals(200,log.written());assertEquals(0,log.dropped());
        assertEquals(expected.toString(),publicBytes.toString(StandardCharsets.UTF_8));
        assertEquals(expected.toString(),read(local.toPath()));
    }
    @Test public void storagePermissionFailureRetriesAndBackfillsCompleteSession()throws Exception {
        File local=spool(),publicFile=new File(local.getParentFile(),"public.jsonl");
        AtomicInteger attempts=new AtomicInteger();
        AutomaticLogWriter log=new AutomaticLogWriter(local,()->{
            if(attempts.incrementAndGet()==1)throw new IOException("permission not granted yet");
            return new FileOutputStream(publicFile,false);
        },4096,20);
        log.append("{\"event\":\"startup_settings\"}");log.append("{\"event\":\"calibration_blocked\"}");
        log.close();assertTrue(log.awaitClosed(3000));
        assertTrue(attempts.get()>=2);assertTrue(log.publicReady());assertEquals("",log.error());
        assertEquals(read(local.toPath()),read(publicFile.toPath()));
    }
    @Test public void partialPublicWriteFailureRecreatesOwnFileWithoutDuplicatingRecords()throws Exception {
        File local=spool(),publicFile=new File(local.getParentFile(),"public.jsonl");
        AtomicInteger attempts=new AtomicInteger();
        AutomaticLogWriter log=new AutomaticLogWriter(local,()->{
            OutputStream out=new FileOutputStream(publicFile,false);
            if(attempts.incrementAndGet()>1)return out;
            return new FilterOutputStream(out){
                public void write(byte[] bytes,int offset,int length)throws IOException {
                    out.write(bytes,offset,Math.min(7,length));throw new IOException("storage interrupted");
                }
            };
        },4096,20);
        log.append("{\"record\":1,\"event\":\"settings\"}");log.append("{\"record\":2,\"event\":\"gps\"}");
        log.close();assertTrue(log.awaitClosed(3000));assertTrue(attempts.get()>=2);
        assertEquals(read(local.toPath()),read(publicFile.toPath()));
    }
    @Test public void persistentPublicFailureKeepsRecoveryCopyAndReportsFailure()throws Exception {
        File local=spool();
        AutomaticLogWriter log=new AutomaticLogWriter(local,()->{throw new IOException("storage full");},4096,20);
        log.append("{\"event\":\"gps\"}");log.close();assertTrue(log.awaitClosed(3000));
        assertFalse(log.publicReady());assertTrue(log.error().contains("storage full"));
        assertTrue(read(local.toPath()).contains("gps"));
    }
    @Test public void slowStorageNeverBlocksControlProducerAndOverflowIsExplicit()throws Exception {
        File local=spool();CountDownLatch opened=new CountDownLatch(1),release=new CountDownLatch(1);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        AutomaticLogWriter log=new AutomaticLogWriter(local,()->{
            opened.countDown();
            try{if(!release.await(3,TimeUnit.SECONDS))throw new IOException("test timeout");}
            catch(InterruptedException e){throw new IOException(e);}
            return out;
        },2,20);
        try{
            assertTrue(opened.await(1,TimeUnit.SECONDS));
            log.append("{\"record\":1}");log.append("{\"record\":2}");log.append("{\"record\":3}");
            assertEquals(1,log.dropped());
        }finally{release.countDown();log.close();assertTrue(log.awaitClosed(3000));}
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("log_overflow"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"droppedEntries\":1"));
    }
}

