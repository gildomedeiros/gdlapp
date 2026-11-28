package au.com.gildo.fm;

import java.util.LinkedHashMap;
import java.util.Map;

final class CameraSettings {
    static final int FIRST=29, BANDS=FIRST+4, MAPPING=FIRST+5, SCALES=FIRST+6, LAST=FIRST+7;
    static final String DEFAULT_BANDS="{\"bufferMetres\":1,\"bands\":[{\"maxDistanceMetres\":57,\"zoomX\":1},{\"maxDistanceMetres\":63,\"zoomX\":1.1},{\"maxDistanceMetres\":69,\"zoomX\":1.2},{\"maxDistanceMetres\":74,\"zoomX\":1.3},{\"maxDistanceMetres\":80,\"zoomX\":1.4},{\"maxDistanceMetres\":90,\"zoomX\":1.6},{\"maxDistanceMetres\":100,\"zoomX\":1.8},{\"maxDistanceMetres\":120,\"zoomX\":2.1},{\"maxDistanceMetres\":140,\"zoomX\":2.5},{\"maxDistanceMetres\":160,\"zoomX\":2.8},{\"maxDistanceMetres\":180,\"zoomX\":3.1},{\"maxDistanceMetres\":200,\"zoomX\":3.5},{\"maxDistanceMetres\":220,\"zoomX\":3.8},{\"maxDistanceMetres\":240,\"zoomX\":4.2},{\"maxDistanceMetres\":260,\"zoomX\":4.5},{\"maxDistanceMetres\":280,\"zoomX\":4.9},{\"maxDistanceMetres\":300,\"zoomX\":5.7},{\"maxDistanceMetres\":null,\"zoomX\":5.7}]}";

    final String cameraHost, zoomMode;
    final int cameraPort, maxRecordingMinutes;
    final boolean autoZoom, manual;
    final double desiredFrameWidthMetres, wideFrameFactor, zoomSwitchSeconds;
    final double[] zoomMultipliers;
    final ZoomBands zoomBands;
    final ZoomMapping zoomMapping;
    final Map<String,String> values=new LinkedHashMap<>();

    CameraSettings(String[] settings) {
        String flag=settings[FmService.MANUAL_CAMERA];
        if(!"true".equalsIgnoreCase(flag)&&!"false".equalsIgnoreCase(flag))
            throw new IllegalArgumentException("Manual camera must be true or false");
        manual=Boolean.parseBoolean(flag);
        values.put("Manual camera",Boolean.toString(manual));
        cameraHost=settings[6].trim();
        if(!cameraHost.isEmpty() && !cameraHost.matches("[a-zA-Z0-9.-]+"))
            throw new IllegalArgumentException("Enter a Canon IPv4 address or hostname");
        cameraPort=(int)number(settings[7],"Canon port",1,65535,true);
        for(int i=FIRST;i<=LAST;i++) values.put(FmService.KEYS[i],settings[i]);
        maxRecordingMinutes=(int)number(settings[FIRST],"Canon clip limit",0,1440,true);
        zoomMode=settings[FIRST+1].trim().toLowerCase(java.util.Locale.ROOT);
        if(!java.util.Set.of("off","bands","auto-fit").contains(zoomMode))
            throw new IllegalArgumentException("Canon zoom mode must be off, bands or auto-fit");
        autoZoom=!zoomMode.equals("off");
        desiredFrameWidthMetres=number(settings[FIRST+2],"Canon frame width",1,10000,false);
        wideFrameFactor=number(settings[FIRST+3],"Canon wide frame factor",.01,10,false);
        zoomBands=new ZoomBands(settings[BANDS]);
        zoomMapping=new ZoomMapping(settings[MAPPING]);
        zoomMultipliers=ZoomPlan.parse(settings[SCALES],true);
        zoomSwitchSeconds=number(settings[FIRST+7],"Canon zoom scale interval",2,3600,false);
        values.put("maxRecordingMinutes",settings[FIRST]);
        values.put("zoomBands",settings[BANDS]);
        values.put("zoomMapping",settings[MAPPING]);
        values.put("zoom_scales",settings[SCALES]);
    }

    private static double number(String text,String label,double min,double max,boolean integer) {
        double value;
        try { value=Double.parseDouble(text); }
        catch(NumberFormatException failure) { value=Double.NaN; }
        if(!Double.isFinite(value)||value<min||value>max||(integer&&value!=Math.rint(value)))
            throw new IllegalArgumentException(label+" must be between "+min+" and "+max);
        return value;
    }
}
