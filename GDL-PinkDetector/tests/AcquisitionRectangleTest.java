import com.gdl.pinkdetector.AcquisitionRectangle;
public final class AcquisitionRectangleTest {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) {
        float[] r=AcquisitionRectangle.bounds(900,400,960,460,2340,1080);
        check(r[2]-r[0]==180 && r[3]-r[1]==180);
        check((r[0]+r[2])/2==930 && (r[1]+r[3])/2==430);
        r=AcquisitionRectangle.bounds(251,0,311,60,2340,1080);
        check(r[0]>=2340*.107f && r[1]>=0);
        check((r[0]+r[2])/2==281 && (r[1]+r[3])/2==30);
        check(AcquisitionRectangle.bounds(0,0,10,10,2340,1080)==null);
        System.out.println("Rectangle area, centre, preview edges and invalid marker passed");
    }
}
