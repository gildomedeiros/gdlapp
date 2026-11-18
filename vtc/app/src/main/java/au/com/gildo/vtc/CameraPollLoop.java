package au.com.gildo.vtc;
import java.util.concurrent.*;
/** Single-flight polling that never runs on the mount executor or the UI thread. */
final class CameraPollLoop implements AutoCloseable {
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"vtc-camera"));
    CameraPollLoop(Runnable read,long delayMs){worker.scheduleWithFixedDelay(read,0,delayMs,TimeUnit.MILLISECONDS);}
    void execute(Runnable work){worker.execute(work);}
    public void close(){worker.shutdownNow();}
    boolean awaitClosed(long millis)throws InterruptedException {
        return worker.awaitTermination(millis,TimeUnit.MILLISECONDS);
    }
}
