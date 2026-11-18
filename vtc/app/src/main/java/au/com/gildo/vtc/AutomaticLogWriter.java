package au.com.gildo.vtc;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** One disk worker, automatic public log, private recovery copy, bounded nonblocking producers. */
final class AutomaticLogWriter implements AutoCloseable {
    interface Destination { OutputStream open()throws IOException; }
    private final File spool;
    private final Destination destination;
    private final ArrayBlockingQueue<String> queue;
    private final AtomicLong dropped=new AtomicLong(),pendingDropped=new AtomicLong();
    private final Thread worker;
    private final long retryMillis;
    private volatile boolean open=true,publicReady,finished;
    private volatile long written,flushedRecords;
    private long queuedRecords;
    private volatile String publicError="",privateError="";
    private BufferedWriter local,shared;
    private long nextRetry;
    AutomaticLogWriter(File spool,Destination destination) { this(spool,destination,4096,5000); }
    AutomaticLogWriter(File spool,Destination destination,int capacity,long retryMillis) {
        this.spool=spool;this.destination=destination;this.retryMillis=retryMillis;
        queue=new ArrayBlockingQueue<>(capacity);
        worker=new Thread(this::run,"vtc-full-log");worker.setDaemon(true);worker.start();
    }
    synchronized void append(String line) {
        if(!open)return;
        if(queue.offer(line))queuedRecords++;
        else{dropped.incrementAndGet();pendingDropped.incrementAndGet();}
    }
    synchronized long checkpoint(){return queuedRecords;}
    boolean awaitFlushed(long checkpoint,long timeout)throws InterruptedException {
        long deadline=monotonic()+timeout;
        while(flushedRecords<checkpoint&&!finished&&monotonic()<deadline)Thread.sleep(25);
        return flushedRecords>=checkpoint;
    }
    long written(){return written;}
    long dropped(){return dropped.get();}
    boolean publicReady(){return publicReady;}
    String error(){return !privateError.isEmpty()?"Private recovery log: "+privateError:publicError;}
    boolean finished(){return finished;}
    private static long monotonic(){return System.nanoTime()/1_000_000;}
    private void run() {
        try {
            File dir=spool.getParentFile();
            if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create private log folder");
            local=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(spool),StandardCharsets.UTF_8));
            tryPublic(true);
            long processed=0;
            while(open||!queue.isEmpty()) {
                String row=queue.poll(200,TimeUnit.MILLISECONDS);
                if(row!=null){write(row);processed++;}
                long lost=pendingDropped.getAndSet(0);
                if(lost>0)write("{\"event\":\"log_overflow\",\"epochMs\":"+System.currentTimeMillis()+",\"droppedEntries\":"+lost+"}");
                // Batches keep disk work separate from all control and receiver threads.
                for(int n=0;n<127&&(row=queue.poll())!=null;n++){write(row);processed++;}
                local.flush();
                flushedRecords=processed;
                flushPublic();
                tryPublic(false);
            }
            local.flush();flushPublic();tryPublic(true);
        }catch(Exception e){privateError=e.toString();}
        finally {
            try{if(local!=null)local.close();}catch(IOException e){privateError=e.toString();}
            try{if(shared!=null)shared.close();}catch(IOException e){publicFailed(e);}
            finished=true;
        }
    }
    private void write(String row)throws IOException {
        local.write(row);local.newLine();written++;
        if(shared!=null)try{shared.write(row);shared.newLine();}catch(IOException e){publicFailed(e);}
    }
    private void flushPublic() {
        if(shared!=null)try{shared.flush();}catch(IOException e){publicFailed(e);}
    }
    private void tryPublic(boolean force)throws IOException {
        if(shared!=null||(!force&&monotonic()<nextRetry))return;
        local.flush();
        try {
            shared=new BufferedWriter(new OutputStreamWriter(destination.open(),StandardCharsets.UTF_8));
            // Recreate only this session's public file from the complete recovery copy.
            try(Reader in=new InputStreamReader(new FileInputStream(spool),StandardCharsets.UTF_8)) {
                char[] b=new char[8192];int n;while((n=in.read(b))!=-1)shared.write(b,0,n);
            }
            shared.flush();publicReady=true;publicError="";
        }catch(Exception e){publicFailed(e);}
    }
    private void publicFailed(Exception e) {
        publicReady=false;publicError=e.toString();nextRetry=monotonic()+retryMillis;
        BufferedWriter failed=shared;shared=null;
        if(failed!=null)try{failed.close();}catch(IOException ignored){}
    }
    public synchronized void close(){open=false;}
    boolean awaitClosed(long timeoutMillis)throws InterruptedException { worker.join(timeoutMillis);return !worker.isAlive(); }
}

