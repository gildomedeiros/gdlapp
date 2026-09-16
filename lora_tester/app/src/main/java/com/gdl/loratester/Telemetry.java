package com.gdl.loratester;

import java.io.ByteArrayOutputStream;
import java.util.function.BiConsumer;

final class Telemetry {
    final String kind;
    final long sequence, senderMs, receiverMissed;
    final double lat, lon, hdop;
    final int sats, rssi;
    final double snr;

    private Telemetry(String[] f) {
        kind = "GPS";
        sequence = unsigned(f[2]); senderMs = unsigned(f[3]);
        lat = number(f[4]); lon = number(f[5]);
        sats = Integer.parseInt(f[6]); hdop = number(f[7]);
        rssi = Integer.parseInt(f[8]); snr = number(f[9]); receiverMissed = unsigned(f[10]);
        if (Math.abs(lat)>90 || Math.abs(lon)>180 || sats<0 || hdop<0 || rssi<-32768 || rssi>32767 || snr<-128 || snr>127)
            throw new IllegalArgumentException("Out-of-range telemetry");
    }
    static Telemetry parse(String line) {
        String[] f = line.split(",", -1);
        if (f.length != 11 || !f[0].equals("RX") || !f[1].equals("GPS"))
            throw new IllegalArgumentException("Unrecognised or malformed reading");
        return new Telemetry(f);
    }
    private static long unsigned(String s) {
        if (!s.matches("[0-9]+")) throw new IllegalArgumentException("Invalid integer");
        long n=Long.parseLong(s);
        if(n>4294967295L) throw new IllegalArgumentException("Integer exceeds sender range");
        return n;
    }
    private static double number(String s) {
        double n=Double.parseDouble(s);
        if(!Double.isFinite(n)) throw new IllegalArgumentException("Non-finite number");
        return n;
    }
    static final class Sequences {
        long high=-1, sender=-1, gaps=0, repeats=0, backwards=0, resets=0;
        String accept(Telemetry t) {
            if(high<0) { high=t.sequence; sender=t.senderMs; return "first"; }
            if(t.senderMs<sender && t.sequence<=high) {
                resets++; high=t.sequence; sender=t.senderMs; return "possible_restart_wrap_or_old_packet";
            }
            if(t.sequence==high) { repeats++; sender=Math.max(sender,t.senderMs); return "repeated_sequence"; }
            if(t.sequence<high) { backwards++; return "older_sequence"; }
            long missed=t.sequence-high-1;
            gaps+=missed; high=t.sequence; sender=t.senderMs;
            return missed>0 ? "sequence_gap:"+missed : "next";
        }
        void boundary() { high=-1; sender=-1; }
    }
    static final class Framer {
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private boolean fragmented;
        void feed(byte[] data, int length, BiConsumer<byte[],String> output) {
            for(int i=0;i<length;i++) {
                bytes.write(data[i]);
                if(data[i]=='\n') { output.accept(bytes.toByteArray(),fragmented?"fragment_end":"line"); bytes.reset(); fragmented=false; }
                else if(bytes.size()>=8192) { output.accept(bytes.toByteArray(),"oversized_fragment"); bytes.reset(); fragmented=true; }
            }
        }
        void finish(BiConsumer<byte[],String> output) {
            if(bytes.size()>0) output.accept(bytes.toByteArray(),"partial_line");
            bytes.reset(); fragmented=false;
        }
    }
}
