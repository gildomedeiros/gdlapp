import com.gdl.pinkdetector.EvidenceAdmission;
import java.util.concurrent.*;
public class EvidenceAdmissionTest {
    public static void main(String[] args) throws Exception {
        var slots=new EvidenceAdmission(); var writer=Executors.newSingleThreadExecutor();
        var blocked=new CountDownLatch(1); var entered=new CountDownLatch(1);
        try {
            for(int i=0;i<3;i++) {
                if(!slots.tryAcquire()) throw new AssertionError();
                writer.execute(()->{entered.countDown(); try { blocked.await(); }
                    catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { slots.release(); }});
            }
            if(!entered.await(2,TimeUnit.SECONDS)) throw new AssertionError();
            var detector=Executors.newSingleThreadExecutor();
            try {
                int processed=detector.submit(()->{int n=0; for(int i=0;i<10000;i++) {
                    if(slots.tryAcquire()) throw new AssertionError(); n++;
                } return n; }).get(2,TimeUnit.SECONDS);
                if(processed!=10000) throw new AssertionError();
            } finally { detector.shutdownNow(); }
        } finally { blocked.countDown(); writer.shutdown(); }
        if(!writer.awaitTermination(2,TimeUnit.SECONDS) || !slots.tryAcquire()) throw new AssertionError();
        slots.release(); System.out.println("Blocked writer: 10000 nonblocking skips; admission resumes after drain");
    }
}
