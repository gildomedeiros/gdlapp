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
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int CHOOSE_REPLAY_CSV=1, EXPORT_LOG=2, LINK_SETTINGS_FOLDER=5;
    private static final String SETTINGS_OPEN="settingsExpanded", CALIBRATION_OPEN="calibrationExpanded";
    private static final String REPLAY_URI="replayCsvUri", REPLAY_NAME="replayCsvName";
    private static final String GPS_PAD_STEP="gpsPadStep";
    private static final String SURFER_SEQUENCE="surferSequence", SURFER_RIDE_SECONDS="surferRideSecondsText";
    private static final String[] REPLAY_TIMING_COLUMNS={"Viewpoint","GoToPosition time","Since previous call",
        "Stop motor","Stop check wait","Row → both stopped","CSV delay","Actual wait","Call → both Start replies"};
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
    private TextView gpsStatus,gtiStatus,result,savedStatus,calibrationStatus,aimCalibrationStatus,replayFileStatus;
    private TextView replayTotalTime,replayMotion,replayFirstStart,landmarkGpsStatus;
    private EditText mountCoordinates,landmarkCoordinates;
    private LinearLayout settingsPanel,calibrationPanel;
    private CheckBox calibrationConfirmed;
    private Button startMonitoring,replayResults,hsResults,hsCancel,liveStop;
    private LinearLayout liveOffsetControls,gpsPadControls;
    private Button gpsPadToggle;
    private Button saveSurferModes,loadSurferModes,applySurferModes;
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
    private boolean bound,exporting;
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
        ScrollView scroll=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        int padding=(int)(20*getResources().getDisplayMetrics().density);
        root.setPadding(padding,padding*2,padding,padding);
        scroll.addView(root);
        setContentView(scroll);
        TextView title=text(root,"FM "+BuildConfig.VERSION_NAME,30);
        if(Build.VERSION.SDK_INT>=28) title.setAccessibilityHeading(true);
        String[] values=SettingsFile.cached(prefs);
        text(root,"Move report",18);
        result=text(root,"Starting FM",16);
        gpsStatus=text(root,"Starting GPS",16);
        liveGpsRuleStatus=text(root,"",14);
        liveGpsRuleStatus.setVisibility(View.GONE);
        addGpsPad(root,state);
        gtiStatus=text(root,"GTi disconnected",15);
        button(root,"Connect GTi",()->service.connectGti());
        savedStatus=text(root,"",15);
        aimCalibrationStatus=text(root,"Checking calibration…",15);
        aimCalibrationStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        button(root,"Aim at Surfer HS",()->service.launchAim(SettingsFile.cached(prefs)));
        button(root,"Live Tracking",()->service.launchLiveTracking(SettingsFile.cached(prefs)));
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
        button(root,"GoToMe",()->service.launch("GoToMe",null,SettingsFile.cached(prefs)));
        button(root,"Go to start",()->service.launch("GoHome",null,SettingsFile.cached(prefs)));
        button(root,"Replay viewpoints",this::replayViewpoints);
        replayFileStatus=text(root,"",14);
        updateReplayFileStatus();
        replayResults=new Button(this);
        replayResults.setAllCaps(false);
        replayResults.setText("View replay reports");
        replayResults.setEnabled(false);
        replayResults.setOnClickListener(v->showReplayReports());
        root.addView(replayResults);
        button(root,"Replay viewpoints (HS)",this::replayHighSpeed);
        hsCancel=new Button(this);
        hsCancel.setAllCaps(false);
        hsCancel.setText("Stop HS replay");
        hsCancel.setVisibility(View.GONE);
        hsCancel.setOnClickListener(v->{ if(service!=null) service.cancelHs(); });
        root.addView(hsCancel);
        hsResults=new Button(this);
        hsResults.setAllCaps(false);
        hsResults.setText("View HS replay report");
        hsResults.setEnabled(false);
        hsResults.setOnClickListener(v->showHsReport());
        root.addView(hsResults);
        startMonitoring=new Button(this);
        startMonitoring.setText("Start FM");
        startMonitoring.setAllCaps(false);
        startMonitoring.setOnClickListener(v->connect());
        startMonitoring.setVisibility(View.GONE);
        root.addView(startMonitoring);
        settingsPanel=collapsibleSection(root,"Settings",state!=null&&state.getBoolean(SETTINGS_OPEN));
        mountCoordinates=addSetting(settingsPanel,values,MountCoordinates.LATITUDE);
        button(settingsPanel,"Save mount coordinates",this::saveMountCoordinates);
        button(settingsPanel,"Choose replay CSV",this::chooseReplayCsv);
        text(settingsPanel,"Replay CSV columns\nlatitude,longitude,elevation_m,delay_ms,wait for moto full stop,stop motor,speed up\n"+
            "For HS, set delay_ms to 0 on the first viewpoint and to the travel time from the previous viewpoint (ms) on each later row. "+
            "Use 1.5x or 2.0x under speed up at intermediate HS viewpoints.",14);
        text(settingsPanel,"Save settings to apply changes and write "+SettingsFile.LOCATION+".",14);
        button(settingsPanel,"Link FM settings folder",this::chooseSettingsFolder);
        for(int i=0;i<MountCoordinates.LATITUDE;i++) addSetting(settingsPanel,values,i);
        button(settingsPanel,"Discover GTi",()->discover(true));
        button(settingsPanel,"Discover Canon SX",()->discover(false));
        addSetting(settingsPanel,values,LandmarkCalibration.PIVOT_ELEVATION);
        addSetting(settingsPanel,values,11);
        addSetting(settingsPanel,values,FmService.HS_GAP_WARNING);
        addSetting(settingsPanel,values,FmService.LIVE_PAN_SPEED);
        addSetting(settingsPanel,values,FmService.LIVE_TILT_SPEED);
        addSetting(settingsPanel,values,FmService.LIVE_GPS_HOLD);
        addSetting(settingsPanel,values,FmService.LIVE_GPS_STATIONARY_MS);
        button(settingsPanel,"Save settings",this::submitSettings);

        calibrationPanel=collapsibleSection(root,"Landmark calibration",state!=null&&state.getBoolean(CALIBRATION_OPEN));
        text(calibrationPanel,"Enter the coordinates and height of the exact point you will centre. Use the same height reference as the camera pivot.",14);
        landmarkCoordinates=addSetting(calibrationPanel,values,LandmarkCalibration.LANDMARK_LAT);
        button(calibrationPanel,"Capture landmark from LoRa GPS",this::captureLandmarkGps);
        text(calibrationPanel,"Place the LoRa GPS unit at the landmark before capturing.",14);
        landmarkGpsStatus=text(calibrationPanel,"",14);
        landmarkGpsStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        landmarkGpsStatus.setVisibility(View.GONE);
        addSetting(calibrationPanel,values,LandmarkCalibration.LANDMARK_HEIGHT);
        addSetting(calibrationPanel,values,16);
        addSetting(calibrationPanel,values,17);
        text(calibrationPanel,"Centre the landmark in the camera. Calibrate again after moving the tripod, adjusting the camera, releasing a clutch or restarting the GTi.",14);
        calibrationConfirmed=new CheckBox(this);
        calibrationConfirmed.setText("Mount is level and secure, and Right / Up directions have been checked");
        calibrationPanel.addView(calibrationConfirmed);
        calibrationStatus=text(calibrationPanel,"Checking calibration…",15);
        calibrationStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        button(calibrationPanel,"Calibrate here",()->service.calibrateHere(snapshot(),
            mountCoordinates.getText().toString(),landmarkCoordinates.getText().toString(),calibrationConfirmed.isChecked()));

        text(root,"Logs",22);
        text(root,"Automatic logs · "+AttemptDownload.DIRECTORY,14);
        button(root,"Save a copy of the last log",this::chooseLogDestination);
        root.requestFocus();
        render();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean(SETTINGS_OPEN,settingsPanel.getVisibility()==View.VISIBLE);
        state.putBoolean(CALIBRATION_OPEN,calibrationPanel.getVisibility()==View.VISIBLE);
        state.putString(GPS_PAD_STEP,gpsPadStep.getText().toString());
        state.putString(SURFER_SEQUENCE,surferSequence.getText().toString());
        state.putString(SURFER_RIDE_SECONDS,surferRideSeconds.getText().toString());
    }

    @Override protected void onStart() {
        super.onStart();
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
    }
    private void connect() {
        if(bound) return;
        try {
            Intent intent=new Intent(this,FmService.class);
            startForegroundService(intent);
            bound=bindService(intent,connection,BIND_AUTO_CREATE);
            startMonitoring.setVisibility(View.GONE);
        } catch(RuntimeException e) { result.setText("Cannot start FM: "+e.getMessage()); }
    }
    private void disconnect() {
        if(service!=null) service.detach(listener);
        if(bound) unbindService(connection);
        bound=false;
        service=null;
    }
    @Override protected void onStop() {
        disconnect();
        super.onStop();
    }
    private void render() {
        if(service!=null && service.stopped()) {
            disconnect();
            gpsStatus.setText("FM stopped");
            gtiStatus.setText("GTi disconnected");
            startMonitoring.setVisibility(View.VISIBLE);
        }
        boolean ready=service!=null && service.ready();
        if(ready && formService!=service) {
            String[] saved=SettingsFile.cached(prefs);
            for(int i=0;i<inputs.length;i++) if(inputs[i]!=null) inputs[i].setText(displayValue(saved,i));
            formService=service;
        }
        for(Button b:buttons) b.setEnabled(ready&&!exporting);
        for(EditText input:inputs) if(input!=null) input.setEnabled(ready&&!exporting);
        calibrationConfirmed.setEnabled(ready&&!exporting);
        hsCancel.setVisibility(service!=null&&service.canCancelHs()?View.VISIBLE:View.GONE);
        boolean tracking=service!=null&&service.canStopLiveTracking();
        String gpsRule=tracking?service.liveGpsRuleText():"";
        liveGpsRuleStatus.setVisibility(gpsRule.isEmpty()?View.GONE:View.VISIBLE);
        if(!TextUtils.equals(liveGpsRuleStatus.getText(),gpsRule)) liveGpsRuleStatus.setText(gpsRule);
        liveStop.setVisibility(tracking?View.VISIBLE:View.GONE);
        boolean simulating=service!=null&&service.gpsSimulatorActive();
        gpsPadToggle.setEnabled((ready||tracking)&&!exporting);
        gpsPadToggle.setText(simulating?"Use LoRa GPS":"GP Simulator");
        gpsPadControls.setVisibility(simulating?View.VISIBLE:View.GONE);
        if(simulating) {
            SurferModes.Mode activeMode=service.continuousSurferMode();
            boolean canSelectMode=(ready||tracking)&&!exporting;
            continuousSurferModes.forEach((mode,controls)->{
                controls.select().setChecked(mode==activeMode);
                controls.select().setEnabled(canSelectMode);
                if(controls.duration()!=null) controls.duration().setEnabled(canSelectMode);
            });
            boolean canPrepare=(ready||tracking)&&!exporting&&!service.preparingSurferFixes();
            surferSequence.setEnabled(canPrepare);
            surferRideSeconds.setEnabled(canPrepare);
            saveSurferModes.setEnabled(canPrepare);
            loadSurferModes.setEnabled(canPrepare);
            applySurferModes.setEnabled(!exporting&&service.canApplySurferModes());
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
        if(ready&&pendingSettingsFolder!=null) {
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
        text(content,"Green · Both Starts acknowledged\nRed · Blocked or Start issue",13);
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
                boolean startAcknowledged=item.callToBothStartsMs()!=null;
                row.setBackgroundColor(startAcknowledged?0x332e7d32:0x33c62828);
                setTimingCells(row,new String[]{Integer.toString(item.viewpoint()),item.callTime(),
                    timingDuration(item.sincePreviousCallMs()),item.stopMotor(),timingDuration(item.stopCheckWaitMs()),
                    timingDuration(item.rowToBothStoppedMs()),timingDuration(item.csvDelayMs()),
                    timingDuration(item.actualWaitMs()),timingDuration(item.callToBothStartsMs())});
                row.getChildAt(0).setContentDescription("Viewpoint "+item.viewpoint()+
                    (startAcknowledged?", both Starts acknowledged":", blocked or Start issue"));
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
        else status="Calibration required. Centre the landmark and tap Calibrate here.";

        for(TextView view:new TextView[]{aimCalibrationStatus,calibrationStatus}) {
            int color=failed?0xff8b1616:0xff263238;
            if(view.getCurrentTextColor()!=color) {
                view.setTextColor(color);
                view.setBackgroundColor(failed?0xffffebeb:android.graphics.Color.TRANSPARENT);
            }
            if(!TextUtils.equals(view.getText(),status)) view.setText(status);
        }
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
        header.setOnClickListener(v->setSectionExpanded(header,content,label,content.getVisibility()!=View.VISIBLE));
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
        gpsPadToggle.setOnClickListener(v->{ if(service!=null) service.toggleGpsSimulator(); });
        root.addView(gpsPadToggle);

        gpsPadControls=new LinearLayout(this);
        gpsPadControls.setOrientation(LinearLayout.VERTICAL);
        gpsPadControls.setVisibility(View.GONE);
        root.addView(gpsPadControls);
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
        text(continuousModes,"Tap the time beside a mode to change its duration. Clear the selection or use an arrow for manual control.",14);
        for(SurferModes.Mode mode:SurferModes.Mode.values()) addContinuousMode(continuousModes,mode);
        addSurferModes(state);
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
        text(controls,legend.toString(),14);

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

        text(controls,SurferFixFile.LOCATION,14);
        text(controls,"Start Live Tracking before Apply to measure pan reaction time.",14);
        saveSurferModes=surferButton(controls,"Save selected surfer modes",()->prepareSurferModes(false));
        loadSurferModes=surferButton(controls,"Load surfer behaviour from existing file",()->prepareSurferModes(true));
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
        service.prepareSurferModes(selected,seconds,load);
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

    private void button(LinearLayout root,String label,Runnable action) {
        Button b=new Button(this);
        b.setText(label); b.setAllCaps(false);
        b.setOnClickListener(v->action.run()); root.addView(b); buttons.add(b);
    }

    private EditText addSetting(LinearLayout root,String[] values,int index) {
        TextView caption=text(root,settingLabel(index),14);
        EditText input=new EditText(this);
        input.setSingleLine();
        input.setId(View.generateViewId());
        caption.setLabelFor(input.getId());
        if(isCoordinatePair(index)) input.setHint("e.g. "+MountCoordinates.EXAMPLE);
        if(index==FmService.HS_GAP_WARNING||index==FmService.LIVE_PAN_SPEED||index==FmService.LIVE_TILT_SPEED)
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if(index==FmService.LIVE_GPS_HOLD||index==FmService.LIVE_GPS_STATIONARY_MS)
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(displayValue(values,index));
        root.addView(input);
        inputs[index]=input;
        return input;
    }

    private static String settingLabel(int index) {
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
        landmarkGpsStatus.setText("Reading latest LoRa GPS fix…");
        landmarkGpsStatus.setVisibility(View.VISIBLE);
        connected.captureLandmarkGps(capture->{
            if(!bound||service!=connected) return;
            if(capture.coordinates()!=null) {
                landmarkCoordinates.setText(capture.coordinates());
                landmarkCoordinates.setError(null);
            }
            landmarkGpsStatus.setText(capture.status());
        });
    }

    private void saveMountCoordinates() {
        String[] values=SettingsFile.cached(prefs);
        if(!readPair(mountCoordinates,values,MountCoordinates.LATITUDE)) return;
        if(values[MountCoordinates.LATITUDE].isEmpty()) {
            mountCoordinates.setError("Paste the Google Maps latitude, longitude pair");
            mountCoordinates.requestFocus();
            return;
        }
        service.saveSettings(values);
    }

    private void submitSettings() {
        String[] values=snapshot();
        if(!readPair(mountCoordinates,values,MountCoordinates.LATITUDE)||
           !readPair(landmarkCoordinates,values,LandmarkCalibration.LANDMARK_LAT)) return;
        service.saveSettings(values);
    }

    private boolean readPair(EditText field,String[] values,int index) {
        try {
            MountCoordinates.apply(field.getText().toString(),values,index,index+1,settingLabel(index));
            field.setError(null);
            return true;
        } catch(IllegalArgumentException e) {
            field.setError(e.getMessage());
            field.requestFocus();
            return false;
        }
    }

    private String[] snapshot() {
        String[] values=SettingsFile.cached(prefs);
        for(int i=0;i<values.length;i++) if(inputs[i]!=null&&!isCoordinatePair(i)) values[i]=inputs[i].getText().toString().trim();
        return values;
    }

    private void discover(boolean mount) {
        service.discover(mount,snapshot(),found->{
            if(!bound || found.isEmpty()) return;
            String[] options=found.stream().map(DeviceDiscovery.Device::label).toArray(String[]::new);
            new AlertDialog.Builder(this).setTitle(mount?"Select GTi":"Select Canon")
                .setItems(options,(dialog,index)->inputs[mount?4:6].setText(found.get(index).address())).show();
        });
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

    private void chooseLogDestination() {
        String last=prefs.getString("lastLog","");
        if(last.isEmpty()) { result.setText("No attempt log yet"); return; }
        Intent picker=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/x-ndjson").putExtra(Intent.EXTRA_TITLE,last);
        startActivityForResult(picker,EXPORT_LOG);
    }

    @Override protected void onActivityResult(int request,int code,Intent data) {
        super.onActivityResult(request,code,data);
        if(code!=RESULT_OK || data==null || data.getData()==null) return;
        Uri uri=data.getData();
        if(request==CHOOSE_REPLAY_CSV) saveReplayCsv(uri);
        else if(request==EXPORT_LOG) export(uri);
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
        String previous=prefs.getString(REPLAY_URI,"");
        prefs.edit().putString(REPLAY_URI,uri.toString()).putString(REPLAY_NAME,replayDisplayName(uri)).apply();
        if(!previous.isEmpty()&&!previous.equals(uri.toString())) {
            try { resolver.releasePersistableUriPermission(Uri.parse(previous),Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch(SecurityException ignored) { }
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
        String selected=prefs.getString(REPLAY_URI,"");
        if(selected.isEmpty()) {
            Toast.makeText(this,"Use Choose replay CSV in Settings first.",Toast.LENGTH_LONG).show();
            return;
        }
        service.launch("Replay viewpoints",Uri.parse(selected),SettingsFile.cached(prefs));
    }

    private void replayHighSpeed() {
        if(service==null||!service.ready()) return;
        String selected=prefs.getString(REPLAY_URI,"");
        if(selected.isEmpty()) {
            Toast.makeText(this,"Use Choose replay CSV in Settings first.",Toast.LENGTH_LONG).show();
            return;
        }
        service.launchHs(Uri.parse(selected),SettingsFile.cached(prefs));
    }

    private void export(Uri uri) {
        String last=prefs.getString("lastLog","");
        exporting=true;
        render();
        new Thread(()->{
            String status;
            try(InputStream in=new FileInputStream(new File(getFilesDir(),last));
                OutputStream out=getContentResolver().openOutputStream(uri)) {
                if(out==null) throw new IOException("Log destination unavailable");
                byte[] buffer=new byte[8192];
                for(int n;(n=in.read(buffer))!=-1;) out.write(buffer,0,n);
                status="Exported "+last;
            } catch(IOException e) { status="Export failed: "+e.getMessage(); }
            String display=status;
            runOnUiThread(()->{exporting=false;render();result.setText(display);});
        },"FM log export").start();
    }
}

