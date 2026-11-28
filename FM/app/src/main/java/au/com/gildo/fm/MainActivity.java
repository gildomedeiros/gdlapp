package au.com.gildo.fm;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Typeface;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int CHOOSE_REPLAY_CSV=1, LINK_SETTINGS_FOLDER=5, CHOOSE_SIM_KML=6;
    private static final String SETTINGS_OPEN="settingsExpanded", CALIBRATION_OPEN="calibrationExpanded", REPORT_OPEN="reportExpanded";
    private static final String REPLAY_NAME="replayCsvName";
    private static final String GPS_PAD_STEP="gpsPadStep";
    private static final String SURFER_SEQUENCE="surferSequence", SURFER_RIDE_SECONDS="surferRideSecondsText";
    private static final String[] REPLAY_TIMING_COLUMNS={"Viewpoint","GoToPosition time","Since previous call",
        "Stop motor","Stop check wait","Row → both stopped","CSV delay","Actual wait","Call → required Start replies"};
    private static final int[] REPLAY_TIMING_WIDTHS={82,128,108,134,112,130,96,102,144};
    private record TimingColumn(String title,int widthDp) {}
    private static final int HS_GAP_COLUMN=1;
    private static final TimingColumn[] HS_TIMING_COLUMNS={
        new TimingColumn("Viewpoint / CSV row",104),new TimingColumn("Gap to target",108),
        new TimingColumn("Mount range",104),new TimingColumn("Target pace",105),
        new TimingColumn("Matched pace",105),new TimingColumn("Expected time",100),
        new TimingColumn("Observed time",128),new TimingColumn("Since previous checkpoint",132),
        new TimingColumn("Lead / lag",108),new TimingColumn("Outcome",110)
    };
    private final EditText[] inputs=new EditText[FmService.KEYS.length];
    private final ArrayList<Button> buttons=new ArrayList<>();
    private TextView gpsStatus,gtiStatus,result,savedStatus,calibrationStatus,replayFileStatus;
    private TextView replayTotalTime,replayMotion,replayFirstStart,landmarkGpsStatus;
    private EditText mountCoordinates,landmarkCoordinates;
    private LinearLayout settingsPanel,calibrationPanel,reportPanel;
    private TextView cameraStatus,settingsSaveStatus;
    private final EnumMap<ConnectionFlag.Channel,TextView> connectionFlags=new EnumMap<>(ConnectionFlag.Channel.class);
    private TextView dashboardTelemetry,settingsVersion;
    private final Runnable refreshSettingsAge=new Runnable() {
        @Override public void run() {
            renderSettingsVersion();
            settingsVersion.postDelayed(this,1000);
        }
    };
    private boolean restoringSettings,startingLive;
    private long displayedSettingsRevision=-1;
    private String displayedFileStatus="";
    private static final int GDL_PERMISSION=7;
    private boolean showGdlCredentials;
    private Button connectHotspot;
    private String lastHotspotName;
    private final SettingsAutosave autosave=new SettingsAutosave(this::writeSettingsEdit,this::settingsSaveResult);
    private CheckBox calibrationConfirmed, disableTilt, manualCamera;
    private Button cameraPreviewButton, liveStart, cancelCapture, stopFm, discoverCanon, chooseCsv, linkFolder;
    private TextView landmarkRangeBanner;
    private LiveMapDialog liveMap;
    private boolean cameraPreviewPending;
    private Button startMonitoring,replayResults,hsResults,hsCancel,liveStop;
    private LinearLayout liveOffsetControls,gpsPadControls;
    private Button gpsPadToggle;
    private Button saveSurferModes,loadSurferModes,loadKml,applySurferModes;
    private RadioButton oneHz,twoHz;
    private Uri pendingSimKml,pendingReplayCsv;
    private record ContinuousModeControls(CheckBox select,Button duration) {}
    private final EnumMap<SurferModes.Mode,ContinuousModeControls> continuousSurferModes=new EnumMap<>(SurferModes.Mode.class);
    private EditText gpsPadStep;
    private EditText surferSequence,surferRideSeconds;
    private TextView liveOffsetStatus,liveGpsRuleStatus;
    private TextView surferFileStatus,surferPlaybackStatus;
    private BaseAdapter replayResultsAdapter;
    private int replayResultsShown=-1;
    private SharedPreferences prefs;
    private FmService service,formService;
    private SettingsFolderSelection pendingSettingsFolder;
    private boolean bound;
    private record SettingsFolderSelection(Uri uri,int flags) {}
    private final FmService.Listener listener=this::render;
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            service=((FmService.Connection)binder).service();
            service.attach(listener);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service=null;
            render();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences("fm",MODE_PRIVATE);
        lastHotspotName=HotspotSettings.cached(prefs).name();
        if(state!=null && state.getString("pendingSimKml")!=null) pendingSimKml=Uri.parse(state.getString("pendingSimKml"));
        if(state!=null && state.getString("pendingReplayCsv")!=null) pendingReplayCsv=Uri.parse(state.getString("pendingReplayCsv"));
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        int padding=(int)(20*getResources().getDisplayMetrics().density);
        LinearLayout screen=new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(padding,padding*2,padding,0);
        setContentView(screen);
        TextView title=text(screen,"FM "+BuildConfig.VERSION_NAME,24);
        addConnectionFlags(screen);
        settingsVersion=text(screen,"Settings version · —",14);
        dashboardTelemetry=text(screen,LiveMapFrame.dashboardStatus(null),14);
        stopFm=new Button(this);
        stopFm.setAllCaps(false);
        stopFm.setText("Stop FM");
        screen.addView(stopFm);
        stopFm.setOnClickListener(v->{ if(service!=null) service.requestStopFm(); });
        root.setPadding(0,0,0,padding);
        scroll.addView(root);
        screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(Build.VERSION.SDK_INT>=28) title.setAccessibilityHeading(true);
        landmarkRangeBanner=text(root,"",22);
        landmarkRangeBanner.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        landmarkRangeBanner.setBackgroundColor(0xffa51220);
        landmarkRangeBanner.setTextColor(0xffffffff);
        landmarkRangeBanner.setPadding(padding,padding,padding,padding);
        landmarkRangeBanner.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        landmarkRangeBanner.setVisibility(View.GONE);
        cameraPreviewButton=new Button(this);
        cameraPreviewButton.setAllCaps(false);
        cameraPreviewButton.setText("Update camera preview");
        root.addView(cameraPreviewButton);
        ImageView preview=new ImageView(this);
        preview.setAdjustViewBounds(true);
        root.addView(preview);
        TextView previewStatus=text(root,"",14);
        cameraPreviewButton.setOnClickListener(v->{
            FmService connected=service;
            if(connected==null || cameraPreviewPending) return;
            cameraPreviewPending=true;
            cameraPreviewButton.setEnabled(false);
            previewStatus.setText("Requesting preview…");
            connected.cameraPreview((image,error)->{
                cameraPreviewPending=false;
                if(service==connected) {
                    if(image!=null) {
                        preview.setImageBitmap(image);
                        previewStatus.setText("Updated "+java.time.LocalTime.now().withNano(0));
                    } else previewStatus.setText(error);
                } else previewStatus.setText("Preview request ended");
                if(bound) render();
            });
        });
        Button openMap=new Button(this);
        openMap.setAllCaps(false);
        openMap.setText("Live Map");
        root.addView(openMap);
        openMap.setOnClickListener(v->{
            liveMap=new LiveMapDialog(this,()->service==null?null:service.liveMapFrame());
            liveMap.show();
        });
        String[] values=SettingsFile.cached(prefs);
        text(root,"Move report",18);
        result=text(root,"Starting FM",16);
        gpsStatus=text(root,"Starting GPS",16);
        liveGpsRuleStatus=text(root,"",14);
        liveGpsRuleStatus.setVisibility(View.GONE);
        addGpsPad(root,state);
        gtiStatus=text(root,"GTi disconnected",15);
        connectHotspot=button(root,"Connect to hotspot",this::chooseGdl);
        button(root,"Connect GTi",()->afterSettings(()->service.connectGti()));
        savedStatus=text(root,"",15);
        calibrationPanel=collapsibleSection(root,"Landmark calibration",state!=null&&state.getBoolean(CALIBRATION_OPEN));
        landmarkCoordinates=addSetting(calibrationPanel,values,LandmarkCalibration.LANDMARK_LAT);
        button(calibrationPanel,"Go to landmark",()->afterSettings(()->service.launch("Go to landmark",null,SettingsFile.cached(prefs))));
        button(calibrationPanel,"Capture landmark from LoRa GPS",this::captureLandmarkGps);
        text(calibrationPanel,"Capture with the LoRa unit at the landmark.",14);
        cancelCapture=new Button(this);
        cancelCapture.setAllCaps(false);
        cancelCapture.setText("Cancel GPS capture");
        cancelCapture.setOnClickListener(v->{ if(service!=null) service.cancelLandmarkCapture(); });
        cancelCapture.setVisibility(View.GONE);
        calibrationPanel.addView(cancelCapture);
        landmarkGpsStatus=text(calibrationPanel,"",14);
        landmarkGpsStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        landmarkGpsStatus.setVisibility(View.GONE);
        addSetting(calibrationPanel,values,16);
        addSetting(calibrationPanel,values,17);
        text(calibrationPanel,"Centre the landmark in the preview.",14);
        calibrationConfirmed=new CheckBox(this);
        calibrationConfirmed.setText("Mount level and secure · Right/Up checked");
        calibrationPanel.addView(calibrationConfirmed);
        calibrationStatus=text(calibrationPanel,"Checking calibration…",15);
        calibrationStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        button(calibrationPanel,"Calibrate here",()->afterSettings(()->service.calibrateHere(SettingsFile.cached(prefs),
            mountCoordinates.getText().toString(),landmarkCoordinates.getText().toString(),calibrationConfirmed.isChecked())));
        liveStart=button(root,"Live Tracking",this::startLiveTracking);
        button(root,"Aim at Surfer HS",()->afterSettings(()->service.launchAim(SettingsFile.cached(prefs))));
        liveStop=new Button(this);
        liveStop.setAllCaps(false);
        liveStop.setText("Stop Live Tracking");
        liveStop.setVisibility(View.GONE);
        liveStop.setOnClickListener(v->{ if(service!=null) service.stopLiveTracking(); });
        root.addView(liveStop);
        liveOffsetControls=new LinearLayout(this);
        liveOffsetControls.setOrientation(LinearLayout.VERTICAL);
        liveOffsetControls.setVisibility(View.GONE);
        root.addView(liveOffsetControls);
        liveOffsetButton(liveOffsetControls,2);
        liveOffsetButton(liveOffsetControls,-2);
        liveOffsetButton(liveOffsetControls,0.5);
        liveOffsetButton(liveOffsetControls,-0.5);
        liveOffsetStatus=text(liveOffsetControls,"",14);
        button(root,"GoToMe",()->afterSettings(()->service.launch("GoToMe",null,SettingsFile.cached(prefs))));
        button(root,"Go to start",()->afterSettings(()->service.launch("GoHome",null,SettingsFile.cached(prefs))));
        hsCancel=new Button(this);
        hsCancel.setAllCaps(false);
        hsCancel.setText("Stop HS replay");
        hsCancel.setVisibility(View.GONE);
        hsCancel.setOnClickListener(v->{ if(service!=null) service.cancelHs(); });
        root.addView(hsCancel);
        startMonitoring=new Button(this);
        startMonitoring.setText("Start FM");
        startMonitoring.setAllCaps(false);
        startMonitoring.setOnClickListener(v->connect());
        startMonitoring.setVisibility(View.GONE);
        root.addView(startMonitoring);
        settingsPanel=collapsibleSection(root,"Settings",state!=null&&state.getBoolean(SETTINGS_OPEN));
        mountCoordinates=addSetting(settingsPanel,values,MountCoordinates.LATITUDE);
        addSetting(settingsPanel,values,LandmarkCalibration.PIVOT_ELEVATION);
        addSetting(settingsPanel,values,LandmarkCalibration.LANDMARK_HEIGHT);
        addSetting(settingsPanel,values,11);
        chooseCsv=button(settingsPanel,"Choose replay CSV",this::chooseReplayCsv);
        settingsSaveStatus=text(settingsPanel,"Autosave on",14);
        settingsSaveStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        linkFolder=button(settingsPanel,"Link FM settings folder",this::chooseSettingsFolder);
        for(int i=0;i<6;i++) addSetting(settingsPanel,values,i);
        LinearLayout canon=collapsibleSection(settingsPanel,"Canon camera",false);
        manualCamera=new CheckBox(this);
        manualCamera.setText("Manual camera");
        manualCamera.setChecked(Boolean.parseBoolean(values[FmService.MANUAL_CAMERA]));
        canon.addView(manualCamera);
        manualCamera.setOnCheckedChangeListener((button,checked)->{
            if(restoringSettings || formService==null) return;
            autosave.edit(FmService.MANUAL_CAMERA,Boolean.toString(checked));
            autosave.finishField(FmService.MANUAL_CAMERA);
        });
        addSetting(canon,values,6);
        addSetting(canon,values,7);
        cameraStatus=text(canon,"Canon waiting for FM",14);
        discoverCanon=button(canon,"Discover Canon SX",()->afterSettings(()->discover(false)));
        for(int i=CameraSettings.FIRST;i<=CameraSettings.LAST;i++) {
            if(i!=CameraSettings.BANDS && i!=CameraSettings.MAPPING) addSetting(canon,values,i);
        }
        button(settingsPanel,"Discover GTi",()->afterSettings(()->discover(true)));
        addSetting(settingsPanel,values,FmService.HS_GAP_WARNING);
        disableTilt=new CheckBox(this);
        disableTilt.setText("Disable tilt motor");
        disableTilt.setChecked(FmService.tiltDisabled(values));
        settingsPanel.addView(disableTilt);
        disableTilt.setOnCheckedChangeListener((button,checked)->{
            if(restoringSettings || formService==null) return;
            autosave.edit(FmService.TILT_DISABLE,Boolean.toString(checked));
            autosave.finishField(FmService.TILT_DISABLE);
        });
        addSetting(settingsPanel,values,FmService.LIVE_MIN_RANGE);
        addSetting(settingsPanel,values,FmService.LIVE_PAN_SPEED_MULTIPLIER);
        addSetting(settingsPanel,values,FmService.LIVE_TILT_SPEED);
        addSetting(settingsPanel,values,FmService.LIVE_GPS_HOLD);
        addSetting(settingsPanel,values,FmService.LIVE_GPS_STATIONARY_MS);
        addSetting(settingsPanel,values,FmService.LIVE_PAN_LEAD);
        addSetting(settingsPanel,values,FmService.SIMULATOR_SHOT_WIDTH);

        reportPanel=collapsibleSection(root,"Report",state!=null && state.getBoolean(REPORT_OPEN));
        button(reportPanel,"Replay viewpoints",()->afterSettings(this::replayViewpoints));
        replayFileStatus=text(reportPanel,"",14);
        updateReplayFileStatus();
        replayResults=new Button(this);
        replayResults.setAllCaps(false);
        replayResults.setText("View replay reports");
        replayResults.setEnabled(false);
        replayResults.setOnClickListener(v->showReplayReports());
        reportPanel.addView(replayResults);
        button(reportPanel,"Replay viewpoints (HS)",()->afterSettings(this::replayHighSpeed));
        hsResults=new Button(this);
        hsResults.setAllCaps(false);
        hsResults.setText("View HS replay report");
        hsResults.setEnabled(false);
        hsResults.setOnClickListener(v->showHsReport());
        reportPanel.addView(hsResults);

        if(state!=null) {
            Bundle drafts=state.getBundle("settingsDrafts");
            if(drafts!=null) for(String key:drafts.keySet()) {
                int index=Integer.parseInt(key);
                String value=drafts.getString(key);
                if(inputs[index]!=null) inputs[index].setText(value);
                else if(index==FmService.TILT_DISABLE) disableTilt.setChecked(Boolean.parseBoolean(value));
                else if(index==FmService.MANUAL_CAMERA) manualCamera.setChecked(Boolean.parseBoolean(value));
                autosave.edit(index,value);
            }
        }
        root.requestFocus();
        render();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        Bundle drafts=new Bundle();
        for(SettingsAutosave.Edit edit:autosave.drafts()) drafts.putString(Integer.toString(edit.index()),edit.value());
        state.putBundle("settingsDrafts",drafts);
        state.putBoolean(REPORT_OPEN,reportPanel.getVisibility()==View.VISIBLE);
        state.putBoolean(SETTINGS_OPEN,settingsPanel.getVisibility()==View.VISIBLE);
        state.putBoolean(CALIBRATION_OPEN,calibrationPanel.getVisibility()==View.VISIBLE);
        if(pendingSimKml!=null) state.putString("pendingSimKml",pendingSimKml.toString());
        if(pendingReplayCsv!=null) state.putString("pendingReplayCsv",pendingReplayCsv.toString());
        state.putString(GPS_PAD_STEP,gpsPadStep.getText().toString());
        state.putString(SURFER_SEQUENCE,surferSequence.getText().toString());
        state.putString(SURFER_RIDE_SECONDS,surferRideSeconds.getText().toString());
    }

    @Override protected void onStart() {
        super.onStart();
        settingsVersion.post(refreshSettingsAge);
        if(Build.VERSION.SDK_INT<29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED
            && !prefs.getBoolean("legacyPermissionAsked",false)) {
            prefs.edit().putBoolean("legacyPermissionAsked",true).apply();
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},3);
        } else connect();
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
            && !prefs.getBoolean("notificationAsked",false)) {
            prefs.edit().putBoolean("notificationAsked",true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},4);
        }
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] result) {
        super.onRequestPermissionsResult(code,permissions,result);
        if(code==3 && !isFinishing()) connect();
        if(code==GDL_PERMISSION && !isFinishing()) {
            if(gdlPermissionGranted()) { showGdlCredentials=true; render(); }
            else {
                if(service!=null) service.gdlPermissionDenied();
                Toast.makeText(this,"Hotspot Wi-Fi permission denied",Toast.LENGTH_LONG).show();
            }
        }
    }
    private void connect() {
        if(bound) return;
        try {
            Intent intent=new Intent(this,FmService.class);
            startForegroundService(intent);
            bound=bindService(intent,connection,BIND_AUTO_CREATE);
            startMonitoring.setVisibility(View.GONE);
            render();
        } catch(RuntimeException e) { result.setText("Cannot start FM: "+e.getMessage()); }
    }
    private void disconnect() {
        if(service!=null) service.detach(listener);
        if(bound) unbindService(connection);
        bound=false;
        service=null;
    }
    @Override protected void onStop() {
        settingsVersion.removeCallbacks(refreshSettingsAge);
        if(service!=null && service.settingsEditable()) autosave.finishAll(null);
        if(liveMap!=null) { liveMap.dismiss(); liveMap=null; }
        disconnect();
        super.onStop();
    }
    private void addConnectionFlags(LinearLayout parent) {
        if(getResources().getConfiguration().screenWidthDp>=480) {
            addFlagRow(parent,ConnectionFlag.Channel.GDL,ConnectionFlag.Channel.GPS,ConnectionFlag.Channel.GTI,ConnectionFlag.Channel.CALIBRATION);
        } else {
            addFlagRow(parent,ConnectionFlag.Channel.GDL,ConnectionFlag.Channel.GPS);
            addFlagRow(parent,ConnectionFlag.Channel.GTI,ConnectionFlag.Channel.CALIBRATION);
        }
        addFlagRow(parent,ConnectionFlag.Channel.CANON,ConnectionFlag.Channel.RECORDING,
            ConnectionFlag.Channel.EVENTS,ConnectionFlag.Channel.ZOOM);
    }

    private void addFlagRow(LinearLayout parent,ConnectionFlag.Channel... channels) {
        LinearLayout row=new LinearLayout(this);
        parent.addView(row);
        for(ConnectionFlag.Channel channel:channels) {
            TextView badge=new TextView(this);
            badge.setTextSize(12);
            badge.setPadding(dp(3),dp(2),dp(3),dp(2));
            LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1);
            cell.setMargins(0,0,dp(3),dp(3));
            row.addView(badge,cell);
            connectionFlags.put(channel,badge);
        }
    }

    private void renderConnectionFlags() {
        for(var entry:connectionFlags.entrySet()) {
            ConnectionFlag.Channel channel=entry.getKey();
            ConnectionFlag flag=service==null?(channel==ConnectionFlag.Channel.GDL?ConnectionFlag.failed("Disconnected"):ConnectionFlag.idle("FM offline")):service.connectionFlag(channel);
            TextView badge=entry.getValue();
            if(flag.equals(badge.getTag())) continue;
            badge.setTag(flag);
            badge.setText(channel.title+"\n"+flag.label());
            badge.setTextColor(0xffffffff);
            badge.setBackgroundColor(switch(flag.level()) {
                case GOOD -> 0xff20603a;
                case FAILED -> 0xff9c2431;
                case PENDING -> 0xff775307;
                case IDLE -> 0xff505b64;
            });
        }
    }

    private void renderSettingsVersion() {
        String version="Settings version · "+(service==null?"—":service.settingsVersionLabel());
        if(!TextUtils.equals(settingsVersion.getText(),version)) settingsVersion.setText(version);
    }

    private void render() {
        if(service!=null && service.stopped()) {
            showGdlCredentials=false;
            result.setText(service.resultText());
            disconnect();
            gpsStatus.setText("FM stopped");
            gtiStatus.setText("GTi disconnected");
            startMonitoring.setVisibility(View.VISIBLE);
        }
        renderConnectionFlags();
        renderSettingsVersion();
        String telemetry=LiveMapFrame.dashboardStatus(service==null?null:service.liveMapFrame());
        if(!TextUtils.equals(dashboardTelemetry.getText(),telemetry)) dashboardTelemetry.setText(telemetry);
        if(cameraStatus!=null) cameraStatus.setText(service==null?"Canon waiting for FM":service.cameraStatus());
        boolean ready=service!=null && service.ready() && !autosave.waitingForAction();
        boolean editSettings=service!=null && service.settingsEditable() && !startingLive;
        long revision=service==null?-1:service.settingsRevision();
        boolean reloaded=service!=null && formService==service && displayedSettingsRevision!=revision;
        if((service!=null && service.settingsLoaded() && formService!=service) || reloaded) {
            restoringSettings=true;
            String[] saved=SettingsFile.cached(prefs);
            for(int i=0;i<inputs.length;i++) if(inputs[i]!=null && !autosave.hasDraft(i)
                && !TextUtils.equals(inputs[i].getText(),displayValue(saved,i))) inputs[i].setText(displayValue(saved,i));
            if(!autosave.hasDraft(FmService.MANUAL_CAMERA)) manualCamera.setChecked(Boolean.parseBoolean(saved[FmService.MANUAL_CAMERA]));
            displayedSettingsRevision=revision;
            if(!autosave.hasDraft(FmService.TILT_DISABLE)) disableTilt.setChecked(FmService.tiltDisabled(saved));
            restoringSettings=false;
            formService=service;
        }
        if(service!=null && !displayedFileStatus.equals(service.settingsFileStatus())) {
            displayedFileStatus=service.settingsFileStatus();
            if(!displayedFileStatus.isEmpty()) settingsSaveStatus.setText(displayedFileStatus);
        }
        for(Button b:buttons) b.setEnabled(ready);
        boolean hotspotAvailable=service==null?bound:service.canConnectHotspot();
        hotspotAvailable=hotspotAvailable && !startingLive && !autosave.waitingForAction();
        if(service!=null && service.settingsLoaded()) lastHotspotName=service.hotspot().name();
        String hotspotLabel="Connect to "+lastHotspotName;
        if(!TextUtils.equals(connectHotspot.getText(),hotspotLabel)) connectHotspot.setText(hotspotLabel);
        connectHotspot.setEnabled(hotspotAvailable && !showGdlCredentials);
        stopFm.setEnabled(service!=null && !service.stopping());
        stopFm.setText(service!=null && service.stopping()?"Waiting for full stop":"Stop FM");
        discoverCanon.setEnabled(ready && !service.manualCamera());
        manualCamera.setEnabled(editSettings);
        chooseCsv.setEnabled(editSettings);
        linkFolder.setEnabled(editSettings && !service.linkingSettingsFolder());
        if(showGdlCredentials && hotspotAvailable && service!=null && service.settingsLoaded()) {
            showGdlCredentials=false;
            connectHotspot.setEnabled(true);
            gdlCredentials();
        }
        String block=service==null?"":service.landmarkRangeBlock();
        if(!TextUtils.equals(landmarkRangeBanner.getText(),block)) landmarkRangeBanner.setText(block);
        landmarkRangeBanner.setVisibility(block.isEmpty()?View.GONE:View.VISIBLE);
        liveStart.setEnabled(ready&&block.isEmpty());
        boolean capturing=service!=null&&service.capturingLandmark();
        cancelCapture.setVisibility(capturing?View.VISIBLE:View.GONE);
        if(service!=null && !service.captureProgress().isEmpty()) {
            landmarkGpsStatus.setVisibility(View.VISIBLE);
            landmarkGpsStatus.setText(service.captureProgress());
        }
        for(EditText input:inputs) if(input!=null) input.setEnabled(editSettings);
        disableTilt.setEnabled(editSettings);
        calibrationConfirmed.setEnabled(ready);
        hsCancel.setVisibility(service!=null&&service.canCancelHs()?View.VISIBLE:View.GONE);
        boolean tracking=service!=null&&service.canStopLiveTracking();
        cameraPreviewButton.setEnabled((ready||tracking)&&!cameraPreviewPending&&!service.manualCamera());
        String gpsRule=tracking?service.liveGpsRuleText():"";
        liveGpsRuleStatus.setVisibility(gpsRule.isEmpty()?View.GONE:View.VISIBLE);
        if(!TextUtils.equals(liveGpsRuleStatus.getText(),gpsRule)) liveGpsRuleStatus.setText(gpsRule);
        liveStop.setVisibility(tracking?View.VISIBLE:View.GONE);
        boolean simulating=service!=null&&service.gpsSimulatorActive();
        gpsPadToggle.setEnabled(ready||tracking);
        gpsPadToggle.setText(simulating?"Use LoRa GPS":"GP Simulator");
        gpsPadControls.setVisibility(simulating?View.VISIBLE:View.GONE);
        if(simulating) {
            SurferModes.Mode activeMode=service.continuousSurferMode();
            boolean canSelectMode=(ready||tracking);
            continuousSurferModes.forEach((mode,controls)->{
                controls.select().setChecked(mode==activeMode);
                controls.select().setEnabled(canSelectMode);
                if(controls.duration()!=null) controls.duration().setEnabled(canSelectMode);
            });
            boolean canPrepare=(ready||tracking)&&!service.preparingSurferFixes();
            surferSequence.setEnabled(canPrepare);
            surferRideSeconds.setEnabled(canPrepare);
            saveSurferModes.setEnabled(canPrepare);
            loadSurferModes.setEnabled(canPrepare);
            loadKml.setEnabled(canPrepare);
            oneHz.setEnabled(canSelectMode);
            twoHz.setEnabled(canSelectMode);
            (service.simulatorRateHz()==1?oneHz:twoHz).setChecked(true);
            applySurferModes.setEnabled(service.canApplySurferModes());
            surferFileStatus.setText(service.surferFileStatus());
            String playback=service.surferPlaybackStatus();
            surferPlaybackStatus.setText(playback);
            surferPlaybackStatus.setVisibility(playback.isEmpty()?View.GONE:View.VISIBLE);
        }
        liveOffsetControls.setVisibility(tracking&&!simulating?View.VISIBLE:View.GONE);
        if(tracking&&!simulating) liveOffsetStatus.setText("Simulation offset · "+LiveGpsSource.describeOffset(service.liveOffsetM()));
        hsResults.setEnabled(service!=null&&service.hsReport()!=null);
        renderReplayReports();
        if(service==null) return;
        gpsStatus.setText(service.gpsText());
        if(!TextUtils.equals(gtiStatus.getText(),service.gtiStatus())) gtiStatus.setText(service.gtiStatus());
        result.setText(service.resultText());
        renderCalibrationStatus();
        FmService.Saved saved=service.loadSaved();
        savedStatus.setText(saved==null?"No saved start position":
            "Start position · GPS #"+saved.sequence()+" · "+saved.point().lat()+", "+saved.point().lon());
        if(pendingSimKml!=null && simulating && (ready||tracking) && !service.preparingSurferFixes()) {
            Uri file=pendingSimKml;
            pendingSimKml=null;
            service.prepareSurferModes(List.of(),0,FmService.SimulatorFile.LOAD_KML,file,replayDisplayName(file));
        }
        if(editSettings && pendingReplayCsv!=null) {
            Uri file=pendingReplayCsv;
            pendingReplayCsv=null;
            saveReplayCsv(file);
        }
        if(linkFolder.isEnabled()&&pendingSettingsFolder!=null) {
            SettingsFolderSelection selection=pendingSettingsFolder;
            pendingSettingsFolder=null;
            service.linkSettingsFolder(selection.uri(),selection.flags(),()->formService=null);
        }
    }

    private void renderReplayReports() {
        if(replayFirstStart!=null) replayFirstStart.setText(replayFirstStartLabel());
        if(replayTotalTime!=null) {
            String total=replayTotalTimeLabel();
            if(!TextUtils.equals(replayTotalTime.getText(),total)) replayTotalTime.setText(total);
        }
        if(replayMotion!=null && service!=null && !TextUtils.equals(replayMotion.getText(),service.replayMotionText()))
            replayMotion.setText(service.replayMotionText());
        int count=service==null?0:service.replayReports().size();
        if(count==replayResultsShown) return;
        replayResultsShown=count;
        replayResults.setEnabled(count>0);
        replayResults.setText(count==0?"View replay reports":"View replay reports ("+count+")");
        if(replayResultsAdapter!=null) replayResultsAdapter.notifyDataSetChanged();
    }

    private void showReplayReports() {
        if(service==null || service.replayReports().isEmpty()) return;
        List<FmService.ReplayRowReport> reports=service.replayReports();
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20),0,dp(20),0);
        replayFirstStart=text(content,replayFirstStartLabel(),16);
        replayTotalTime=text(content,replayTotalTimeLabel(),16);
        replayMotion=text(content,service.replayMotionText(),16);
        text(content,"Green · Required Starts acknowledged\nRed · Blocked or Start issue",13);
        text(content,"Swipe to see all columns. Tap a viewpoint for details.",13);

        HorizontalScrollView horizontal=new HorizontalScrollView(this);
        horizontal.setScrollbarFadingEnabled(false);
        LinearLayout table=new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header=timingRow(true);
        setTimingCells(header,REPLAY_TIMING_COLUMNS);
        table.addView(header);

        ListView rows=new ListView(this);
        replayResultsAdapter=new BaseAdapter() {
            @Override public int getCount() { return reports.size(); }
            @Override public FmService.ReplayRowReport getItem(int position) { return reports.get(position); }
            @Override public long getItemId(int position) { return getItem(position).viewpoint(); }
            @Override public View getView(int position,View convertView,ViewGroup parent) {
                FmService.ReplayRowReport item=getItem(position);
                LinearLayout row=convertView==null?timingRow(false):(LinearLayout)convertView;
                boolean startAcknowledged=item.callToRequiredStartsMs()!=null;
                row.setBackgroundColor(startAcknowledged?0x332e7d32:0x33c62828);
                setTimingCells(row,new String[]{Integer.toString(item.viewpoint()),item.callTime(),
                    timingDuration(item.sincePreviousCallMs()),item.stopMotor(),timingDuration(item.stopCheckWaitMs()),
                    timingDuration(item.rowToBothStoppedMs()),timingDuration(item.csvDelayMs()),
                    timingDuration(item.actualWaitMs()),timingDuration(item.callToRequiredStartsMs())});
                row.getChildAt(0).setContentDescription("Viewpoint "+item.viewpoint()+
                    (startAcknowledged?", required Starts acknowledged":", blocked or Start issue"));
                return row;
            }
        };
        rows.setAdapter(replayResultsAdapter);
        rows.setOnItemClickListener((parent,view,position,id)->{
            FmService.ReplayRowReport item=reports.get(position);
            new AlertDialog.Builder(this).setTitle("Viewpoint "+item.viewpoint()).setMessage(item.details())
                .setPositiveButton("Close",null).show();
        });
        int rowsHeight=Math.min(dp(270),getResources().getDisplayMetrics().heightPixels/3);
        table.addView(rows,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,rowsHeight));
        horizontal.addView(table);
        content.addView(horizontal);

        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Replay reports")
            .setView(content).setPositiveButton("Close",null).create();
        dialog.setOnDismissListener(d->{
            replayResultsAdapter=null;
            replayTotalTime=null;
            replayMotion=null;
            replayFirstStart=null;
        });
        dialog.show();
    }

    private void showHsReport() {
        FmService.HsReport report=service==null?null:service.hsReport();
        if(report==null) return;
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20),0,dp(20),0);
        ScrollView summary=new ScrollView(this);
        TextView summaryText=new TextView(this);
        summaryText.setTextSize(15);
        String result=report.summary();
        android.text.SpannableString highlighted=new android.text.SpannableString(result);
        int warning=result.indexOf("\n"+FmService.HS_SPEED_SHORTFALL);
        if(warning>=0) {
            int start=warning+1,end=result.indexOf('\n',start);
            if(end<0) end=result.length();
            highlighted.setSpan(new android.text.style.ForegroundColorSpan(0xff8b1616),start,end,0);
            highlighted.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),start,end,0);
        }
        summaryText.setText(highlighted);
        summary.addView(summaryText);
        int screen=getResources().getDisplayMetrics().heightPixels;
        if(report.rows().isEmpty()) {
            content.addView(summary,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,screen/2));
        } else {
            content.addView(summary,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,screen/4));
            text(content,"Green row · Observed   Red row · Unobserved\nRed gap · Over "+
                FmService.hsGapWarningLabel(report.gapWarningM())+" at the scheduled time\n"+
                "Swipe for timings. Tap a viewpoint for details.",13);
            HorizontalScrollView horizontal=new HorizontalScrollView(this);
            horizontal.setScrollbarFadingEnabled(false);
            LinearLayout table=new LinearLayout(this);
            table.setOrientation(LinearLayout.VERTICAL);
            int[] widths=Arrays.stream(HS_TIMING_COLUMNS).mapToInt(TimingColumn::widthDp).toArray();
            String[] titles=Arrays.stream(HS_TIMING_COLUMNS).map(TimingColumn::title).toArray(String[]::new);
            LinearLayout header=timingRow(true,widths);
            setTimingCells(header,titles);
            table.addView(header);
            ListView rows=new ListView(this);
            rows.setAdapter(new BaseAdapter() {
                @Override public int getCount() { return report.rows().size(); }
                @Override public FmService.HsRow getItem(int position) { return report.rows().get(position); }
                @Override public long getItemId(int position) { return getItem(position).viewpoint(); }
                @Override public View getView(int position,View convertView,ViewGroup parent) {
                    FmService.HsRow item=getItem(position);
                    LinearLayout row=convertView==null?timingRow(false,widths):(LinearLayout)convertView;
                    row.setBackgroundColor(item.observed()?0x332e7d32:0x33c62828);
                    setTimingCells(row,item.cells());
                    if(item.distanceGapOverLimit()) {
                        TextView gapCell=(TextView)row.getChildAt(HS_GAP_COLUMN);
                        android.text.SpannableString gapText=new android.text.SpannableString(gapCell.getText());
                        gapText.setSpan(new android.text.style.ForegroundColorSpan(0xffd32f2f),0,gapText.length(),0);
                        gapCell.setText(gapText);
                    }
                    return row;
                }
            });
            rows.setOnItemClickListener((parent,view,position,id)->{
                FmService.HsRow item=report.rows().get(position);
                new AlertDialog.Builder(this).setTitle("HS viewpoint "+item.viewpoint()).setMessage(item.details())
                    .setPositiveButton("Close",null).show();
            });
            table.addView(rows,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,Math.min(dp(260),screen/4)));
            horizontal.addView(table);
            content.addView(horizontal);
        }
        new AlertDialog.Builder(this).setTitle("HS viewpoint replay report").setView(content)
            .setPositiveButton("Close",null).show();
    }

    private LinearLayout timingRow(boolean header) { return timingRow(header,REPLAY_TIMING_WIDTHS); }

    private LinearLayout timingRow(boolean header,int[] widths) {
        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if(header) row.setBackgroundColor(0x184a6983);
        for(int width:widths) {
            TextView cell=new TextView(this);
            cell.setTextSize(13);
            cell.setMinHeight(dp(48));
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding(dp(8),dp(4),dp(8),dp(4));
            if(header) {
                cell.setTypeface(cell.getTypeface(),Typeface.BOLD);
                if(Build.VERSION.SDK_INT>=28) cell.setAccessibilityHeading(true);
            }
            row.addView(cell,new LinearLayout.LayoutParams(dp(width),ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return row;
    }

    private static void setTimingCells(LinearLayout row,String[] values) {
        for(int i=0;i<values.length;i++) ((TextView)row.getChildAt(i)).setText(values[i]);
    }

    private static String timingDuration(Long millis) {
        return millis==null?"—":String.format(Locale.US,"%,d ms",millis);
    }

    private String replayFirstStartLabel() {
        return "Button to first successful Start reply · "+timingDuration(service==null?null:service.replayFirstStartMs());
    }

    private String replayTotalTimeLabel() {
        return "Total replay time · "+timingDuration(service==null?null:service.replayDurationMs());
    }

    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }

    private void renderCalibrationStatus() {
        String[] values=SettingsFile.cached(prefs);
        String failure=service.calibrationError();
        boolean checking=service.calibrating();
        boolean failed=!checking&&!failure.isEmpty();
        String status;
        if(checking) status="Capturing the landmark…";
        else if(failed) status="Last calibration failed\n"+failure;
        else if(LandmarkCalibration.hasReference(values)) status=LandmarkCalibration.describe(values);
        else status="Calibration required";

        calibrationStatus.setTextColor(failed?0xff8b1616:0xff263238);
        if(!TextUtils.equals(calibrationStatus.getText(),status)) calibrationStatus.setText(status);
    }

    private TextView text(LinearLayout root,String value,int size) {
        TextView t=new TextView(this);
        t.setTextSize(size);
        t.setText(value);
        t.setPadding(0,8,0,8);
        root.addView(t);
        return t;
    }
    private LinearLayout collapsibleSection(LinearLayout root,String label,boolean expanded) {
        Button header=new Button(this);
        header.setAllCaps(false);
        header.setTextSize(17);
        header.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        root.addView(header);

        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content);
        setSectionExpanded(header,content,label,expanded);
        header.setOnClickListener(v->{
            boolean closing=content.getVisibility()==View.VISIBLE;
            View focused=content.findFocus();
            if(closing && focused!=null) focused.clearFocus();
            setSectionExpanded(header,content,label,!closing);
        });
        return content;
    }

    private static void setSectionExpanded(Button header,LinearLayout content,String label,boolean expanded) {
        header.setText((expanded?"▾  ":"▸  ")+label);
        header.setContentDescription(label+(expanded?", expanded":", collapsed"));
        content.setVisibility(expanded?View.VISIBLE:View.GONE);
    }

    private void addGpsPad(LinearLayout root,Bundle state) {
        gpsPadToggle=new Button(this);
        gpsPadToggle.setAllCaps(false);
        gpsPadToggle.setText("GP Simulator");
        gpsPadToggle.setOnClickListener(v->{
            if(service==null) return;
            if(service.canStopLiveTracking()) service.toggleGpsSimulator();
            else afterSettings(()->service.toggleGpsSimulator());
        });
        root.addView(gpsPadToggle);

        gpsPadControls=new LinearLayout(this);
        gpsPadControls.setOrientation(LinearLayout.VERTICAL);
        gpsPadControls.setVisibility(View.GONE);
        root.addView(gpsPadControls);
        text(gpsPadControls,"Raw GPS fixes per second",14);
        RadioGroup rates=new RadioGroup(this);
        rates.setOrientation(LinearLayout.HORIZONTAL);
        gpsPadControls.addView(rates);
        oneHz=rateOption(rates,1);
        twoHz=rateOption(rates,2);
        TextView label=text(gpsPadControls,"Offset per tap (m)",14);
        gpsPadStep=new EditText(this);
        gpsPadStep.setId(View.generateViewId());
        label.setLabelFor(gpsPadStep.getId());
        gpsPadStep.setSingleLine();
        gpsPadStep.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        gpsPadStep.setText(state==null?GpsPadSimulator.DEFAULT_STEP:state.getString(GPS_PAD_STEP,GpsPadSimulator.DEFAULT_STEP));
        gpsPadControls.addView(gpsPadStep);

        gpsPadRow(GpsPadSimulator.Move.UP_LEFT,GpsPadSimulator.Move.UP,GpsPadSimulator.Move.UP_RIGHT);
        gpsPadRow(GpsPadSimulator.Move.LEFT,null,GpsPadSimulator.Move.RIGHT);
        gpsPadRow(GpsPadSimulator.Move.DOWN_LEFT,GpsPadSimulator.Move.DOWN,GpsPadSimulator.Move.DOWN_RIGHT);
        LinearLayout continuousModes=collapsibleSection(gpsPadControls,"Continuous surfer mode",true);
        text(continuousModes,"Tap a duration to edit. Tap an arrow for manual control.",14);
        for(SurferModes.Mode mode:SurferModes.Mode.values()) addContinuousMode(continuousModes,mode);
        addSurferModes(state);
    }

    private RadioButton rateOption(RadioGroup rates,int hz) {
        RadioButton option=new RadioButton(this);
        option.setText(hz+" Hz");
        option.setId(View.generateViewId());
        rates.addView(option);
        option.setOnClickListener(v->{ if(service!=null) service.setSimulatorRate(hz); });
        return option;
    }

    private void addContinuousMode(LinearLayout parent,SurferModes.Mode mode) {
        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row);

        CheckBox check=new CheckBox(this);
        check.setText(mode.number+"  "+mode.label);
        check.setOnClickListener(view->{
            if(service!=null) service.selectContinuousSurferMode(check.isChecked()?mode:null);
        });
        row.addView(check,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1));

        Button duration=mode==SurferModes.Mode.SITTING?null:new Button(this);
        if(duration!=null) {
            duration.setAllCaps(false);
            showContinuousDuration(duration,mode);
            duration.setOnClickListener(view->chooseContinuousDuration(duration,mode));
            row.addView(duration);
        }
        continuousSurferModes.put(mode,new ContinuousModeControls(check,duration));
    }

    private void showContinuousDuration(Button button,SurferModes.Mode mode) {
        String seconds=prefs.getString(mode.durationPreference(),SurferModes.DEFAULT_CONTINUOUS_SECONDS);
        button.setText(seconds+" s");
        button.setContentDescription(mode.label+" duration, "+seconds+" seconds");
    }

    private void chooseContinuousDuration(Button button,SurferModes.Mode mode) {
        EditText seconds=new EditText(this);
        seconds.setSingleLine();
        seconds.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        seconds.setContentDescription("Duration in seconds");
        seconds.setText(prefs.getString(mode.durationPreference(),SurferModes.DEFAULT_CONTINUOUS_SECONDS));
        seconds.selectAll();

        LinearLayout field=new LinearLayout(this);
        field.setPadding(dp(24),0,dp(24),0);
        field.addView(seconds,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle(mode.label+" duration")
            .setMessage("Seconds. Applies the next time you select this mode.")
            .setView(field)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("OK",null)
            .create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
            String value=seconds.getText().toString().trim();
            try {
                SurferModes.continuousDurationMs(value);
            } catch(IllegalArgumentException invalid) {
                seconds.setError(invalid.getMessage());
                return;
            }
            prefs.edit().putString(mode.durationPreference(),value).apply();
            showContinuousDuration(button,mode);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void addSurferModes(Bundle state) {
        LinearLayout controls=collapsibleSection(gpsPadControls,"Surfer behaviours",true);
        TextView sequenceLabel=text(controls,"Surfer behaviour sequence",14);
        surferSequence=new EditText(this);
        surferSequence.setId(View.generateViewId());
        sequenceLabel.setLabelFor(surferSequence.getId());
        surferSequence.setSingleLine();
        surferSequence.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        surferSequence.setHint("Example: 1,2,3,4,1");
        if(state!=null) surferSequence.setText(state.getString(SURFER_SEQUENCE,""));
        watchSurferInput(surferSequence);
        controls.addView(surferSequence);
        StringBuilder legend=new StringBuilder();
        for(SurferModes.Mode mode:SurferModes.Mode.values()) {
            if(legend.length()>0) legend.append('\n');
            legend.append(mode.number).append("  ").append(mode.label);
        }
        text(collapsibleSection(controls,"Mode numbers",false),legend.toString(),14);

        TextView label=text(controls,"Wave riding duration (s)",14);
        surferRideSeconds=new EditText(this);
        surferRideSeconds.setId(View.generateViewId());
        label.setLabelFor(surferRideSeconds.getId());
        surferRideSeconds.setSingleLine();
        surferRideSeconds.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        String stored=Integer.toString(prefs.getInt(SurferModes.RIDE_SECONDS_PREF,SurferModes.DEFAULT_RIDE_SECONDS));
        surferRideSeconds.setText(state==null?stored:state.getString(SURFER_RIDE_SECONDS,stored));
        watchSurferInput(surferRideSeconds);
        controls.addView(surferRideSeconds);

        saveSurferModes=surferButton(controls,"Save selected surfer modes",()->prepareSurferModes(false));
        loadSurferModes=surferButton(controls,"Load surfer behaviour from existing file",()->prepareSurferModes(true));
        loadKml=surferButton(controls,"Load LoRa Tester KML",()->{
            Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(picker,CHOOSE_SIM_KML);
        });
        applySurferModes=surferButton(controls,"Apply surfer modes",()->{if(service!=null)service.applySurferModes();});
        surferFileStatus=text(controls,"",14);
        surferPlaybackStatus=text(controls,"",14);
    }

    private Button surferButton(LinearLayout controls,String label,Runnable action) {
        Button button=new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setOnClickListener(view->action.run());
        controls.addView(button);
        return button;
    }

    private void watchSurferInput(EditText field) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {
                if(service!=null) service.invalidateSurferPreparation();
            }
            @Override public void afterTextChanged(Editable text) { }
        });
    }

    private void prepareSurferModes(boolean load) {
        if(service==null) return;
        List<SurferModes.Mode> selected=List.of();
        if(!load) {
            try { selected=SurferModes.parseSequence(surferSequence.getText().toString()); }
            catch(IllegalArgumentException invalid) {
                surferSequence.requestFocus();
                surferSequence.setError(invalid.getMessage());
                return;
            }
        }
        surferSequence.setError(null);
        int seconds=SurferModes.DEFAULT_RIDE_SECONDS;
        if(!load&&selected.contains(SurferModes.Mode.RIDING)) {
            try { seconds=Integer.parseInt(surferRideSeconds.getText().toString().trim()); }
            catch(NumberFormatException failure) {
                surferRideSeconds.setError("Enter the duration in whole seconds");
                return;
            }
        }
        surferRideSeconds.setError(null);
        service.prepareSurferModes(selected,seconds,load?FmService.SimulatorFile.LOAD_CSV:FmService.SimulatorFile.SAVE_CSV,null,null);
    }

    private void gpsPadRow(GpsPadSimulator.Move... moves) {
        LinearLayout row=new LinearLayout(this);
        gpsPadControls.addView(row);
        for(GpsPadSimulator.Move move:moves) {
            View cell;
            if(move==null) cell=new Space(this);
            else {
                Button direction=new Button(this);
                direction.setText(move.arrow);
                direction.setTextSize(24);
                direction.setContentDescription(move.label);
                direction.setOnClickListener(v->moveGpsPad(move));
                cell=direction;
            }
            row.addView(cell,new LinearLayout.LayoutParams(0,dp(52),1));
        }
    }

    private void moveGpsPad(GpsPadSimulator.Move move) {
        if(service==null) return;
        try {
            double metres=Double.parseDouble(gpsPadStep.getText().toString().trim().replace(',','.'));
            service.moveGpsSimulator(move,metres);
            gpsPadStep.setError(null);
        } catch(IllegalArgumentException invalid) {
            gpsPadStep.setError("Enter a positive offset in metres");
        }
    }

    private void liveOffsetButton(LinearLayout parent,double stepM) {
        Button button=new Button(this);
        button.setAllCaps(false);
        String sign=stepM>0?"+":"−";
        button.setText("Offset surfer position ("+sign+LiveGpsSource.describeOffset(stepM)+")");
        button.setOnClickListener(v->{ if(service!=null) service.offsetSurferPosition(stepM); });
        parent.addView(button);
    }

    private Button button(LinearLayout root,String label,Runnable action) {
        Button b=new Button(this);
        b.setText(label); b.setAllCaps(false);
        b.setOnClickListener(v->action.run()); root.addView(b); buttons.add(b); return b;
    }

    private EditText addSetting(LinearLayout root,String[] values,int index) {
        TextView caption=text(root,settingLabel(index),14);
        EditText input=new EditText(this);
        input.setSingleLine();
        input.setId(View.generateViewId());
        caption.setLabelFor(input.getId());
        if(isCoordinatePair(index)) input.setHint("e.g. "+MountCoordinates.EXAMPLE);
        if(index==FmService.HS_GAP_WARNING||index==FmService.LIVE_TILT_SPEED||index==FmService.LIVE_PAN_SPEED_MULTIPLIER
            ||index==FmService.LIVE_PAN_LEAD||index==FmService.SIMULATOR_SHOT_WIDTH||index==FmService.LIVE_MIN_RANGE)
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if(index==FmService.LIVE_GPS_HOLD||index==FmService.LIVE_GPS_STATIONARY_MS)
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(displayValue(values,index));
        root.addView(input);
        inputs[index]=input;
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {
                if(!restoringSettings && formService!=null) {
                    autosave.edit(index,s.toString().trim());
                    input.setError(null);
                    settingsSaveStatus.setText("Editing · "+settingLabel(index));
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        input.setOnFocusChangeListener((v,focused)->{
            if(!focused && !restoringSettings && service!=null) autosave.finishField(index);
        });
        input.setOnEditorActionListener((v,action,event)->{
            if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                (event!=null && event.getKeyCode()==KeyEvent.KEYCODE_ENTER && event.getAction()==KeyEvent.ACTION_UP))
                autosave.finishField(index);
            return false;
        });
        return input;
    }

    private static String settingLabel(int index) {
        if(index==CameraSettings.FIRST) return "Canon clip limit (minutes, 0 = continuous)";
        if(index==CameraSettings.FIRST+1) return "Canon zoom mode (off / bands / auto-fit)";
        if(index==MountCoordinates.LATITUDE) return MountCoordinates.KEY;
        if(index==LandmarkCalibration.LANDMARK_LAT) return LandmarkCalibration.COORDINATES;
        return FmService.KEYS[index];
    }

    private static String displayValue(String[] values,int index) {
        return isCoordinatePair(index) ? MountCoordinates.format(values,index,index+1) : values[index];
    }

    private static boolean isCoordinatePair(int index) {
        return index==MountCoordinates.LATITUDE||index==LandmarkCalibration.LANDMARK_LAT;
    }

    private void captureLandmarkGps() {
        FmService connected=service;
        if(connected==null || !connected.ready() || autosave.waitingForAction()) return;
        long press=GoTo.now(),wallTime=System.currentTimeMillis();
        View focused=getCurrentFocus();
        if(focused instanceof EditText) focused.clearFocus();
        SettingsAutosave.Edit previousDraft=autosave.draft(LandmarkCalibration.LANDMARK_LAT);
        landmarkGpsStatus.setText("Waiting for 10 fresh LoRa GPS fixes…");
        landmarkGpsStatus.setVisibility(View.VISIBLE);
        autosave.afterPendingWrites(()->connected.captureLandmarkGps(press,wallTime,capture->{
            if(capture.coordinates()!=null) {
                autosave.discardDraft(previousDraft);
                formService=null; // Reload the committed pair without triggering a second write.
            }
            if(bound && service==connected) {
                if(capture.coordinates()!=null) landmarkCoordinates.setError(null);
                landmarkGpsStatus.setText(capture.message());
            }
        }));
        if(autosave.waitingForAction()) render();
    }

    private void writeSettingsEdit(SettingsAutosave.Edit edit, java.util.function.Consumer<String> completed) {
        if(formService==null) {
            completed.accept("Settings are still loading");
            return;
        }
        settingsSaveStatus.setText("Saving settings…");
        formService.saveEdit(edit,completed);
    }

    private void settingsSaveResult(SettingsAutosave.Edit edit,String error) {
        if(error!=null) startingLive=false;
        EditText input=inputs[edit.index()];
        if(input!=null) input.setError(error);
        String message=error==null?"Saved · "+settingLabel(edit.index()):"Settings not saved · "+error;
        settingsSaveStatus.setText(message);
        if(error!=null && bound) Toast.makeText(this,message,Toast.LENGTH_LONG).show();
        if(bound) settingsSaveStatus.post(()->{ if(bound) render(); });
    }

    private void afterSettings(Runnable action) {
        FmService connected=service;
        if(connected==null || !connected.ready() || autosave.waitingForAction()) return;
        autosave.finishAll(()->{
            if(service==connected && connected.ready()) action.run();
            if(bound) render();
        });
        if(autosave.waitingForAction()) render();
    }

    private void startLiveTracking() {
        FmService connected=service;
        if(connected==null || !connected.ready() || autosave.waitingForAction()) return;
        long press=GoTo.now();
        java.time.Instant pressedAt=java.time.Instant.now();
        startingLive=true;
        autosave.finishAll(()->{
            startingLive=false;
            if(service==connected && connected.ready()) connected.launchLiveTracking(SettingsFile.cached(prefs),press,pressedAt);
            if(bound) render();
        });
        if(autosave.waitingForAction()) render();
    }

    private void discover(boolean mount) {
        service.discover(mount,SettingsFile.cached(prefs),found->{
            if(!bound || found.isEmpty()) return;
            String[] options=found.stream().map(DeviceDiscovery.Device::label).toArray(String[]::new);
            new AlertDialog.Builder(this).setTitle(mount?"Select GTi":"Select Canon")
                .setItems(options,(dialog,index)->{
                    int host=mount?4:6;
                    inputs[host].setText(found.get(index).address());
                    autosave.finishField(host);
                }).show();
        });
    }
    private boolean gdlPermissionGranted() {
        String permission=Build.VERSION.SDK_INT>=33?Manifest.permission.NEARBY_WIFI_DEVICES:Manifest.permission.ACCESS_FINE_LOCATION;
        return checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;
    }

    private void chooseGdl() {
        if(Build.VERSION.SDK_INT<29) {
            startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
            return;
        }
        if(!gdlPermissionGranted()) {
            requestPermissions(Build.VERSION.SDK_INT>=33?new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}:
                new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},GDL_PERMISSION);
            return;
        }
        showGdlCredentials=true;
        render();
    }

    private void gdlCredentials() {
        if(service==null) return;
        HotspotSettings selected=service.hotspot();
        LinearLayout fields=new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20),dp(4),dp(20),0);
        Spinner security=new Spinner(this);
        security.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"WPA2","WPA3","Open network"}));
        fields.addView(security);
        EditText password=new EditText(this);
        password.setSingleLine();
        password.setHint("Hotspot Wi-Fi password");
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(selected.password());
        fields.addView(password);
        security.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                password.setVisibility(position==2?View.GONE:View.VISIBLE);
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        if(selected.password().isEmpty()) security.setSelection(2);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Connect to "+selected.name()).setView(fields)
            .setNegativeButton("Cancel",null).setPositiveButton("Connect",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String pass=password.getText().toString();
            if(security.getSelectedItemPosition()!=2 && (pass.length()<8 || pass.length()>63)) {
                password.setError("Enter the 8–63 character Wi-Fi password");
                return;
            }
            if(service!=null) service.connectGdl(selected.name(),security.getSelectedItem().toString(),pass);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void chooseSettingsFolder() {
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        picker.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,SettingsFile.initialFolder());
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(picker,LINK_SETTINGS_FOLDER);
    }

    private void chooseReplayCsv() {
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(picker,CHOOSE_REPLAY_CSV);
    }

    @Override protected void onActivityResult(int request,int code,Intent data) {
        super.onActivityResult(request,code,data);
        if(code!=RESULT_OK || data==null || data.getData()==null) return;
        Uri uri=data.getData();
        if(request==CHOOSE_SIM_KML) {
            pendingSimKml=uri;
            render();
        } else if(request==CHOOSE_REPLAY_CSV) {
            pendingReplayCsv=uri;
            render();
        }
        else if(request==LINK_SETTINGS_FOLDER) {
            pendingSettingsFolder=new SettingsFolderSelection(uri,data.getFlags());
            render();
        }
    }

    private void saveReplayCsv(Uri uri) {
        ContentResolver resolver=getContentResolver();
        try {
            resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch(SecurityException e) {
            Toast.makeText(this,"Could not save access to this file. Choose a CSV from internal storage.",
                Toast.LENGTH_LONG).show();
            return;
        }
        String previous=prefs.getString(FmService.REPLAY_URI,"");
        prefs.edit().putString(FmService.REPLAY_URI,uri.toString()).putString(REPLAY_NAME,replayDisplayName(uri)).apply();
        if(!previous.isEmpty()&&!previous.equals(uri.toString())) {
            service.releaseOldReplayFile(previous);
        }
        updateReplayFileStatus();
    }

    private String replayDisplayName(Uri uri) {
        try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if(cursor!=null&&cursor.moveToFirst()) {
                String name=cursor.getString(0);
                if(!TextUtils.isEmpty(name)) return name;
            }
        } catch(RuntimeException ignored) { }
        return "Selected document";
    }

    private void updateReplayFileStatus() {
        String name=prefs.getString(REPLAY_NAME,"");
        replayFileStatus.setText(name.isEmpty()?"Replay CSV: choose a file in Settings":"Replay CSV · "+name);
    }

    private void replayViewpoints() {
        if(service==null||!service.ready()) return;
        String selected=prefs.getString(FmService.REPLAY_URI,"");
        if(selected.isEmpty()) {
            Toast.makeText(this,"Use Choose replay CSV in Settings first.",Toast.LENGTH_LONG).show();
            return;
        }
        service.launch("Replay viewpoints",Uri.parse(selected),SettingsFile.cached(prefs));
    }

    private void replayHighSpeed() {
        if(service==null||!service.ready()) return;
        String selected=prefs.getString(FmService.REPLAY_URI,"");
        if(selected.isEmpty()) {
            Toast.makeText(this,"Use Choose replay CSV in Settings first.",Toast.LENGTH_LONG).show();
            return;
        }
        service.launchHs(Uri.parse(selected),SettingsFile.cached(prefs));
    }


}

