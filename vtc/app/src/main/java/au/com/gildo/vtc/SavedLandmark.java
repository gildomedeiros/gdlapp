package au.com.gildo.vtc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Persistent motor bookmark, deliberately separate from a validated tracking calibration. */
final class SavedLandmark {
    final Calibration saved;
    final String firmware,invalidReason;
    final long savedAtEpochMs;
    private SavedLandmark(Calibration saved,String firmware,long at,String invalidReason) {
        if(saved.panRaw<0||saved.panRaw>0xffffff||saved.tiltRaw<0||saved.tiltRaw>0xffffff
            ||saved.panCpr<360||saved.panCpr>0x7fffff||saved.tiltCpr<360||saved.tiltCpr>0x7fffff
            ||firmware==null||firmware.isEmpty()||at<=0)throw new IllegalArgumentException("Invalid saved landmark data");
        this.saved=saved;this.firmware=firmware;savedAtEpochMs=at;this.invalidReason=invalidReason;
    }
    static SavedLandmark capture(Calibration calibration,String firmware,long at) {
        return new SavedLandmark(calibration,firmware,at,"");
    }
    SavedLandmark invalidate(String reason){return new SavedLandmark(saved,firmware,savedAtEpochMs,reason);}
    Calibration navigationReference(Settings current,int[] cpr,String currentFirmware) {
        if(!invalidReason.isEmpty())throw new IllegalArgumentException(invalidReason);
        Settings old=saved.settings;
        if(cpr.length!=2||cpr[0]!=saved.panCpr||cpr[1]!=saved.tiltCpr||!firmware.equals(currentFirmware)
            ||!old.mountHost.equals(current.mountHost)||old.mountPort!=current.mountPort)
            throw new IllegalArgumentException("Last landmark belongs to a different mount setup; calibrate to save a new one");
        double[] before={old.latitude,old.longitude,old.height,old.landmarkLat,old.landmarkLon,old.landmarkHeight,old.panSign,old.tiltSign};
        double[] after={current.latitude,current.longitude,current.height,current.landmarkLat,current.landmarkLon,current.landmarkHeight,current.panSign,current.tiltSign};
        for(int i=0;i<before.length;i++)if(Double.compare(before[i],after[i])!=0)
            throw new IllegalArgumentException("Camera, landmark or axis settings changed; calibrate to save a new landmark");
        // Current movement limits and leg size apply, rather than obsolete saved limits.
        return new Calibration(current,new GtiClient.Position(saved.panRaw,saved.tiltRaw,false),cpr);
    }
    Map<String,String> values() {
        Map<String,String> out=new LinkedHashMap<>();
        out.put("format","1");out.put("savedAtEpochMs",Long.toString(savedAtEpochMs));
        out.put("panRaw",Integer.toString(saved.panRaw));out.put("tiltRaw",Integer.toString(saved.tiltRaw));
        out.put("panCpr",Integer.toString(saved.panCpr));out.put("tiltCpr",Integer.toString(saved.tiltCpr));
        out.put("firmware",firmware);out.put("invalidReason",invalidReason);
        for(Map.Entry<String,String> e:saved.settings.values.entrySet())out.put("setting."+e.getKey(),e.getValue());
        return out;
    }
    static SavedLandmark restore(Map<String,String> values) {
        if(!"1".equals(values.get("format")))throw new IllegalArgumentException("Unsupported saved landmark format");
        Map<String,String> settings=new LinkedHashMap<>();
        for(Map.Entry<String,String> e:values.entrySet())if(e.getKey().startsWith("setting."))settings.put(e.getKey().substring(8),e.getValue());
        int pan=Integer.parseInt(values.get("panRaw")),tilt=Integer.parseInt(values.get("tiltRaw"));
        int[] cpr={Integer.parseInt(values.get("panCpr")),Integer.parseInt(values.get("tiltCpr"))};
        return new SavedLandmark(new Calibration(new Settings(settings),new GtiClient.Position(pan,tilt,false),cpr),
            values.get("firmware"),Long.parseLong(values.get("savedAtEpochMs")),Objects.requireNonNull(values.get("invalidReason")));
    }
    Map<String,Object> snapshot() {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("savedAtEpochMs",savedAtEpochMs);out.put("panRaw",saved.panRaw);out.put("tiltRaw",saved.tiltRaw);
        out.put("panCountsPerRevolution",saved.panCpr);out.put("tiltCountsPerRevolution",saved.tiltCpr);
        out.put("firmware",firmware);out.put("invalidReason",invalidReason);out.put("settings",saved.settings.values);
        return out;
    }
}
