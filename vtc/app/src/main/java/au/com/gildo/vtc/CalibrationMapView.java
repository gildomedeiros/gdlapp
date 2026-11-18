package au.com.gildo.vtc;
import android.content.*;
import android.graphics.Color;
import android.text.InputType;
import android.webkit.*;
import android.widget.*;
import java.util.*;
import java.util.function.Supplier;
/** Saved calibration preview; internet loading is independent of device Wi-Fi sockets. */
final class CalibrationMapView extends LinearLayout {
    private final Supplier<Settings> snapshot;
    private final java.util.function.BooleanSupplier calibrated;
    private final SharedPreferences preferences;
    private final TextView status;
    private final EditText key;
    private final WebView web;
    private String shown="";
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    CalibrationMapView(Context context,Supplier<Settings> snapshot,java.util.function.BooleanSupplier calibrated){
        super(context);this.snapshot=snapshot;this.calibrated=calibrated;setOrientation(VERTICAL);setPadding(0,20,0,20);
        preferences=context.getSharedPreferences("vtc_google_map",Context.MODE_PRIVATE);
        status=new TextView(context);status.setTextColor(Color.WHITE);addView(status);
        TextView help=new TextView(context);help.setTextColor(Color.LTGRAY);help.setText("Current saved coordinates. Stop aiming and disconnect GTi before switching Wi-Fi. Reconnect and validate calibration before aiming again. Add a Maps JavaScript API key below to enable Google Maps; the direction diagram works without it.");addView(help);
        key=new EditText(context);key.setTextColor(Color.WHITE);key.setHintTextColor(Color.LTGRAY);key.setHint("Google Maps JavaScript API key (optional)");key.setSingleLine(true);key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);key.setText(preferences.getString("key",""));addView(key);
        Button save=new Button(context);save.setText("Save map key / Refresh map");addView(save);
        web=new WebView(context);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}});
        addView(web,new LayoutParams(LayoutParams.MATCH_PARENT,(int)(390*getResources().getDisplayMetrics().density)));
        save.setOnClickListener(v->{String value=key.getText().toString().trim();if(!value.isEmpty()&&!value.matches("[A-Za-z0-9_-]{20,200}")){status.setText("Invalid map key format");return;}preferences.edit().putString("key",value).apply();refresh();});
        TextView privacy=new TextView(context);privacy.setTextColor(Color.LTGRAY);privacy.setText("Google Maps receives the displayed coordinates when loaded. Key is stored separately on this phone, not in setup.json or VTC logs. For website key restrictions use https://vtc.local/* and enable Maps JavaScript API. The line shows the geographic bearing to the landmark, not a measurement of camera direction.");addView(privacy);
        refresh();
    }
    private String version(){Settings s=snapshot.get();return s.latitude+"/"+s.longitude+"/"+s.landmarkLat+"/"+s.landmarkLon+"/"+calibrated.getAsBoolean();}
    void refreshIfChanged(){if(!version().equals(shown))refresh();}
    void refresh(){
        Settings s=snapshot.get();shown=version();
        if(!CurrentMap.valid(s)){status.setText("Capture or enter both camera and landmark coordinates. Calibration is not required to view them.");web.loadDataWithBaseURL("https://vtc.local/","<html><body style='background:#102030;color:white'>Current camera or landmark coordinates are missing.</body></html>","text/html","UTF-8",null);return;}
        double distance=Geo.distance(s.latitude,s.longitude,s.landmarkLat,s.landmarkLon);
        String bearing=distance<.1?"undefined (points coincide)":String.format(Locale.US,"%.2f° true",Geo.bearing(s.latitude,s.longitude,s.landmarkLat,s.landmarkLon));
        status.setText(String.format(Locale.US,"Current saved coordinates — %s\nCamera %.7f, %.7f\nLandmark %.7f, %.7f\nDistance %.1f m · geographic bearing %s",calibrated.getAsBoolean()?"Calibration validated":"Not yet calibrated",s.latitude,s.longitude,s.landmarkLat,s.landmarkLon,distance,bearing));
        web.loadDataWithBaseURL("https://vtc.local/",CalibrationMapData.html(s,preferences.getString("key","")),"text/html","UTF-8",null);
    }
    void close(){web.stopLoading();web.destroy();}
}
