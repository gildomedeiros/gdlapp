package au.com.gildo.vtc;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int BACKGROUND=Color.rgb(16,29,40),CARD=Color.rgb(28,44,57),TEXT=Color.rgb(234,243,247),MUTED=Color.rgb(166,186,200),ACCENT=Color.rgb(84,214,176);
    private final Map<String,EditText> fields=new LinkedHashMap<>();
    private final List<Button> manualButtons=new ArrayList<>();
    private final List<Button> landmarkButtons=new ArrayList<>();
    private final List<TextView> landmarkStatuses=new ArrayList<>();
    private final ExecutorService files=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Settings settings;
    private SessionLog log;
    private VtcService service;
    private boolean bound,screenVisible,fillingSettings;
    private final Runnable autoSave=this::autoSaveSettings;
    private TextView cameraInfo;
    private final VtcController.Listener listener=this::render;
    private final ServiceConnection serviceConnection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){
            service=((VtcService.LocalBinder)binder).service();controller=service.controller;log=service.log;settings=controller.settings();
            buildUi();fillSettings(settings);showPage(0);service.attach(listener);controller.foreground(screenVisible);refreshLogStatus();refreshTelemetry();
        }
        public void onServiceDisconnected(ComponentName name){controller=null;service=null;handler.removeCallbacksAndMessages(null);
            TextView error=new TextView(MainActivity.this);error.setText("VTC service interrupted. Reopen VTC, reconnect and validate calibration.");setContentView(error);}
    };
    private VtcController controller;
    private LinearLayout root,body;
    private View[] pages;
    private TextView state,message,connections,mountInfo,gpsInfo,aimInfo,frameInfo,calibrationInfo,setupMessage,logStatus;
    private Button connect,disconnect,start,calibrate;
    private final List<Button> aimingButtons=new ArrayList<>();
    private boolean capturing;
    private TextView captureStatus,calibrationGps;
    private final List<TextView> settingsStorageStatuses=new ArrayList<>();
    private String settingsValidationError="";
    private CalibrationMapView calibrationMap;
    private Spinner panSign,tiltSign,manualStep,zoomMode;
    private CheckBox levelConfirmed,directionsConfirmed;
    private String exportSettingsText;
    private int page;
    private VtcController.View lastView;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView loading=new TextView(this);loading.setText("Starting VTC service…");setContentView(loading);
        Intent intent=new Intent(this,VtcService.class);startForegroundService(intent);
        bound=bindService(intent,serviceConnection,Context.BIND_AUTO_CREATE);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},104);
        if(Build.VERSION.SDK_INT<29&&checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},103);
    }
    private void buildUi() {
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(16),dp(12),dp(16),dp(8));setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            root.setPadding(dp(16)+insets.getSystemWindowInsetLeft(),dp(8)+insets.getSystemWindowInsetTop(),dp(16)+insets.getSystemWindowInsetRight(),dp(8)+insets.getSystemWindowInsetBottom());
            return insets;
        });
        text(root,"VTC  "+SessionLog.VERSION,26,ACCENT,true);
        text(root,"GPS-guided shore camera mount",13,MUTED,false);
        state=text(root,"DISCONNECTED",18,TEXT,true);
        connections=text(root,"GTi: Disconnected\nLoRa: Disconnected\nCalibration: Required",13,TEXT,false);
        message=text(root,"Set up the site and connect.",14,MUTED,false);
        LinearLayout nav=row(root);
        String[] labels={"Track","Calibrate","Setup","Logs"};
        for(int i=0;i<labels.length;i++){final int n=i;button(nav,labels[i],()->showPage(n));}
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        pages=new View[]{trackPage(),calibrationPage(),settingsPage(),logsPage()};
        Button stop=button(root,"STOP MOUNT",()->controller.stop("User Stop"));
        stop.setTextColor(Color.WHITE);
        stop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(158,48,56)));
        text(root,"Background aiming • independent watchdog",12,MUTED,false);
    }
    private LinearLayout scrollPage() {
        LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(0,dp(8),0,dp(12));return p;
    }
    private View scroll(LinearLayout content){ScrollView s=new ScrollView(this);s.setFillViewport(true);s.addView(content);return s;}
    private View trackPage() {
        LinearLayout p=scrollPage();
        LinearLayout r=row(p);connect=button(r,"Connect GTi + LoRa",()->{if(autoSaveSettings())controller.connect();});disconnect=button(r,"Disconnect GTi",()->controller.disconnect());
        loraControls(p);
        start=aimingButton(p);
        landmarkButtons.add(button(p,"GO TO LAST LANDMARK",()->controller.goToLandmark()));
        landmarkStatuses.add(text(p,"Loading last saved landmark",13,MUTED,false));
        text(p,"Returns both axes to the last saved landmark and stays there. No surfer GPS is needed. Returning does not validate calibration.",13,MUTED,false);
        text(p,"Start can wait for a target outside the limits. While aiming, a target outside the configured range or pan/tilt limits pauses new movements. Fresh GPS back inside the limits resumes aiming automatically. Stop cancels automatic resume.",13,MUTED,false);
        text(p,"Large errors trigger GoTo to a saved position. New GPS does not change that move. After arrival, live GPS aiming resumes. Stop cancels both. Switching apps or locking the screen keeps aiming active.",14,MUTED,false);
        LinearLayout a=card(p,"Aim / next steps");aimInfo=text(a,"Checking settings and connection",16,TEXT,false);
        LinearLayout f=card(p,"Automatic framing");frameInfo=text(f,"Automatic",15,TEXT,false);
        LinearLayout g=card(p,"Surfer GPS");gpsInfo=text(g,"Disconnected",15,TEXT,false);
        cameraInfo=text(card(p,"Camera zoom / clock"),"Camera disabled",14,MUTED,false);
        LinearLayout m=card(p,"Mount");mountInfo=text(m,"Disconnected",14,MUTED,false);
        button(p,"STOP & EXIT VTC",()->service.exit(()->{finishAndRemoveTask();}));
        return scroll(p);
    }
    private View calibrationPage() {
        LinearLayout p=scrollPage();
        aimingButton(p);loraControls(p);
        button(p,"See calibration in Google Maps",()->{if(autoSaveSettings())CalibrationLauncher.maps(this,controller.settings());});
        button(p,"See calibration in Google Earth",()->{if(autoSaveSettings())CalibrationLauncher.earth(this,controller.settings(),controller.mapValidated());});
        text(p,"Uses current saved camera and landmark coordinates, including LoRa captures. Maps shows directions; Earth shows both points and their bearing line. No calibration or API key is required to view coordinates.",13,MUTED,false);
        calibrationGps=text(card(p,"LoRa GPS for capture"),"LoRa disconnected",14,MUTED,false);
        settingsStorageStatuses.add(text(p,"Checking settings storage",14,ACCENT,false));
        captureStatus=text(p,"Disconnect GTi to edit reference settings or capture GPS. LoRa stays connected. Keep the LoRa unit still during capture.",14,ACCENT,false);
        LinearLayout site=card(p,"Camera site");
        field(site,"cameraCoordinates","Camera coordinates — latitude, longitude");
        text(site,"Paste the coordinate pair copied from Google Maps, e.g. -28.165514, 153.550457",13,MUTED,false);
        button(site,"Use LoRa GPS for camera",()->captureCoordinates(false));
        field(site,"height","Lens height above water (m)");
        field(site,"targetHeight","Aim height on surfer above water (m)");

        LinearLayout reference=card(p,"Landmark");
        text(reference,"Enter the coordinates and height of the exact point you will centre. Use the same water level as the camera height.",13,MUTED,false);
        field(reference,"landmarkCoordinates","Landmark coordinates — latitude, longitude");
        button(reference,"Use LoRa GPS for landmark",()->captureCoordinates(true));
        field(reference,"landmarkHeight","Landmark height above water (m)");
        text(reference,"Pan direction",14,TEXT,false);
        panSign=spinner(reference,new String[]{"Positive motor count turns right (+1)","Negative motor count turns right (-1)"});
        text(reference,"Tilt direction",14,TEXT,false);
        tiltSign=spinner(reference,new String[]{"Positive motor count tilts up (+1)","Negative motor count tilts up (-1)"});


        landmarkButtons.add(button(p,"GO TO LAST LANDMARK",()->controller.goToLandmark()));
        landmarkStatuses.add(text(p,"Loading last saved landmark",13,MUTED,false));
        text(p,"Each successful calibration saves the landmark position across app restarts. Return, check alignment on the camera, then validate calibration. Recalibrate after moving the setup or restarting the mount.",13,MUTED,false);
        text(p,"1. Level the mount and secure the camera.\n2. Check that Right and Up move the camera in those directions.\n3. Centre the exact configured landmark point using the camera screen.\n4. Tap Calibrate here.",15,TEXT,false);
        text(p,"Before calibration, manual positioning uses moves of at most 1°. Go to last landmark is also available when a compatible saved position exists.",13,MUTED,false);
        manualStep=spinner(p,new String[]{"0.1° move","0.5° move","1° move"});
        LinearLayout pan=row(p);
        manualButtons.add(button(pan,"← Left",()->nudge(1,-1)));
        manualButtons.add(button(pan,"Right →",()->nudge(1,1)));
        LinearLayout tilt=row(p);
        manualButtons.add(button(tilt,"↓ Down",()->nudge(2,-1)));
        manualButtons.add(button(tilt,"Up ↑",()->nudge(2,1)));
        levelConfirmed=new CheckBox(this);levelConfirmed.setText("Mount is level and camera is secure");p.addView(levelConfirmed);
        directionsConfirmed=new CheckBox(this);directionsConfirmed.setText("Right / Up directions have been checked");p.addView(directionsConfirmed);
        calibrate=button(p,"CALIBRATE HERE",()->{
            log.record("calibration_requested","levelConfirmed",levelConfirmed.isChecked(),"directionsConfirmed",directionsConfirmed.isChecked(),"form",formValues());
            if(!levelConfirmed.isChecked()||!directionsConfirmed.isChecked()){
                log.event("calibration_blocked","Confirm levelling and axis directions first");
                toast("Confirm levelling and axis directions first");return;
            }
            if(autoSaveSettings())controller.calibrate();
        });
        levelConfirmed.setOnCheckedChangeListener((b,checked)->log.record("calibration_confirmation","field","level","checked",checked));
        directionsConfirmed.setOnCheckedChangeListener((b,checked)->log.record("calibration_confirmation","field","directions","checked",checked));
        calibrationInfo=text(card(p,"Reference"),"Not calibrated",15,TEXT,false);
        calibrationMap=new CalibrationMapView(this,controller::settings,controller::mapValidated);p.addView(calibrationMap);
        text(p,"Calibration is valid only for this connection. Recalibrate after moving the tripod, releasing a clutch, adjusting the camera, or restarting the mount. Disconnect VTC before making those changes.",14,MUTED,false);
        return scroll(p);
    }
    private Button aimingButton(LinearLayout p){Button b=button(p,"START AIMING",()->{if(lastView!=null&&lastView.tracking)controller.stop("User Stop aiming");else if(autoSaveSettings())controller.start();});aimingButtons.add(b);b.setTextColor(ACCENT);return b;}
    private void loraControls(LinearLayout p){LinearLayout r=row(p);button(r,"Connect LoRa",()->{if(autoSaveSettings())controller.connectLora();});button(r,"Disconnect LoRa",()->controller.disconnectLora());}
    private void captureCoordinates(boolean landmark){
        if(capturing){toast("A GPS capture is already running");return;}
        if(lastView!=null&&(lastView.connected||lastView.busy)){toast("Disconnect GTi first; LoRa can stay connected");return;}
        if(!autoSaveSettings())return;
        controller.connectLora();capturing=true;
        String label=landmark?"landmark":"camera";
        StableGpsCapture capture=new StableGpsCapture(SystemClock.elapsedRealtime());
        TextView progress=new TextView(this);progress.setPadding(dp(20),dp(20),dp(20),dp(20));progress.setText("Waiting for fresh LoRa GPS");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Capture "+label+" GPS — remain still").setView(progress).setNegativeButton("Cancel",(d,w)->{capturing=false;log.event("gps_capture_cancelled",label);}).create();
        dialog.setOnCancelListener(d->{capturing=false;log.event("gps_capture_cancelled",label);});dialog.show();
        log.record("gps_capture_started","target",label,"settings",settings.values);
        handler.post(new Runnable(){long epoch=-1;public void run(){
            if(!capturing||!dialog.isShowing())return;
            if(!screenVisible||lastView!=null&&(lastView.connected||lastView.busy)){capturing=false;dialog.dismiss();log.event("gps_capture_cancelled","Screen or mount state changed");return;}
            try{
                GpsFeed.Fix fix=controller.captureFix();long now=SystemClock.elapsedRealtime();
                if(fix!=null&&epoch!=fix.epoch){capture.reset();epoch=fix.epoch;}
                StableGpsCapture.Point point=capture.observe(fix==null?Double.NaN:fix.packet.lat,fix==null?Double.NaN:fix.packet.lon,fix==null?-1:fix.receivedAt,now);
                progress.setText(capture.status);captureStatus.setText(capture.status);
                log.record("gps_capture_progress","target",label,"fix",fix==null?null:fix.packet.fields(),"fixReceivedAtMs",fix==null?null:fix.receivedAt,"samples",capture.count(),"scatterMetres",capture.scatter,"status",capture.status);
                if(point!=null){
                    capturing=false;dialog.dismiss();String key=landmark?"landmarkCoordinates":"cameraCoordinates";
                    fillingSettings=true;fields.get(key).setText(String.format(Locale.US,"%.8f, %.8f",point.lat,point.lon));fillingSettings=false;
                    if(autoSaveSettings()){captureStatus.setText("Captured "+label+": "+fields.get(key).getText()+" · scatter "+String.format(Locale.US,"%.1f m",point.scatter));log.record("gps_capture_applied_save_queued","target",label,"latitude",point.lat,"longitude",point.lon,"scatterMetres",point.scatter,"fixes",10,"settings",settings.values);}
                    else{clearFailedCapture(landmark,"Capture could not be saved");log.event("gps_capture_save_failed",label);}
                    return;
                }
                handler.postDelayed(this,500);
            }catch(Exception e){capturing=false;dialog.dismiss();clearFailedCapture(landmark,e.getMessage());log.error("gps_capture_failed",e,"target",label);}
        }});
    }
    private void clearFailedCapture(boolean landmark,String reason){
        String label=landmark?"landmark":"camera",key=landmark?"landmarkCoordinates":"cameraCoordinates";
        handler.removeCallbacks(autoSave);fillingSettings=true;fields.get(key).setText("");fillingSettings=false;
        try{
            Map<String,String> values=new LinkedHashMap<>(controller.settings().values);
            values.put(landmark?"landmarkLat":"latitude","");values.put(landmark?"landmarkLon":"longitude","");
            Settings cleared=new Settings(values);service.saveSettings(cleared);settings=cleared;
            captureStatus.setText(reason+". "+label+" coordinates cleared; see storage status for file confirmation.");
            log.record("gps_capture_coordinates_cleared","target",label,"reason",reason,"settings",settings.values);
        }catch(Exception e){captureStatus.setText(reason+". Field cleared, but saving failed: "+e.getMessage());log.error("gps_capture_clear_failed",e,"target",label);}
    }
    private void nudge(int axis,int direction){double[] steps={.1,.5,1};controller.nudge(axis,direction*steps[manualStep.getSelectedItemPosition()]);}
    private View settingsPage() {
        LinearLayout p=scrollPage();
        setupMessage=text(p,"Valid edits save automatically while disconnected. Heights use the same water-level reference.",14,ACCENT,false);
        settingsStorageStatuses.add(text(p,"Checking settings storage",14,ACCENT,false));
        button(p,"Allow Settings folder access",()->{
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download/VTC/Settings"));startActivityForResult(intent,105);
        });
        text(p,"Only needed if Android denies access after a PC file replacement. Select Download/VTC/Settings once. VTC then saves current settings to setup.json and verifies the file automatically.",13,MUTED,false);
        LinearLayout connection=card(p,"Network");
        text(connection,"Join Wi-Fi in Android first. The GTi and LoRa receiver must both be reachable on it. Close SynScan and other LoRa viewers.",13,MUTED,false);
        field(connection,"mountHost","GTi IPv4 address");
        field(connection,"mountPort","GTi UDP port");
        field(connection,"receiverHost","LoRa receiver IPv4 address");
        field(connection,"receiverPort","LoRa control / source UDP port");
        field(connection,"receivePort","Phone receiving UDP port");
        button(connection,"Open Wi-Fi settings",()->startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)));

        LinearLayout camera=card(p,"Optional Canon camera");
        field(camera,"cameraHost","Camera IPv4 address — blank disables polling");
        field(camera,"cameraPort","CCAPI HTTP port");
        text(camera,"Zoom mode",14,TEXT,false);
        zoomMode=spinner(camera,new String[]{"Off","Bands","Auto-fit"});
        field(camera,"zoomBands","Zoom bands JSON: distance limits and zoomX");
        fields.get("zoomBands").setSingleLine(false);fields.get("zoomBands").setMinLines(8);fields.get("zoomBands").setGravity(Gravity.TOP);
        text(camera,"Bands selects zoomX by GPS distance, then multiplies it by the current zoom scale. bufferMetres prevents repeated switching near a boundary. Auto-fit uses desired frame width. Zoom scales and their timer apply to both modes.",13,MUTED,false);
        text(camera,"Off leaves the camera zoom exactly as it is: no zoom commands, no automatic adjustments. VTC continues reading and logging zoom.",13,MUTED,false);
        field(camera,"zoom_scales","Zoom scales (comma-separated; 0.5–3.0, increments 0.1)");
        field(camera,"zoomSwitchSeconds","Seconds per zoom scale (2–3600)");
        field(camera,"desiredFrameWidthMetres","Desired frame width (metres; 1–10000)");
        field(camera,"wideFrameFactor","Wide frame factor (0.01–10; default 1.648)");
        field(camera,"zoomMapping","Zoom mapping JSON: zoomX / ccapiValue points");
        fields.get("zoomMapping").setSingleLine(false);fields.get("zoomMapping").setMinLines(8);fields.get("zoomMapping").setGravity(Gravity.TOP);
        text(camera,"Auto-fit calculates zoom from GPS distance and desired width. Scale 1 uses that width; scale 2 halves it. One scale holds; multiple scales cycle. Mapping accepts 2–1000 points beginning at 1x. zoomX must increase; ccapiValue may repeat but must not decrease. Uses logarithmic interpolation/extrapolation. Adjustments allow 5% width error to avoid zoom chatter; checked on the independent camera poll (at least 2 seconds apart).",13,MUTED,false);
        text(camera,"Zoom uses the configured calibration mapping. Frame widths remain estimates. Camera limits are enforced. Fresh GPS is required for automatic zoom.",13,MUTED,false);
        field(camera,"manualZoomX","Manual camera zoom (×; optional observation, not a command)");
        button(camera,"Log manual zoom observation",()->{if(autoSaveSettings())service.camera.manualObservation("user_record");});
        text(camera,"Select Off, set the zoom on your camera and enter its displayed × value. The observation logs the latest CCAPI reading and its age. These fields can be edited while aiming.",13,MUTED,false);
        text(camera,"Reads zoom and synchronises the clock once per connection, independently of aiming. Camera errors do not stop tracking. Use the camera IP shown on your shared Wi-Fi network.",13,MUTED,false);
        LinearLayout frame=card(p,"Framing");
        text(frame,"Always automatic. Magnitude 0 means no horizontal offset.",13,MUTED,false);
        field(frame,"magnitudeMetres","Automatic horizontal offset magnitude (m)");
        field(frame,"tiltOffset","Vertical offset (°; + up)");
        field(frame,"hfov","Optional horizontal field of view (°; blank if unknown)");
        text(frame,"An automatic +offset aims right and places the surfer left. Field of view is optional: it only estimates frame width and draws the KML viewing sector. It does not restrict offsets or control aiming. Leave blank if unknown. Camera zoom is not converted into field of view automatically.",13,MUTED,false);

        LinearLayout limits=card(p,"Movement limits");
        text(limits,"Pan limits are relative to the calibrated landmark. Tilt limits are angles above / below horizontal. Set them to clear your tripod and cables.",13,MUTED,false);
        field(limits,"panMin","Left limit from landmark (°)");
        field(limits,"panMax","Right limit from landmark (°)");
        field(limits,"tiltMin","Minimum tilt (°)");
        field(limits,"tiltMax","Maximum tilt (°)");
        field(limits,"rangeMin","Minimum surfer range (m)");
        field(limits,"rangeMax","Maximum surfer range (m)");

        LinearLayout acquire=card(p,"GoTo for large aiming errors");
        field(acquire,"acquisitionTriggerMetres","Acquisition trigger pointing error (m)");
        field(acquire,"gotoMaxMove","Maximum GoTo movement per leg (°; 1–45)");
        text(acquire,"Pointing error in metres is converted using current surfer distance. Default: about 7.49 m triggers fast native GoTo. The target position and framing are saved once and held until arrival, even if surfer GPS changes or stops. Larger turns use legs toward the same saved position. Live GPS aiming resumes after arrival.",13,MUTED,false);

        LinearLayout advanced=card(p,"Advanced aiming");
        text(advanced,"Low-speed correction varies up to 0.5°/s. High-speed catch-up holds the configured maximum, then returns to low speed near the target. Tolerances are metres at the surfer distance. Stop within tolerance; restart beyond 1.5× that metre tolerance.",13,MUTED,false);
        field(advanced,"maxPanningSpeed","Maximum panning speed (°/s; 0.01–5)");
        text(advanced,"Also caps tilt. AZ-GTi commands are capped at 3.34°/s (800× sidereal). High-speed catch-up uses one fixed rate. Switching modes or reversing still requires Stop/Start. Default remains 0.5°/s.",13,MUTED,false);
        field(advanced,"gpsGapTimeout","Keep panning without GPS for (s since last accepted packet)");
        field(advanced,"panToleranceMetres","Pan aiming tolerance (m)");
        field(advanced,"tiltToleranceMetres","Tilt aiming tolerance (vertical m at surfer distance)");
        field(advanced,"reverseSeconds","Direction reversal hold (s)");
        field(advanced,"gpsAge","Maximum GPS age / evidence gap (s)");
        field(advanced,"maxGpsSpeed","Reject GPS jumps above (m/s)");

        LinearLayout auto=card(p,"Automatic offset detection");
        field(auto,"window","Direction observation window (s)");
        field(auto,"observations","Minimum GPS observations");
        field(auto,"minNet","Minimum net bearing change (°)");
        field(auto,"consistency","Direction consistency (0.5–1)");
        field(auto,"supports","Minimum separate supporting changes");
        field(auto,"supportChange","Minimum supporting change (°)");
        field(auto,"quietRate","Quiet total angular travel rate (°/s)");
        field(auto,"quietSeconds","Continuous quiet before centring (s)");
        field(auto,"offsetRate","Offset transition rate (°/s; 0 = immediate, default 0)");

        LinearLayout filesRow=row(p);
        button(filesRow,"Export setup",this::exportSetup);
        button(filesRow,"Import setup",()->{
            if(lastView!=null&&(lastView.connected||lastView.busy)){toast("Disconnect before importing settings");return;}
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("application/json");intent.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,102);
        });
        return scroll(p);
    }
    private View logsPage() {
        LinearLayout p=scrollPage();
        logStatus=text(p,"Opening automatic full log in Download/VTC/logs",14,TEXT,false);
        text(p,"Full logging is always on. Each session writes directly to Download/VTC/logs and flushes continuously. The file is available while VTC is running; no Save or Export is needed.",15,TEXT,false);
        text(p,"A matching KML updates automatically in Download/VTC/logs: camera position, surfer points/track, timestamped camera heading and tilt, field-of-view sector, and saved GoTo targets. Open it in Google Earth and use the time slider. Aiming lines are calculated from calibration and motor positions; they do not verify the camera image.",14,MUTED,false);
        text(p,"Records include all saved settings and edits, button actions, calibration checks and failures, raw GPS datagrams, parsed telemetry, mount commands/replies, motor positions, aiming/framing decisions, and a status snapshot every second—even while waiting for calibration.",14,MUTED,false);
        text(p,"If public storage fails, VTC keeps a private recovery copy and automatically retries writing the full session to Download/VTC/logs. Storage errors and dropped-record counts appear here.",14,MUTED,false);
        text(p,"Verification\n• Build and automated checks cover software logic.\n• Landmark accuracy, motor directions, backlash and tracking smoothness need a hardware check.\n• No guarantee of a mount communication-loss watchdog is assumed. Continuous movement needs Stop. The independent watchdog attempts Stop on a control stall; lost power, force-stop or lost Wi-Fi can prevent it.\n• Aiming continues when switching apps or locking the screen. Use Stop in VTC or its notification. Force-stop/process termination ends the service; reconnect and validate calibration after a new service session.",14,MUTED,false);
        return scroll(p);
    }
    private String prettyBands(String json){try{return json.trim().startsWith("{")?new org.json.JSONObject(json).toString(2):new org.json.JSONArray(json).toString(2);}catch(Exception e){return json;}}
    private void fillSettings(Settings s) {
        fillingSettings=true;
        for(Map.Entry<String,EditText> e:fields.entrySet())if(s.values.containsKey(e.getKey()))e.getValue().setText(e.getKey().equals("zoom_scales")?s.values.get(e.getKey()).replace("[", "").replace("]", ""):(e.getKey().equals("zoomMapping")||e.getKey().equals("zoomBands"))?prettyBands(s.values.get(e.getKey())):s.values.get(e.getKey()));
        fields.get("cameraCoordinates").setText(Coordinates.display(s.values.get("latitude"),s.values.get("longitude")));
        fields.get("landmarkCoordinates").setText(Coordinates.display(s.values.get("landmarkLat"),s.values.get("landmarkLon")));
        zoomMode.setSelection(s.zoomMode.equals("off")?0:s.zoomMode.equals("bands")?1:2);
        panSign.setSelection(s.panSign>0?0:1);tiltSign.setSelection(s.tiltSign>0?0:1);
        fillingSettings=false;
    }
    private Map<String,String> formValues() {
        Map<String,String> values=new LinkedHashMap<>();
        for(Map.Entry<String,EditText> e:fields.entrySet())values.put(e.getKey(),e.getValue().getText().toString());
        values.put("zoomMode",new String[]{"off","bands","auto-fit"}[zoomMode.getSelectedItemPosition()]);
        values.put("panSign",panSign.getSelectedItemPosition()==0?"1":"-1");
        values.put("tiltSign",tiltSign.getSelectedItemPosition()==0?"1":"-1");
        return values;
    }
    private Settings readSettings() {
        Map<String,String> values=new LinkedHashMap<>(settings.values);values.putAll(formValues());
        String[] camera=Coordinates.parse(values.remove("cameraCoordinates"));
        String[] landmark=Coordinates.parse(values.remove("landmarkCoordinates"));
        values.put("latitude",camera[0]);values.put("longitude",camera[1]);
        values.put("landmarkLat",landmark[0]);values.put("landmarkLon",landmark[1]);
        return new Settings(values);
    }
    private void scheduleSave(){if(!fillingSettings){handler.removeCallbacks(autoSave);handler.postDelayed(autoSave,600);}}
    private boolean autoSaveSettings() {
        handler.removeCallbacks(autoSave);
        if(fillingSettings||service==null)return false;
        
        try {
            Settings next=readSettings();
            if(lastView!=null&&(lastView.connected||lastView.busy)&&!settings.onlyZoomChanged(next))
                throw new IllegalArgumentException("Only zoom settings can change while connected");
            if(!next.values.equals(settings.values)){service.saveSettings(next);settings=next;log.settings("settings_applied_save_queued",next);}
            settingsValidationError="";setupMessage.setText("Valid edits apply automatically. File save status is shown below.");refreshSettingsStatus();return true;
        }catch(Exception e){settingsValidationError="Not applied: "+e.getMessage()+". Last valid settings retained.";setupMessage.setText(settingsValidationError);refreshSettingsStatus();return false;}
    }
    private void exportSetup() {
        try {
            Settings next=readSettings();
            exportSettingsText=new JSONObject(ConfigJson.structured(next.values)).toString(2);
            createDocument("VTC-setup.json","application/json");
        }catch(Exception e){toast(e.getMessage());}
    }
    private void createDocument(String name,String type) {
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(type);intent.putExtra(Intent.EXTRA_TITLE,name);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download/VTC/Settings"));
        startActivityForResult(intent,101);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        if(request==105){
            try{service.useSettingsFolder(uri,data.getFlags());toast("Folder access saved. Check settings storage status below.");}catch(Exception e){toast(e.getMessage());}
        }else if(request==101) {
            String content=exportSettingsText;
            files.execute(()->{
                try {
                    try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Cannot open destination");out.write(content.getBytes(StandardCharsets.UTF_8));}
                    log.event("settings_exported",uri.toString());
                    runOnUiThread(()->toast("Export saved"));
                }catch(Exception e){log.error("settings_export_failed",e);runOnUiThread(()->toast("Export failed: "+e.getMessage()));}
            });
        }else if(request==102) {
            files.execute(()->{
                try(InputStream in=getContentResolver().openInputStream(uri)) {
                    if(in==null)throw new IOException("Cannot open setup file");
                    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
                    while((count=in.read(buffer))!=-1){out.write(buffer,0,count);if(out.size()>32768)throw new IOException("Setup file is too large");}
                    Map<String,String> input=ConfigJson.settings(out.toString(StandardCharsets.UTF_8.name()));
                    Settings imported=new Settings(input);
                    log.settings("import_for_review",imported);
                    runOnUiThread(()->{fillSettings(imported);autoSaveSettings();showPage(2);});
                }catch(Exception e){log.error("settings_import_failed",e);runOnUiThread(()->toast("Import failed: "+e.getMessage()));}
            });
        }
    }
    private void render(VtcController.View v) {
        if(isDestroyed())return;lastView=v;
        state.setText(v.state);state.setTextColor(v.tracking?ACCENT:
            v.state.equals("CONNECTION CLOSED")||v.state.equals("AIMING STOPPED")?Color.rgb(255,132,132):TEXT);
        connections.setText(v.connections);
        message.setText(v.message);mountInfo.setText(v.mount);gpsInfo.setText(controller.liveGps());aimInfo.setText(v.aim);frameInfo.setText(v.framing);calibrationInfo.setText(v.calibration);
        connect.setEnabled(!v.connected&&!v.busy);disconnect.setEnabled(v.connected||v.busy);
        for(Button button:aimingButtons){button.setEnabled(v.tracking||v.canStart);button.setText(v.tracking?"STOP AIMING":"START AIMING");}
        calibrate.setEnabled(v.connected&&!v.tracking&&!v.busy&&!v.moving);
        for(Button b:manualButtons)b.setEnabled(v.connected&&!v.tracking&&!v.busy&&!v.moving);
        for(Button b:landmarkButtons)b.setEnabled(v.canGoToLandmark);
        for(TextView t:landmarkStatuses)t.setText(v.lastLandmark);
        boolean editing=!v.connected&&!v.busy;
        for(Map.Entry<String,EditText> entry:fields.entrySet())entry.getValue().setEnabled(editing||Settings.liveZoomKey(entry.getKey()));
        panSign.setEnabled(editing);tiltSign.setEnabled(editing);
        if(!v.connected){levelConfirmed.setChecked(false);directionsConfirmed.setChecked(false);}
        refreshSettingsStatus();
        logStatus.setText(log.status()+"\n\n"+service.setupStatus());
        cameraInfo.setText(service.camera.status());
        if(screenVisible&&page==1&&calibrationMap!=null)calibrationMap.refreshIfChanged();
    }
    private void showPage(int index){page=index;if(index==1&&calibrationMap!=null)calibrationMap.refresh();body.removeAllViews();body.addView(pages[index],new LinearLayout.LayoutParams(-1,-1));log.record("ui_page","index",index);}
    private void field(LinearLayout parent,String key,String label) {
        text(parent,label,14,TEXT,false);
        EditText e=new EditText(this);e.setSingleLine();e.setTextSize(16);e.setTextColor(TEXT);e.setHintTextColor(MUTED);
        boolean coordinates=key.endsWith("Coordinates");
        e.setInputType(key.endsWith("Host")||coordinates||key.equals("zoom_scales")||(key.equals("zoomMapping")||key.equals("zoomBands"))?InputType.TYPE_CLASS_TEXT:InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        e.setHint(coordinates?"-28.165514, 153.550457":key.equals("height")||key.equals("landmarkHeight")?"Required before calibration":"");
        e.setOnFocusChangeListener((v,focused)->{if(!focused)log.record("settings_field_edited","field",key,"value",e.getText().toString(),"saved",false);});
        e.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){}
            public void afterTextChanged(android.text.Editable text){scheduleSave();}
        });
        parent.addView(e,new LinearLayout.LayoutParams(-1,-2));fields.put(key,e);
    }
    private LinearLayout card(LinearLayout parent,String title) {
        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(14),dp(12),dp(14),dp(14));
        GradientDrawable bg=new GradientDrawable();bg.setColor(CARD);bg.setCornerRadius(dp(12));c.setBackground(bg);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(10),0,dp(4));parent.addView(c,lp);
        text(c,title,18,ACCENT,true);return c;
    }
    private LinearLayout row(LinearLayout parent){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);parent.addView(r,new LinearLayout.LayoutParams(-1,-2));return r;}
    private Button button(LinearLayout parent,String label,Runnable action) {
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setMinHeight(dp(48));
        b.setOnClickListener(v->{log.record("ui_action","button",label,"page",page);action.run();});
        parent.addView(b,parent.getOrientation()==LinearLayout.HORIZONTAL?new LinearLayout.LayoutParams(0,-2,1):new LinearLayout.LayoutParams(-1,-2));return b;
    }
    private TextView text(LinearLayout parent,String value,int size,int color,boolean bold) {
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(3),0,dp(5));t.setLineSpacing(dp(2),1);
        if(bold)t.setTypeface(null,Typeface.BOLD);parent.addView(t,new LinearLayout.LayoutParams(-1,-2));return t;
    }
    private Spinner spinner(LinearLayout p,String[] entries){
        Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,entries));
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){log.record("ui_selection","options",entries,"selectedIndex",position,"selectedValue",entries[position]);scheduleSave();}
            public void onNothingSelected(AdapterView<?> parent){}
        });
        p.addView(s,new LinearLayout.LayoutParams(-1,dp(52)));return s;
    }
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void refreshTelemetry(){
        if(isDestroyed()||service==null)return;
        if(screenVisible&&controller!=null){String gps=controller.liveGps();gpsInfo.setText(gps);calibrationGps.setText(gps);}
        handler.postDelayed(this::refreshTelemetry,250);
    }
    private void refreshSettingsStatus(){String value=settingsValidationError.isEmpty()?service.setupStatus():settingsValidationError+"\n"+service.setupStatus();for(TextView status:settingsStorageStatuses)status.setText(value);}
    private void refreshLogStatus(){
        if(isDestroyed()||service==null)return;
        refreshSettingsStatus();
        logStatus.setText(log.status()+"\n\n"+service.setupStatus());
        cameraInfo.setText(service.camera.status());
        if(!screenVisible){handler.postDelayed(this::refreshLogStatus,1000);return;}
        VtcController.View v=lastView;
        log.record("ui_status_snapshot","page",page,"form",formValues(),
            "levelConfirmed",levelConfirmed.isChecked(),"directionsConfirmed",directionsConfirmed.isChecked(),
            "state",v==null?null:v.state,"message",v==null?null:v.message,"aim",v==null?null:v.aim,
            "connections",v==null?null:v.connections,
            "mount",v==null?null:v.mount,"gps",controller==null?null:controller.liveGps(),"calibration",v==null?null:v.calibration,
            "startEnabled",start.isEnabled(),"calibrateEnabled",calibrate.isEnabled(),"settingsStorage",service.setupStatus(),"logStatus",log.status());
        handler.postDelayed(this::refreshLogStatus,1000);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==103&&log!=null)log.record("storage_permission_result","permissions",permissions,"grants",grants);
    }
    @Override protected void onResume(){super.onResume();screenVisible=true;if(log!=null)log.event("activity_resume","foreground");if(controller!=null)controller.foreground(true);}
    @Override protected void onPause(){if(service!=null)autoSaveSettings();screenVisible=false;if(log!=null)log.record("activity_pause","form",formValues());if(controller!=null)controller.foreground(false);super.onPause();}
    @Override protected void onDestroy(){
        if(service!=null)service.detach(listener);if(bound)unbindService(serviceConnection);
        if(calibrationMap!=null)calibrationMap.close();
        files.shutdown();handler.removeCallbacksAndMessages(null);super.onDestroy();
    }
}

