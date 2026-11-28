package au.com.gildo.vtc;
import org.junit.Test;
import static org.junit.Assert.*;
public class LogLineTest {
    @Test public void escapesRawProtocolWithoutSplittingRecords() {
        String line=LogLine.record("time",10,"RX","request=:G120\r response=\"ok\"\n");
        assertTrue(line.contains(":G120\\r"));assertTrue(line.contains("\"\"ok\"\"\\n"));
        assertEquals(1,line.chars().filter(c -> c=='\n').count());
    }
    @Test public void quotesCommasAndHandlesNull() {
        assertEquals("\"a,b\"",LogLine.csv("a,b"));assertEquals("\"\"",LogLine.csv(null));
    }
}
