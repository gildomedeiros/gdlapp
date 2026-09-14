package com.gdl.pinkdetector;
import javax.imageio.ImageIO;
import java.util.zip.ZipFile;

/** Compare offset optimization against the previous native matcher, on supplied RAW evidence. */
public final class SpotlightPinEquivalenceTest {
    static int[] samples(String name) throws Exception {
        var f=SpotlightPinMatcher.class.getDeclaredField(name); f.setAccessible(true); return (int[])f.get(null);
    }
    static boolean green(int p) {
        int r=(p>>16)&255,g=(p>>8)&255,b=p&255;
        return g>135 && g>r*1.45 && b>45 && b<g*.95;
    }
    static boolean previous(int[] p,int w,int h,int[] pos,int[] neg) {
        double sx=w/2340.0,sy=h/1080.0;
        int tw=(int)Math.ceil(116*sx),th=(int)Math.ceil(114*sy),step=Math.max(1,(int)Math.round(2*sx));
        for(int y=0;y<h-th;y+=step) for(int x=(int)(w*.11);x<w*.92-tw;x+=step) {
            int hits=0,misses=0;
            for(int i=0;i<pos.length;i+=2) {
                if(green(p[(y+(int)Math.round(pos[i+1]*sy))*w+x+(int)Math.round(pos[i]*sx)])) hits++; else misses++;
                if(misses>pos.length/2*.15) break;
            }
            if(hits<pos.length/2*.85) continue;
            int clear=0;
            for(int i=0;i<neg.length;i+=2)
                if(!green(p[(y+(int)Math.round(neg[i+1]*sy))*w+x+(int)Math.round(neg[i]*sx)])) clear++;
            if(clear>=neg.length/2*.85) return true;
        }
        return false;
    }
    public static void main(String[] args) throws Exception {
        int[] pos=samples("POS"),neg=samples("NEG"); int n=0,positive=0;
        try(var z=new ZipFile(args[0])) {
            var entries=z.entries();
            while(entries.hasMoreElements()) {
                var e=entries.nextElement(); if(!e.getName().endsWith("_RAW.jpg")) continue;
                try(var in=z.getInputStream(e)) {
                    var im=ImageIO.read(in); int w=im.getWidth(),h=im.getHeight();
                    int[] p=im.getRGB(0,0,w,h,null,0,w);
                    boolean a=previous(p,w,h,pos,neg),b=SpotlightPinMatcher.matches(p,w,h);
                    if(a!=b) throw new AssertionError(e.getName());
                    n++; if(b) positive++;
                }
            }
        }
        System.out.println("Identical pin decisions: "+n+" RAW frames, "+positive+" positive");
    }
}
