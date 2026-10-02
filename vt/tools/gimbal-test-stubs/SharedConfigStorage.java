package dji.v5.ux.sample.showcase.defaultlayout.aiming;
final class SharedConfigStorage {
 static final String ROTATION="vt_rotation_speeds.json";
 static String raw,error;
 static String folder(android.content.Context c){return "test-folder";}
 static String read(android.content.Context c,String name)throws java.io.IOException{if(error!=null)throw new java.io.IOException(error);return raw;}
}
