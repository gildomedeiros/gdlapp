package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Read once per session. User override in app-specific external files; asset is fallback. */
final class GimbalBandStorage {
    static final String NAME="vt_gimbal_bands.json";
    static final class Loaded { GimbalBandConfig config;String raw,effective,path,error="none",source; }
    static String read(InputStream in)throws IOException {
        try(InputStream stream=in;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[4096];int n;while((n=stream.read(b))!=-1){if(out.size()+n>65536)throw new IOException("Configuration exceeds 64 KiB");out.write(b,0,n);}
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    static Loaded load(Context context) {
        Loaded x=new Loaded();
        try {x.effective=read(context.getAssets().open(NAME));x.config=GimbalBandConfig.parse(x.effective);}
        catch(Exception e){throw new IllegalStateException("Invalid bundled gimbal config",e);}
        File dir=context.getExternalFilesDir(null);File file=dir==null?null:new File(dir,NAME);
        x.path=file==null ? "external_files_unavailable" : file.getAbsolutePath();x.source="bundled";
        if(file!=null && file.exists()) {
            try {x.raw=read(new FileInputStream(file));GimbalBandConfig custom=GimbalBandConfig.parse(x.raw);x.config=custom;x.effective=x.raw;x.source="external_file";}
            catch(Exception e){x.error=e.toString();x.source="bundled_fallback";}
        } else if(file!=null) {
            try(FileOutputStream out=new FileOutputStream(file)){out.write(x.effective.getBytes(StandardCharsets.UTF_8));x.raw=x.effective;x.source="bundled_seeded_file";}
            catch(IOException e){x.error=e.toString();}
        }
        return x;
    }
}
