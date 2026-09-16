package com.gdl.loratester;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class TelemetryCheck {
    static void check(boolean condition,String description) { if(!condition) throw new AssertionError(description); }
    static Telemetry reading(long sequence,long millis) { return Telemetry.parse("RX,GPS,"+sequence+","+millis+",-28.07928,153.40093,8,0.9,-94,5,0"); }
    public static void main(String[] args) {
        String good="RX,GPS,1842,123456789,-28.07928,153.40093,8,0.9,-94,5,0\r\n";
        byte[] input=(good+"RAW,bad\u0000bytes,-99,2\r\nRX_ERROR\r\npartial").getBytes(StandardCharsets.UTF_8);
        for(int chunkSize=1;chunkSize<=input.length;chunkSize++) {
            Telemetry.Framer framer=new Telemetry.Framer(); ByteArrayOutputStream restored=new ByteArrayOutputStream(); List<String> kinds=new ArrayList<>();
            java.util.function.BiConsumer<byte[],String> output=(b,k)->{ restored.write(b,0,b.length); kinds.add(k); };
            for(int i=0;i<input.length;i+=chunkSize) { byte[] chunk=Arrays.copyOfRange(input,i,Math.min(i+chunkSize,input.length)); framer.feed(chunk,chunk.length,output); }
            framer.finish(output);
            check(Arrays.equals(input,restored.toByteArray()),"Exact bytes survive arbitrary USB chunks");
            check(kinds.equals(Arrays.asList("line","line","line","partial_line")),"No duplicate or missing framed readings");
        }
        Telemetry.Framer framer=new Telemetry.Framer(); List<String> kinds=new ArrayList<>();
        byte[] huge=new byte[20001]; Arrays.fill(huge,(byte)'x'); huge[20000]='\n';
        ByteArrayOutputStream restored=new ByteArrayOutputStream();
        framer.feed(huge,huge.length,(b,k)->{restored.write(b,0,b.length); kinds.add(k);});
        check(Arrays.equals(huge,restored.toByteArray()),"Oversized data preserved");
        check(kinds.equals(Arrays.asList("oversized_fragment","oversized_fragment","fragment_end")),"Oversized data cannot become valid GPS tail");
        for(String bad:new String[]{"RX,GPS,1,1,NaN,1,8,1,-90,2,0","RX,GPS,1,1,91,1,8,1,-90,2,0","RX,GPS,1,1,,1,8,1,-90,2,0","RX,GPS,x,1,1,1,8,1,-90,2,0", "RX,GPS,1,1,1,1,8,1,-90,2,0,extra"}) {
            boolean rejected=false; try { Telemetry.parse(bad); } catch(IllegalArgumentException e) { rejected=true; } check(rejected,"Malformed telemetry rejected for display");
        }
        Telemetry.Sequences s=new Telemetry.Sequences();
        check(s.accept(reading(10,100)).equals("first"),"Initial packet");
        check(s.accept(reading(12,120)).equals("sequence_gap:1"),"Gap");
        check(s.accept(reading(12,120)).equals("repeated_sequence"),"Repeat");
        check(s.accept(reading(11,120)).equals("older_sequence"),"Older does not move high-water mark");
        check(s.accept(reading(13,130)).equals("next"),"Older did not invent gap");
        check(s.accept(reading(0,10)).startsWith("possible_restart"),"Restart ambiguous and bounded");
        check(s.gaps==1 && s.repeats==1 && s.backwards==1 && s.resets==1,"Counters");
        s.boundary(); check(s.accept(reading(99,999)).equals("first"),"Reconnect starts new baseline");
        System.out.println("PASS: chunk boundaries, exact bytes, malformed and oversized data, duplicates, gaps, restart and reconnect baselines");
    }
}
