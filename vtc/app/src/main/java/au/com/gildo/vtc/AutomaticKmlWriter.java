package au.com.gildo.vtc;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Append complete placemarks before the closing tags, so every completed update is valid KML. */
final class AutomaticKmlWriter implements AutoCloseable {
    interface Destination { FileChannel open()throws IOException; }
    private static final byte[] FOOTER=KmlDocument.FOOTER.getBytes(StandardCharsets.UTF_8);
    private final File spool;
    private final Destination destination;
    private final String name;
    private final ArrayBlockingQueue<Map<String,Object>> queue;
    private final AtomicLong dropped=new AtomicLong(),pendingDropped=new AtomicLong();
    private final KmlDocument document=new KmlDocument();
    private final Thread worker;
    private final long retryMs;
    private volatile boolean open=true,publicReady,finished;
    private volatile long written;
    private volatile String error="";
    private FileChannel local,shared;
    private long retryAt;
    AutomaticKmlWriter(File spool,Destination destination,String name){this(spool,destination,name,1024,5000);}
    AutomaticKmlWriter(File spool,Destination destination,String name,int capacity,long retryMs) {
        this.spool=spool;this.destination=destination;this.name=name;this.retryMs=retryMs;queue=new ArrayBlockingQueue<>(capacity);
        worker=new Thread(this::run,"vtc-kml");worker.setDaemon(true);worker.start();
    }
    synchronized void append(Map<String,Object> event) {
        if(!open||!KmlDocument.accepts(String.valueOf(event.get("event"))))return;
        if(!queue.offer(event)){dropped.incrementAndGet();pendingDropped.incrementAndGet();}
    }
    long written(){return written;}
    long dropped(){return dropped.get();}
    boolean publicReady(){return publicReady;}
    boolean finished(){return finished;}
    String error(){return error;}
    private static long clock(){return System.nanoTime()/1_000_000;}
    private void run() {
        try {
            File directory=spool.getParentFile();
            if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create KML recovery folder");
            local=new RandomAccessFile(spool,"rw").getChannel();local.truncate(0);
            write(local,(KmlDocument.header(name)+KmlDocument.FOOTER).getBytes(StandardCharsets.UTF_8));
            publish(true);
            while(open||!queue.isEmpty()) {
                Map<String,Object> event=queue.poll(200,TimeUnit.MILLISECONDS);
                StringBuilder batch=new StringBuilder();
                if(event!=null){batch.append(document.accept(event));written++;}
                for(int n=0;n<63&&(event=queue.poll())!=null;n++){batch.append(document.accept(event));written++;}
                long lost=pendingDropped.getAndSet(0);
                if(lost>0)batch.append(document.gap(lost,System.currentTimeMillis()));
                if(batch.length()>0) {
                    byte[] bytes=batch.toString().getBytes(StandardCharsets.UTF_8);
                    appendDocument(local,bytes);
                    if(shared!=null)try{appendDocument(shared,bytes);}catch(Exception e){failed(e);}
                }
                publish(false);
            }
            publish(true);
        }catch(Exception e){error="KML recovery file: "+e;publicReady=false;}
        finally {
            try{if(local!=null)local.close();}catch(IOException e){error=e.toString();}
            try{if(shared!=null)shared.close();}catch(IOException e){error=e.toString();publicReady=false;}
            finished=true;
        }
    }
    private static void write(FileChannel channel,byte[] bytes)throws IOException {
        ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);
    }
    private static void appendDocument(FileChannel channel,byte[] fragment)throws IOException {
        channel.position(channel.size()-FOOTER.length);
        write(channel,fragment);write(channel,FOOTER);channel.truncate(channel.position());
    }
    private void publish(boolean force) {
        if(shared!=null||(!force&&clock()<retryAt))return;
        try {
            shared=destination.open();shared.position(0);shared.truncate(0);
            try(InputStream in=new FileInputStream(spool)){
                byte[] bytes=new byte[8192];int size;
                while((size=in.read(bytes))!=-1){ByteBuffer buffer=ByteBuffer.wrap(bytes,0,size);while(buffer.hasRemaining())shared.write(buffer);}
            }
            publicReady=true;error="";
        }catch(Exception e){failed(e);}
    }
    private void failed(Exception e) {
        publicReady=false;error=e.toString();retryAt=clock()+retryMs;
        FileChannel failed=shared;shared=null;if(failed!=null)try{failed.close();}catch(IOException ignored){}
    }
    public synchronized void close(){open=false;}
    boolean awaitClosed(long timeoutMs)throws InterruptedException {worker.join(timeoutMs);return !worker.isAlive();}
}
