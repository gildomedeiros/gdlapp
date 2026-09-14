import com.gdl.pinkdetector.PersistenceGate;

public class PersistenceGateTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        PersistenceGate gate = new PersistenceGate();
        check(!gate.update(100, true, 800));
        check(!gate.update(899, true, 800));
        check(gate.update(900, true, 800));
        check(!gate.update(1000, false, 800));
        check(!gate.pending());
        check(!gate.update(2000, true, 800));
        check(!gate.update(2400, false, 800));
        check(!gate.update(5000, true, 800));
        check(!gate.update(5400, true, 800));
        check(gate.update(5800, true, 800));
        gate.reset();
        check(!gate.update(10000, true, 3000));
        check(!gate.update(12999, true, 3000));
        check(gate.update(13000, true, 3000));
        gate.reset();
        check(!gate.update(14000, true, 3000));
        check(!gate.update(100, true, 3000));
        System.out.println("PersistenceGateTest passed: thresholds, cancellation, fresh restart, clock rollback");
    }
}
