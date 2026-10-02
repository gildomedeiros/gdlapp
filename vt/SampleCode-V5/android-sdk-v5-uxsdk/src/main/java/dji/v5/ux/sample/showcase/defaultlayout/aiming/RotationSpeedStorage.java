package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import android.content.Context;
/** VT 3.7: Load only before Start; invalid or missing JSON uses the original formula. */
final class RotationSpeedStorage {
    static final class Loaded {RotationSpeedCurve curve;String raw,source="legacy_formula",error="none",path;}
    static Loaded load(Context c) {
        Loaded x=new Loaded();x.path=SharedConfigStorage.folder(c);
        try {x.raw=SharedConfigStorage.read(c,SharedConfigStorage.ROTATION);
            if(x.raw==null)x.error="No configuration folder selected";
            else {x.curve=RotationSpeedConfig.parse(x.raw);x.source="shared_folder";}
        }catch(Exception e){x.error=e.toString();}
        return x;
    }
}
