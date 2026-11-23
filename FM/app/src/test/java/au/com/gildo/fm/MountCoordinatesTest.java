package au.com.gildo.fm;

import org.junit.Test;
import static org.junit.Assert.*;

public class MountCoordinatesTest {
    @Test public void acceptsGoogleMapsPairAndPreservesPrecision() {
        String[] values=new String[10];
        MountCoordinates.apply(" -28.0700700, 153.3873000 ",values);
        assertEquals("-28.0700700",values[8]);
        assertEquals("153.3873000",values[9]);
        assertEquals("-28.0700700, 153.3873000",MountCoordinates.format(values));
    }

    @Test public void formatsCapturedGpsAsAnEditableDecimalPair() {
        String formatted=MountCoordinates.format(0.0000001,-153.4012938366937);
        assertEquals("0.0000001, -153.4012938366937",formatted);
        String[] values=new String[10];
        MountCoordinates.apply(formatted,values);
        assertEquals(formatted,MountCoordinates.format(values));
    }

    @Test public void rejectsIncompleteOutOfBoundsAndNonDecimalInput() {
        for(String input:new String[]{"-28.1","-28.1,","153.4,-28.1","-28.1,181","NaN,153.4","-2e1,153.4"}) {
            try {
                MountCoordinates.apply(input,new String[10]);
                fail("Accepted "+input);
            } catch(IllegalArgumentException expected) {}
        }
    }
}
