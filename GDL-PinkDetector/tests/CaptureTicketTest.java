import com.gdl.pinkdetector.CaptureTicket;
public class CaptureTicketTest {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) {
        var gate=new CaptureTicket(); long old=gate.begin();
        check(gate.begin()==0); check(gate.expire(old));
        long fresh=gate.begin(); check(!gate.accept(old)); check(!gate.finish(old));
        check(gate.accept(fresh)); check(!gate.accept(fresh)); check(!gate.expire(fresh));
        check(gate.begin()==0); check(gate.finish(fresh)); check(gate.begin()!=0);
        System.out.println("Capture timeout, late callback and processing isolation passed");
    }
}
