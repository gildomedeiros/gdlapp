package com.gdl.pinkdetector;

/** Three times marker width/height, symmetrically reduced at preview edges. */
public final class AcquisitionRectangle {
    public static float[] bounds(int left,int top,int right,int bottom,int w,int h) {
        float cx=(left+right)/2f,cy=(top+bottom)/2f;
        float hx=Math.min((right-left)*1.5f,Math.min(cx-w*.107f,w*.927f-cx));
        float hy=Math.min((bottom-top)*1.5f,Math.min(cy,h-1-cy));
        if(hx<=0 || hy<=0) return null;
        return new float[]{cx-hx,cy-hy,cx+hx,cy+hy};
    }
}
