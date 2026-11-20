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
    private static final double[] MANUAL_STEPS={.1,.5,1,5,15,30};
    private static final int FINE_STEP_COUNT=3;
    private final Map<String,EditText> fields=new LinkedHashMap<>();
    private final List<Button> manualButtons=new ArrayList<>();
    private final List<Button> landmarkButtons=new ArrayList<>();
    private final List<TextView> landmarkStatuses=new ArrayList<>();
    private final ExecutorService files=Executors.newSingleThreadExecutor();
    private final ExecutorService discoveryWorker=Executors.newSingleThreadExecutor();
    private boolean findingDevice;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Settings settings;
    private Map<String,String> formBaseline=Collections.emptyMap();
    private SessionLog log;
    private VtcService service;
    private boolean bound,screenVisible,fillingSettings;
    private final Runnable autoSave=this::autoSaveSettings;
    private TextView cameraInfo;
    private TextView previewStatus;
    private ImageView previewImage;
    private Button previewUpdate;
    private final VtcController.Listener listener=this::render;
    private final ServiceConnection serviceConnection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){
            service=((VtcService.LocalBinder)binder).service();controller=service.controller;log=service.log;settings=controller.settings();
            if(service.exiting()){finishVtc();return;}
            buildUi();fillSettings(settings);showPage(0);service.attach(listener);controller.foreground(screenVisible);refreshLogStatus();refreshTelemetry();
        }
        public void onServiceDisconnected(ComponentName name){controller=null;service=null;handler.removeCallbacksAndMessages(null);
            TextView error=new TextView(MainActivity.this);error.setText("VTC service interrupted. Reopen VTC, reconnect and validate calibration.");setContentView(error);}
    };
    private VtcController controller;
    private LinearLayout root,body;
    private View[] pages;
    private TextView state,message,connections,mountInfo,gpsInfo,aimInfo,frameInfo,calibrationInfo,setupMessage,logStatus;
    private Button connect,disconnect,start,calibrate,landmarkCalibrate;
    private final List<Button> loraConnectButtons=new ArrayList<>(),loraDisconnectButtons=new ArrayList<>();
    private android.animation.ObjectAnimator aimStatusPulse;
    private static final int STATUS_RED=Color.rgb(255,132,132);
    private final List<Button> aimingButtons=new ArrayList<>();
    private boolean capturing;
    private TextView captureStatus,calibrationGps,offshoreHeadingStatus;
    private Button saveOffshoreHeading;
    private final List<TextView> settingsStorageStatuses=new ArrayList<>();
    private String settingsValidationError="";
    private static final int LIVE_MAP_PAGE=5, REPLAY_FILE_REQUEST=106;
    private LiveMapView liveMap;
    private TextView calibrationConnectionHint;
    private boolean replayPending;
    private Spinner panSign,tiltSign,zoomMode,wavePanStep,gotoMode;
    private boolean wavePanCalibrated;
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
        text(root,"VTC  "+SessionLog.VERSION,26,TEXT,true);
        text(root,"GPS-guided shore camera mount",13,MUTED,false);
        state=text(root,"DISCONNECTED",18,TEXT,true);
        connections=text(root,"",13,TEXT,false);
        renderConnectionStatus("GTi: Disconnected\nLoRa: Disconnected\nCalibration: Required",false,false);
        message=text(root,"Set up the site and connect.",14,MUTED,false);
        LinearLayout nav=row(root);
        String[] labels={"Track","Calibrate","Setup","Logs","Wave Ride","Live Map","Camera Preview"};
        for(int i=0;i<labels.length;i++){
            if(i==4)nav=row(root);
            final int n=i;
            Button tab=button(nav,labels[i],()->showPage(n));
            tab.setTextSize(12);tab.setPadding(dp(2),0,dp(2),0);
        }
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        pages=new View[]{trackPage(),calibrationPage(),settingsPage(),logsPage(),waveRidePage(),liveMapPage(),cameraPreviewPage()};
        button(root,"STOP & EXIT VTC",()->{
            autoSaveSettings();
            service.exit(this::finishVtc);
        });
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
        cameraInfo=text(card(p,"Camera"),"Camera disabled",14,MUTED,false);
        LinearLayout m=card(p,"Mount");mountInfo=text(m,"Disconnected",14,MUTED,false);
        return scroll(p);
    }
    private void finishVtc() {
        if(service!=null)service.detach(listener);
        if(bound){unbindService(serviceConnection);bound=false;}
        finishAndRemoveTask();
    }
    private View calibrationPage() {
        LinearLayout p=scrollPage();
        aimingButton(p);loraControls(p);
        button(p,"See calibration in Google Maps",()->{if(autoSaveSettings())CalibrationLauncher.maps(this,controller.settings());});
        button(p,"See calibration in Google Earth",()->{if(autoSaveSettings())CalibrationLauncher.earth(this,controller.settings(),controller.mapValidated());});
        text(p,"Uses current saved camera and landmark coordinates, including LoRa captures. Maps shows directions; Earth shows both points and their bearing line. No calibration or API key is required to view coordinates.",13,MUTED,false);
        calibrationGps=text(card(p,"LoRa GPS for capture"),"LoRa disconnected",14,MUTED,false);
        settingsStorageStatuses.add(text(p,"Checking settings storage",14,ACCENT,false));
        captureStatus=text(p,"Stop aiming and wait for the mount to stop before editing reference settings or capturing GPS. Keep LoRa connected and still during capture.",14,ACCENT,false);
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
        text(p,"A saved landmark can be used for automatic calibration when the mount and camera are still aligned with the saved setup.",13,MUTED,false);
        text(p,"1. Level the mount and secure the camera.\n2. Check that Right and Up move the camera in those directions.\n3. Centre the exact configured landmark point using the camera screen.\n4. Tap Calibrate here.",15,TEXT,false);
        text(p,"Before calibration, manual positioning uses moves of at most 1°. Go to last landmark is also available when a compatible saved position exists.",13,MUTED,false);
        Spinner step=manualStep(p);
        panButtons(p,step,"← Left","Right →");
        LinearLayout tilt=row(p);
        manualButtons.add(button(tilt,"↓ Down",()->nudge(2,-1,step)));
        manualButtons.add(button(tilt,"Up ↑",()->nudge(2,1,step)));
        levelConfirmed=new CheckBox(this);levelConfirmed.setText("Mount is level and camera is secure");p.addView(levelConfirmed);
        directionsConfirmed=new CheckBox(this);directionsConfirmed.setText("Right / Up directions have been checked");p.addView(directionsConfirmed);
        calibrationConnectionHint=text(p,"Connect the GTi to calibrate",14,ACCENT,false);
        text(p,"LoRa is only needed here to capture GPS coordinates. You can enter the coordinates manually.",13,MUTED,false);
        calibrate=button(p,"Calibrate here",()->requestCalibration(false));
        landmarkCalibrate=button(p,"Go to landmark and calibrate here",()->requestCalibration(true));
        landmarkCalibrate.setEnabled(false);
        text(p,"After calibration, VTC pans to the saved offshore heading when it is within the pan limits.",13,MUTED,false);
        levelConfirmed.setOnCheckedChangeListener((b,checked)->log.record("calibration_confirmation","field","level","checked",checked));
        directionsConfirmed.setOnCheckedChangeListener((b,checked)->log.record("calibration_confirmation","field","directions","checked",checked));
        calibrationInfo=text(card(p,"Reference"),"Not calibrated",15,TEXT,false);
        text(p,"Calibration is valid only for this connection. Recalibrate after moving the tripod, releasing a clutch, adjusting the camera, or restarting the mount. Disconnect VTC before making those changes.",14,MUTED,false);
        return scroll(p);
    }
    private void requestCalibration(boolean returnToLandmark) {
        log.record("calibration_requested","returnToLandmark",returnToLandmark,"levelConfirmed",levelConfirmed.isChecked(),
            "directionsConfirmed",directionsConfirmed.isChecked(),"form",formValues());
        if(!levelConfirmed.isChecked()||!directionsConfirmed.isChecked()){
            log.event("calibration_blocked","Confirm levelling and axis directions first");
            toast("Confirm levelling and axis directions first");return;
        }
        if(!autoSaveSettings())return;
        if(returnToLandmark)controller.goToLandmark(true);
        else controller.calibrate();
    }
    private Button aimingButton(LinearLayout p){Button b=button(p,"START AIMING",()->{if(lastView!=null&&lastView.tracking)controller.stop("User Stop aiming");else if(autoSaveSettings())controller.start();});aimingButtons.add(b);return b;}
    private void loraControls(LinearLayout p){
        LinearLayout r=row(p);
        loraConnectButtons.add(button(r,"Connect LoRa",()->{if(autoSaveSettings())controller.connectLora();}));
        loraDisconnectButtons.add(button(r,"Disconnect LoRa",()->controller.disconnectLora()));
    }

    private void renderConnectionStatus(String details,boolean cameraConnected,boolean calibrated){
        android.text.SpannableStringBuilder status=new android.text.SpannableStringBuilder();
        for(String line:details.split("\n")){
            if(line.startsWith("Calibration:"))
                appendStatus(status,"CAM: "+(cameraConnected?"Connected":"Disconnected"),cameraConnected);
            boolean ready=line.startsWith("GTi: Connected")||line.startsWith("LoRa: Receiver responding")
                ||(line.startsWith("Calibration:")&&calibrated);
            appendStatus(status,line,ready);
        }
        connections.setText(status);
    }

    private void appendStatus(android.text.SpannableStringBuilder status,String line,boolean ready){
        if(status.length()>0)status.append('\n');
        int start=status.length();
        status.append(line);
        status.setSpan(new android.text.style.ForegroundColorSpan(ready?ACCENT:STATUS_RED),
            start,status.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void updateCameraConnectionControls(){
        boolean configured=!controller.settings().cameraHost.isEmpty();
        connect.setText(configured?"Connect GTi + LoRa + CAM":"Connect GTi + LoRa");
        disconnect.setText(configured&&controller.disconnectDropsCamera(true)?"Disconnect GTi + CAM":"Disconnect GTi");
        for(Button button:loraConnectButtons)button.setText(configured?"Connect LoRa + CAM":"Connect LoRa");
        for(Button button:loraDisconnectButtons)
            button.setText(configured&&controller.disconnectDropsCamera(false)?"Disconnect LoRa + CAM":"Disconnect LoRa");
    }
    private void captureCoordinates(boolean landmark){
        if(capturing){toast("A GPS capture is already running");return;}
        if(!controller.canEditMountSettings()){toast("Stop aiming and wait for the mount to stop before capturing GPS");return;}
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
            if(!screenVisible||!controller.canEditMountSettings()){capturing=false;dialog.dismiss();log.event("gps_capture_cancelled","Screen or mount state changed");return;}
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
            Settings cleared=new Settings(values);service.saveSettings(cleared);fillSettings(cleared);
            captureStatus.setText(reason+". "+label+" coordinates cleared; see storage status for file confirmation.");
            log.record("gps_capture_coordinates_cleared","target",label,"reason",reason,"settings",settings.values);
        }catch(Exception e){captureStatus.setText(reason+". Field cleared, but saving failed: "+e.getMessage());log.error("gps_capture_clear_failed",e,"target",label);}
    }
    private Spinner manualStep(LinearLayout parent) {
        return spinner(parent,manualStepLabels(FINE_STEP_COUNT));
    }
    private static String[] manualStepLabels(int count) {
        String[] labels=new String[count];
        for(int i=0;i<count;i++){
            double degrees=MANUAL_STEPS[i];
            labels[i]=String.format(Locale.US,degrees<1?"%.1f° per press":"%.0f° per press",degrees);
        }
        return labels;
    }
    private void updateWavePanSteps(boolean calibrated) {
        wavePanCalibrated=calibrated;
        String[] labels=manualStepLabels(calibrated?MANUAL_STEPS.length:FINE_STEP_COUNT);
        wavePanStep.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
        wavePanStep.setSelection(calibrated?4:2);
    }
    private void panButtons(LinearLayout parent,Spinner step,String anticlockwise,String clockwise) {
        LinearLayout pan=row(parent);
        manualButtons.add(button(pan,anticlockwise,()->nudge(1,-1,step)));
        manualButtons.add(button(pan,clockwise,()->nudge(1,1,step)));
    }
    private void nudge(int axis,int direction,Spinner step) {
        int index=step.getSelectedItemPosition();
        if(index>=0&&index<MANUAL_STEPS.length)controller.nudge(axis,direction*MANUAL_STEPS[index]);
    }
    private View settingsPage() {
        LinearLayout p=scrollPage();
        setupMessage=text(p,"Valid edits save automatically. Stop aiming and wait for the mount to stop before editing mount or reference settings.",14,ACCENT,false);
        settingsStorageStatuses.add(text(p,"Checking settings storage",14,ACCENT,false));
        button(p,"Allow Settings folder access",()->{
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download/VTC/Settings"));startActivityForResult(intent,105);
        });
        text(p,"Only needed if Android denies access after a PC file replacement. Select Download/VTC/Settings once. VTC then saves current settings to setup.json and verifies the file automatically.",13,MUTED,false);
        LinearLayout connection=card(p,"Network");
        text(connection,"Join Wi-Fi in Android first. The GTi and LoRa receiver must both be reachable on it. Close SynScan and other LoRa viewers.",13,MUTED,false);
        deviceAddress(connection,"mountHost","GTi IPv4 address",true);
        field(connection,"mountPort","GTi UDP port");
        field(connection,"receiverHost","LoRa receiver IPv4 address");
        field(connection,"receiverPort","LoRa control / source UDP port");
        field(connection,"receivePort","Phone receiving UDP port");
        button(connection,"Open Wi-Fi settings",()->startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)));

        LinearLayout camera=card(p,"Optional Canon camera");
        deviceAddress(camera,"cameraHost","Camera IPv4 address — blank disables polling",false);
        field(camera,"cameraPort","CCAPI HTTP port");
        field(camera,"maxRecordingMinutes","Maximum recording length (minutes, 0 = no rollover)");
        text(camera,"Zoom mode",14,TEXT,false);
        zoomMode=spinner(camera,new String[]{"Off","Bands","Auto-fit"});
        text(camera,"Edit zoom bands, zoom mapping and zoom scales in setup.json. Off leaves the lens where it is.",13,MUTED,false);
        field(camera,"zoomSwitchSeconds","Seconds per zoom scale (2–3600)");
        field(camera,"desiredFrameWidthMetres","Desired frame width (metres; 1–10000)");
        field(camera,"wideFrameFactor","Wide frame factor (0.01–10; default 1.648)");
        text(camera,"Bands and Auto-fit adjust zoom after Start Aiming. Stopping aiming leaves the zoom in place and readback continues.",13,MUTED,false);
        field(camera,"manualZoomX","Manual camera zoom (×; optional observation, not a command)");
        button(camera,"Log manual zoom observation",()->{if(autoSaveSettings())service.camera.manualObservation("user_record");});
        text(camera,"Select Off, set the zoom on your camera and enter its displayed × value. The observation logs the latest CCAPI reading and its age. These fields can be edited while aiming.",13,MUTED,false);
        text(camera,"Reads zoom and synchronises the clock once per connection, independently of aiming. Camera errors do not stop tracking. Use the camera IP shown on your shared Wi-Fi network.",13,MUTED,false);
        field(camera,"tiltOffset","Vertical aiming offset (°; + up)");

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
        text(acquire,"GoTo replies",14,MUTED,true);
        gotoMode=spinner(acquire,new String[]{"Acknowledged","No-wait (experimental)"});
        text(acquire,"No-wait sends GoTo immediately and logs replies in the background. Encoder reads still wait for positions.",14,MUTED,false);
        field(acquire,"gotoMaxMove","Maximum GoTo movement per leg (°; 1–45)");
        text(acquire,"Pointing error in metres is converted using current surfer distance. Default: 3 m triggers fast native GoTo. The target position and framing are saved once and held until arrival, even if surfer GPS changes or stops. Larger turns use legs toward the same saved position. Live GPS aiming resumes after arrival.",13,MUTED,false);

        LinearLayout advanced=card(p,"Advanced aiming");
        text(advanced,"Low-speed correction varies up to 0.5°/s. High-speed catch-up holds the configured maximum, then returns to low speed near the target. Tolerances are metres at the surfer distance. Stop within tolerance; restart beyond 1.5× that metre tolerance.",13,MUTED,false);
        field(advanced,"maxPanningSpeed","Maximum panning speed (°/s; 0.01–5)");
        text(advanced,"Also caps tilt. AZ-GTi commands are capped at 3.34°/s (800× sidereal). High-speed catch-up uses one fixed rate. Switching modes or reversing still requires Stop/Start.",13,MUTED,false);
        field(advanced,"gpsGapTimeout","Keep panning without GPS for (s since last accepted packet)");
        field(advanced,"panToleranceMetres","Pan aiming tolerance (m)");
        field(advanced,"tiltToleranceMetres","Tilt aiming tolerance (vertical m at surfer distance)");
        field(advanced,"reverseSeconds","Direction reversal hold (s)");
        field(advanced,"gpsAge","Maximum GPS age / evidence gap (s)");
        field(advanced,"maxGpsSpeed","Reject GPS jumps above (m/s)");

        LinearLayout filesRow=row(p);
        button(filesRow,"Export setup",this::exportSetup);
        button(filesRow,"Import setup",()->{
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("application/json");intent.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,102);
        });
        return scroll(p);
    }
    private View waveRidePage() {
        LinearLayout p=scrollPage();
        LinearLayout pan=card(p,"Position the camera");
        text(pan,"Connect the GTi and stop aiming to pan manually.",13,MUTED,false);
        wavePanStep=new Spinner(this);
        pan.addView(wavePanStep,new LinearLayout.LayoutParams(-1,dp(52)));
        updateWavePanSteps(false);
        wavePanStep.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                log.record("wave_pan_step_selected","degrees",MANUAL_STEPS[position]);
            }
            public void onNothingSelected(AdapterView<?> parent){}
        });
        panButtons(pan,wavePanStep,"Pan anticlockwise","Pan clockwise");
        text(pan,"After calibration, point the camera straight offshore and wait for the mount to stop.",13,MUTED,false);
        offshoreHeadingStatus=text(pan,"No offshore heading saved",14,TEXT,false);
        saveOffshoreHeading=button(pan,"Save offshore heading",this::saveOffshoreHeading);
        saveOffshoreHeading.setEnabled(false);
        settingsStorageStatuses.add(text(pan,"Checking settings storage",14,ACCENT,false));
        LinearLayout ride=card(p,"Wave Ride");
        field(ride,"rideStartKmh","Ride start speed (km/h)");
        field(ride,"rideStartConfirmations","Consecutive speed estimates to start");
        field(ride,"rideEndKmh","Ride end speed (km/h)");
        field(ride,"rideEndSeconds","Time below end speed (seconds)");
        field(ride,"magnitudeMetres","Framing offset during a ride (m; 0 = off)");
        text(ride,"Ride speed counts shoreward and alongshore movement. Framing follows the surfer directly between rides.",13,MUTED,false);
        return scroll(p);
    }

    private void showOffshoreHeading(Settings s) {
        offshoreHeadingStatus.setText(s.offshoreHeading==null
            ? "No offshore heading saved"
            : String.format(Locale.US,"Offshore heading %.2f° true",s.offshoreHeading.degrees));
    }

    private void saveOffshoreHeading() {
        VtcService owner=service;
        if(owner==null)return;
        owner.captureOffshoreHeading(next->{
            if(isDestroyed()||service!=owner)return;
            settings=next;
            showOffshoreHeading(next);
            refreshSettingsStatus();
        });
    }

    private View liveMapPage(){
        LinearLayout p=scrollPage();
        liveMap=new LiveMapView(this,log::liveMapFrame,()->beginReplay(null),this::pickReplayLog,this::endReplay);
        p.addView(liveMap,new LinearLayout.LayoutParams(-1,-2));
        return scroll(p);
    }
    private View cameraPreviewPage() {
        LinearLayout panel = scrollPage();
        previewUpdate = button(panel, "Update", this::requestCameraPreview);
        previewStatus = text(panel, "Press Update for a new camera image.", 14, MUTED, false);
        previewImage = new ImageView(this);
        previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewImage.setContentDescription("Last camera preview");
        previewImage.setBackgroundColor(Color.BLACK);
        panel.addView(previewImage, new LinearLayout.LayoutParams(-1, 0, 1));
        return panel;
    }

    private void requestCameraPreview() {
        if (service == null || controller == null) return;
        previewUpdate.setEnabled(false);
        previewStatus.setText("Fetching camera image…");
        VtcService owner = service;
        owner.camera.requestPreview(controller::wifiNetwork, (image, error) -> runOnUiThread(() -> {
            if (isDestroyed() || service != owner) return;
            previewUpdate.setEnabled(true);
            if (image == null) {
                previewStatus.setText(error);
                return;
            }
            previewImage.setImageBitmap(image);
            String time = android.text.format.DateFormat.getTimeFormat(this).format(new Date());
            previewStatus.setText("Updated " + time);
        }));
    }

    private void pickReplayLog(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download/VTC/logs"));
        try{startActivityForResult(intent,REPLAY_FILE_REQUEST);}
        catch(ActivityNotFoundException e){toast("No file picker available");}
    }

    private void beginReplay(Uri file){
        if(service==null||replayPending||liveMap.replaying())return;
        VtcService source=service;
        replayPending=true;
        String notice="Starting replay. VTC will stop aiming and disconnect the GTi, LoRa and camera.";
        liveMap.loading(true,notice);toast(notice);
        handler.postDelayed(()->source.prepareReplay(closedCamera->{
            if(isDestroyed()||service!=source){source.endReplay();return;}
            if(closedCamera==null){
                replayPending=false;liveMap.loading(false,"Stop unconfirmed or in progress. Confirm Stop and retry replay.");
                return;
            }
            files.execute(()->{
                try{
                    if(!closedCamera.awaitClosed(6000))throw new IOException("Camera polling has not stopped. Try replay again.");
                    MapReplay run;
                    if(file==null)run=source.log.lastRun();
                    else try(InputStream input=getContentResolver().openInputStream(file)){
                        if(input==null)throw new IOException("Could not open the selected log");
                        run=MapReplay.read(input);
                    }
                    if(run==null)throw new IOException("No completed aiming run in "+(file==null?"the available logs":"the selected log"));
                    runOnUiThread(()->{
                        if(isDestroyed()||service!=source){source.endReplay();return;}
                        replayPending=false;liveMap.openReplay(run);
                    });
                }catch(Exception e){
                    source.log.error("replay_open_failed",e);
                    runOnUiThread(()->{
                        source.endReplay();
                        if(!isDestroyed()&&service==source){replayPending=false;liveMap.loading(false,e.getMessage());}
                    });
                }
            });
        }),1400);
    }
    private void endReplay(){
        if(service!=null)service.endReplay();
        liveMap.live();
    }

    private View logsPage() {
        LinearLayout p=scrollPage();
        logStatus=text(p,"Opening automatic full log in Download/VTC/logs",14,TEXT,false);
        text(p,"Full logging is always on. Each session writes directly to Download/VTC/logs and flushes continuously. The file is available while VTC is running; no Save or Export is needed.",15,TEXT,false);
        text(p,"A matching KML updates automatically in Download/VTC/logs: camera position, surfer points/track, timestamped camera heading and tilt, and saved GoTo targets. Open it in Google Earth and use the time slider. Aiming lines are calculated from calibration and motor positions; they do not verify the camera image.",14,MUTED,false);
        text(p,"Records include all saved settings and edits, button actions, calibration checks and failures, raw GPS datagrams, parsed telemetry, mount commands/replies, motor positions, aiming/framing decisions, and a status snapshot every second—even while waiting for calibration.",14,MUTED,false);
        text(p,"If public storage fails, VTC keeps a private recovery copy and automatically retries writing the full session to Download/VTC/logs. Storage errors and dropped-record counts appear here.",14,MUTED,false);
        text(p,"Verification\n• Build and automated checks cover software logic.\n• Landmark accuracy, motor directions, backlash and tracking smoothness need a hardware check.\n• No guarantee of a mount communication-loss watchdog is assumed. Continuous movement needs Stop. The independent watchdog attempts Stop on a control stall; lost power, force-stop or lost Wi-Fi can prevent it.\n• Aiming continues when switching apps or locking the screen. Use Stop in VTC or its notification. Force-stop/process termination ends the service; reconnect and validate calibration after a new service session.",14,MUTED,false);
        return scroll(p);
    }
    private void fillSettings(Settings s) {
        fillingSettings=true;
        for(Map.Entry<String,EditText> e:fields.entrySet())
            if(s.values.containsKey(e.getKey()))e.getValue().setText(s.values.get(e.getKey()));
        showOffshoreHeading(s);
        fields.get("cameraCoordinates").setText(Coordinates.display(s.values.get("latitude"),s.values.get("longitude")));
        fields.get("landmarkCoordinates").setText(Coordinates.display(s.values.get("landmarkLat"),s.values.get("landmarkLon")));
        zoomMode.setSelection(s.zoomMode.equals("off")?0:s.zoomMode.equals("bands")?1:2);
        gotoMode.setSelection(s.gotoNoWait?1:0);
        panSign.setSelection(s.panSign>0?0:1);tiltSign.setSelection(s.tiltSign>0?0:1);
        formBaseline=formValues();
        settings=s;
        fillingSettings=false;
    }
    private Map<String,String> formValues() {
        Map<String,String> values=new LinkedHashMap<>();
        for(Map.Entry<String,EditText> e:fields.entrySet())values.put(e.getKey(),e.getValue().getText().toString());
        values.put("zoomMode",new String[]{"off","bands","auto-fit"}[zoomMode.getSelectedItemPosition()]);
        values.put("gotoMode",gotoMode.getSelectedItemPosition()==1?"no-wait":"acknowledged");
        values.put("panSign",panSign.getSelectedItemPosition()==0?"1":"-1");
        values.put("tiltSign",tiltSign.getSelectedItemPosition()==0?"1":"-1");
        return values;
    }
    private Settings readSettings() {
        return controller.settings().withFormEdits(formBaseline,formValues());
    }
    private void scheduleSave(){if(!fillingSettings){handler.removeCallbacks(autoSave);handler.postDelayed(autoSave,600);}}
    private boolean autoSaveSettings() {
        handler.removeCallbacks(autoSave);
        if(fillingSettings||service==null||service.exiting())return false;
        
        try {
            Settings displayed=settings;
            Settings current=controller.settings();
            Settings next=current.withFormEdits(formBaseline,formValues());
            applySettings(next);
            if(current!=displayed)fillSettings(next);
            else formBaseline=formValues();
            settingsValidationError="";setupMessage.setText("Valid edits apply automatically. File save status is shown below.");refreshSettingsStatus();return true;
        }catch(Exception e){settingsValidationError="Not applied: "+e.getMessage()+". Last valid settings retained.";setupMessage.setText(settingsValidationError);refreshSettingsStatus();return false;}
    }
    private void applySettings(Settings next)throws Exception {
        if(!next.values.equals(controller.settings().values)){
            service.saveSettings(next);log.settings("settings_applied_save_queued",next);
        }
        settings=next;
        showOffshoreHeading(next);
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
        if(request==REPLAY_FILE_REQUEST){
            beginReplay(uri);
        }else if(request==105){
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
                    runOnUiThread(()->{
                        handler.removeCallbacks(autoSave);
                        try{applySettings(imported);fillSettings(imported);showPage(2);toast("Setup applied");}
                        catch(Exception e){log.error("settings_import_failed",e);toast("Import failed: "+e.getMessage());}
                    });
                }catch(Exception e){log.error("settings_import_failed",e);runOnUiThread(()->toast("Import failed: "+e.getMessage()));}
            });
        }
    }
    private void updateAimStatusPulse(boolean prominent) {
        boolean animate=prominent&&screenVisible&&android.animation.ValueAnimator.areAnimatorsEnabled();
        if(!animate){
            if(aimStatusPulse!=null){
                aimStatusPulse.cancel();
                aimStatusPulse=null;
                state.setAlpha(1f);
            }
            return;
        }
        if(aimStatusPulse!=null)return;
        aimStatusPulse=android.animation.ObjectAnimator.ofFloat(state,"alpha",1f,.7f);
        aimStatusPulse.setDuration(900);
        aimStatusPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        aimStatusPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        aimStatusPulse.start();
    }

    private void render(VtcController.View v) {
        if(isDestroyed()||service==null||service.exiting())return;
        if(settings!=controller.settings()&&formValues().equals(formBaseline))fillSettings(controller.settings());
        lastView=v;
        state.setText(v.state);
        boolean ready="READY TO AIM".equals(v.state);
        state.setTextColor(v.tracking||ready?ACCENT:
            v.state.equals("CONNECTION CLOSED")||v.state.equals("AIMING STOPPED")?STATUS_RED:TEXT);
        updateAimStatusPulse(ready||"AIMING".equals(v.state));
        renderConnectionStatus(v.connections,service.camera.connected(),v.calibrated);
        updateCameraConnectionControls();
        message.setText(v.message);mountInfo.setText(v.mount);gpsInfo.setText(controller.liveGps());aimInfo.setText(v.aim);frameInfo.setText(v.framing);calibrationInfo.setText(v.calibration);
        connect.setEnabled(!v.connected&&!v.busy);disconnect.setEnabled(v.connected||v.busy);
        for(Button button:aimingButtons){button.setEnabled(v.tracking||v.canStart);button.setText(v.tracking?"STOP AIMING":"START AIMING");}
        calibrate.setEnabled(v.connected&&!v.tracking&&!v.busy&&!v.moving);
        landmarkCalibrate.setEnabled(v.canGoToLandmark&&!v.tracking&&!v.moving);
        if(v.calibrated!=wavePanCalibrated)updateWavePanSteps(v.calibrated);
        saveOffshoreHeading.setEnabled(v.connected&&v.calibrated&&!v.tracking&&!v.busy&&!v.moving);
        calibrationConnectionHint.setVisibility(v.connected?View.GONE:View.VISIBLE);
        for(Button b:manualButtons)b.setEnabled(v.connected&&!v.tracking&&!v.busy&&!v.moving);
        for(Button b:landmarkButtons)b.setEnabled(v.canGoToLandmark);
        for(TextView t:landmarkStatuses)t.setText(v.lastLandmark);
        boolean editing=controller.canEditMountSettings();
        for(Map.Entry<String,EditText> entry:fields.entrySet())entry.getValue().setEnabled(editing||Settings.canEditDuringAiming(entry.getKey()));
        panSign.setEnabled(editing);tiltSign.setEnabled(editing);
        if(!v.connected){levelConfirmed.setChecked(false);directionsConfirmed.setChecked(false);}
        refreshSettingsStatus();
        logStatus.setText(log.status()+"\n\n"+service.setupStatus());
        cameraInfo.setText(service.camera.status());
    }
    private void showPage(int index){
        if(index!=LIVE_MAP_PAGE&&(replayPending||liveMap.replaying())){
            toast(replayPending?"Wait for replay to finish opening":"Return to live before leaving the replay");
            return;
        }
        page=index;
        body.removeAllViews();body.addView(pages[index],new LinearLayout.LayoutParams(-1,-1));
        liveMap.setVisibleOnScreen(screenVisible&&page==LIVE_MAP_PAGE);
        log.record("ui_page","index",index);
    }
    private void deviceAddress(LinearLayout parent,String key,String label,boolean mount) {
        text(parent,label,14,TEXT,false);
        LinearLayout line=row(parent);
        addField(line,key);
        button(line,mount?"Find GTi":"Find Camera",()->findDevice(key,mount));
    }

    private void findDevice(String key,boolean mount) {
        if(findingDevice||service==null||service.exiting())return;
        findingDevice=true;
        Settings current=controller.settings();
        var preferred=controller.cameraNetwork();
        var manager=getSystemService(android.net.ConnectivityManager.class);
        String saved=current.values.get(key);
        int port=mount?current.mountPort:current.cameraPort;
        toast(mount?"Looking for GTi mounts...":"Looking for Canon cameras...");
        discoveryWorker.execute(()->{
            try{
                List<DeviceDiscovery.Device> devices=DeviceDiscovery.find(manager,preferred,mount,saved,port);
                handler.post(()->{
                    findingDevice=false;
                    if(isDestroyed()||service==null||service.exiting())return;
                    if(devices.isEmpty()){
                        toast(mount?"No GTi responded on this network.":"No Canon CCAPI camera responded on this network.");
                        return;
                    }
                    String[] labels=devices.stream().map(DeviceDiscovery.Device::label).toArray(String[]::new);
                    new AlertDialog.Builder(this).setTitle(mount?"Select GTi":"Select camera")
                        .setItems(labels,(dialog,index)->applyDiscoveredAddress(key,mount,devices.get(index),port))
                        .setNegativeButton("Cancel",null).show();
                });
            }catch(InterruptedException stopped){Thread.currentThread().interrupt();}
            catch(Exception error){
                handler.post(()->{
                    findingDevice=false;
                    if(!isDestroyed())toast(error.getMessage());
                });
            }
        });
    }

    private void applyDiscoveredAddress(String key,boolean mount,DeviceDiscovery.Device device,int searchedPort) {
        if(service==null||service.exiting())return;
        if(lastView!=null&&lastView.tracking){toast("Stop aiming before applying a discovered address.");return;}
        try{
            Settings current=controller.settings();
            if((mount?current.mountPort:current.cameraPort)!=searchedPort){
                toast("The port changed. Run the search again.");return;
            }
            Map<String,String> values=new LinkedHashMap<>(current.values);
            values.put(key,device.address());
            Settings next=new Settings(values);
            applySettings(next);
            fields.get(key).setText(device.address());
            Map<String,String> baseline=new LinkedHashMap<>(formBaseline);
            baseline.put(key,device.address());
            formBaseline=baseline;
            refreshSettingsStatus();
        }catch(Exception e){toast(e.getMessage());}
    }

    private void field(LinearLayout parent,String key,String label) {
        text(parent,label,14,TEXT,false);
        addField(parent,key);
    }

    private void addField(LinearLayout parent,String key) {
        EditText e=new EditText(this);e.setSingleLine();e.setTextSize(16);e.setTextColor(TEXT);e.setHintTextColor(MUTED);
        boolean coordinates=key.endsWith("Coordinates");
        e.setInputType(key.endsWith("Host")||coordinates?InputType.TYPE_CLASS_TEXT:InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        e.setHint(coordinates?"-28.165514, 153.550457":key.equals("height")||key.equals("landmarkHeight")?"Required before calibration":"");
        e.setOnFocusChangeListener((v,focused)->{if(!focused)log.record("settings_field_edited","field",key,"value",e.getText().toString(),"saved",false);});
        e.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){}
            public void afterTextChanged(android.text.Editable text){scheduleSave();}
        });
        parent.addView(e,parent.getOrientation()==LinearLayout.HORIZONTAL?
            new LinearLayout.LayoutParams(0,-2,1):new LinearLayout.LayoutParams(-1,-2));
        fields.put(key,e);
    }
    private LinearLayout card(LinearLayout parent,String title) {
        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(14),dp(12),dp(14),dp(14));
        GradientDrawable bg=new GradientDrawable();bg.setColor(CARD);bg.setCornerRadius(dp(12));c.setBackground(bg);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(10),0,dp(4));parent.addView(c,lp);
        text(c,title,18,TEXT,true);return c;
    }
    private LinearLayout row(LinearLayout parent){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);parent.addView(r,new LinearLayout.LayoutParams(-1,-2));return r;}
    private Button button(LinearLayout parent,String label,Runnable action) {
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setMinHeight(dp(48));
        b.setOnClickListener(v->{log.record("ui_action","button",b.getText().toString(),"page",page);action.run();});
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
    @Override protected void onResume(){super.onResume();screenVisible=true;if(log!=null)log.event("activity_resume","foreground");if(controller!=null)controller.foreground(true);if(liveMap!=null)liveMap.setVisibleOnScreen(page==LIVE_MAP_PAGE);}
    @Override protected void onPause(){if(service!=null)autoSaveSettings();screenVisible=false;updateAimStatusPulse(false);if(log!=null&&zoomMode!=null)log.record("activity_pause","form",formValues());if(controller!=null)controller.foreground(false);if(liveMap!=null)liveMap.setVisibleOnScreen(false);super.onPause();}
    @Override protected void onDestroy(){
        updateAimStatusPulse(false);
        if(service!=null)service.detach(listener);if(bound)unbindService(serviceConnection);
        if(liveMap!=null){
            if(service!=null&&!replayPending&&liveMap.replaying())service.endReplay();
            liveMap.close();
        }
        discoveryWorker.shutdownNow();files.shutdown();handler.removeCallbacksAndMessages(null);super.onDestroy();
    }
}

