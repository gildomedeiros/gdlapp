package au.com.gildo.fm;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

public class GeoTest {
    @Test public void farthestReplayPairCanBeInsideAReturnTrip() {
        GoTo.Point start=new GoTo.Point(-28.079324811,153.401402491,0);
        List<GoTo.Point> targets=List.of(start,
            new GoTo.Point(-28.079312187,153.401387949,10),
            new GoTo.Point(-28.079299564,153.401373407,20),
            new GoTo.Point(-28.079286940,153.401358865,0),start);
        Geo.FarthestPair result=Geo.farthestPair(targets);

        assertEquals(0,result.firstIndex());
        assertEquals(3,result.secondIndex());
        assertEquals(6.0,result.metres(),0.02);
        assertEquals(12.01,Geo.pathLength(targets),0.02);
    }

    @Test public void comparesSurfaceDistancesAcrossTheAntimeridian() {
        Geo.FarthestPair result=Geo.farthestPair(List.of(
            new GoTo.Point(0,179.9,0),new GoTo.Point(0,-179.9,0),new GoTo.Point(0,179.8,0)));

        assertEquals(1,result.firstIndex());
        assertEquals(2,result.secondIndex());
        assertEquals(Geo.distance(0,-179.9,0,179.8),result.metres(),0.000001);
    }

    @Test public void requiresTwoViewpointsAndAllowsTheSameLocation() {
        GoTo.Point point=new GoTo.Point(0,0,0);
        assertNull(Geo.farthestPair(List.of(point)));
        assertEquals(0,Geo.farthestPair(List.of(point,point)).metres(),0);
    }
}
