package com.gdl.pinkdetector;
import javax.imageio.ImageIO;
import java.io.File;
public class SpotlightPinReplay {
 public static void main(String[] args) throws Exception {
  for(String path:args) {
   var im=ImageIO.read(new File(path));int w=im.getWidth(),h=im.getHeight();
   int[] p=im.getRGB(0,0,w,h,null,0,w);long t=System.nanoTime();
   System.out.println(new File(path).getName()+" pin="+SpotlightPinMatcher.matches(p,w,h)+" ms="+(System.nanoTime()-t)/1000000);
  }
 }
}