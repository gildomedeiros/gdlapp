package com.gdl.loratester;

/** Optional HDOP from checksum-verified, valid-fix NMEA. Raw sentences are always logged. */
final class NmeaFix {
    double hdop=Double.NaN;
    long receivedNs=-1;
    void reset(){hdop=Double.NaN;receivedNs=-1;}
    void accept(String sentence,long now) {
        String text=sentence.trim();int star=text.indexOf('*');
        if(!text.startsWith("$") || star<1 || star+3!=text.length()) return;
        try {
            int checksum=0;for(int i=1;i<star;i++) checksum^=text.charAt(i);
            if(checksum!=Integer.parseInt(text.substring(star+1),16)) return;
            String[] f=text.substring(1,star).split(",",-1);
            double candidate;
            if(f[0].endsWith("GGA") && f.length>8) {
                if(Integer.parseInt(f[6])==0){reset();return;}
                candidate=Double.parseDouble(f[8]);
            } else if(f[0].endsWith("GSA") && f.length>16) {
                if(Integer.parseInt(f[2])<2){reset();return;}
                candidate=Double.parseDouble(f[16]);
            } else return;
            if(Double.isFinite(candidate) && candidate>=0){hdop=candidate;receivedNs=now;}
        } catch(IllegalArgumentException ignored) {}
    }
    Double current(long now){return receivedNs>=0 && now>=receivedNs && now-receivedNs<=3_000_000_000L && Double.isFinite(hdop)?hdop:null;}
}
