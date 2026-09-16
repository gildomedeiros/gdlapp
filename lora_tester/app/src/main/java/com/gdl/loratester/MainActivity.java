package com.gdl.loratester;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String USB_PERMISSION="com.gdl.loratester.USB_PERMISSION";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView connection,metrics,position,raw,file,age;
    private Button start;
    private Button phoneStart;
    private TextView phoneStatus,phonePosition,phoneFiles,phoneAge;
    private boolean phonePermissionPending,phoneWanted;
    private boolean awaitingPermission, wanted=true;
    private String localStatus;
    private final BroadcastReceiver usbReceiver=new BroadcastReceiver() {
        public void onReceive(Context context,Intent intent) {
            if(USB_PERMISSION.equals(intent.getAction())) {
                awaitingPermission=false;
                if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)) { if(wanted) connect(); }
                else { wanted=false; localStatus="USB permission declined. Tap Start to try again."; }
            } else if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction()) && wanted) connect();
        }
    };
    private final Runnable redraw=new Runnable() {
        public void run() {
            connection.setText(localStatus!=null?localStatus:CaptureService.status);
            position.setText(CaptureService.telemetry);
            metrics.setText(CaptureService.counters.isEmpty()?"Every reading is saved, including bad data and duplicates.":CaptureService.counters);
            raw.setText(CaptureService.recent.isEmpty()?"Waiting for receiver output…":CaptureService.recent);
            file.setText(CaptureService.file.isEmpty()?"Logs save automatically in Downloads/lora_tester/":CaptureService.file);
            long last=CaptureService.lastReading;
            age.setText(last==0?"No readings yet":String.format(Locale.US,"Last reading %.1f seconds ago",(SystemClock.elapsedRealtime()-last)/1000.0));
            age.setTextColor(last>0 && SystemClock.elapsedRealtime()-last>3000?0xffffbd69:0xff8ce4cd);
            start.setEnabled(!CaptureService.active && !awaitingPermission);
            phoneStatus.setText(PhoneCaptureService.status);
            phonePosition.setText(PhoneCaptureService.telemetry);
            phoneFiles.setText(PhoneCaptureService.files.isEmpty()?"Automatic files: lora_tester_phone_….kml + .jsonl":PhoneCaptureService.files);
            long phoneLast=PhoneCaptureService.lastReading;
            phoneAge.setText(phoneLast==0?"No phone readings yet":String.format(Locale.US,"Last phone reading %.1f seconds ago",(SystemClock.elapsedRealtime()-phoneLast)/1000.0));
            phoneStart.setEnabled(!PhoneCaptureService.active && !phonePermissionPending);
            handler.postDelayed(this,500);
        }
    };
    // The unflagged overload is used only before API 33; newer releases use NOT_EXPORTED.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if(saved!=null) wanted=saved.getBoolean("wanted",true);
        if(saved!=null){phoneWanted=saved.getBoolean("phoneWanted",false);phonePermissionPending=saved.getBoolean("phonePending",false);}
        getWindow().setStatusBarColor(0xff0d1720); getWindow().setNavigationBarColor(0xff0d1720);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xff0d1720);
        LinearLayout body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(22),dp(20),dp(22),dp(24)); scroll.addView(body);
        TextView title=text("lora_tester",30); title.setTypeface(null,Typeface.BOLD); body.addView(title);
        TextView subtitle=text("USB OTG  •  CP2102  •  115200 baud",13); subtitle.setTextColor(0xff9bafc2); body.addView(subtitle);
        connection=card(body,"Ready",17);
        LinearLayout actions=new LinearLayout(this);
        start=new Button(this); start.setText("Start LoRa"); actions.addView(start,new LinearLayout.LayoutParams(0,dp(54),1));
        Button stop=new Button(this); stop.setText("Stop LoRa"); actions.addView(stop,new LinearLayout.LayoutParams(0,dp(54),1)); body.addView(actions);
        start.setOnClickListener(v->{wanted=true; localStatus=null; connect();});
        stop.setOnClickListener(v->{wanted=false; localStatus=null; if(CaptureService.active) startService(new Intent(this,CaptureService.class).setAction("STOP")); else localStatus="Stopped";});
        label(body,"PHONE GPS — CAN RUN ALONGSIDE LORA");
        LinearLayout phoneActions=new LinearLayout(this);
        phoneStart=new Button(this);phoneStart.setText("Start phone");phoneActions.addView(phoneStart,new LinearLayout.LayoutParams(0,dp(54),1));
        Button phoneStop=new Button(this);phoneStop.setText("Stop phone");phoneActions.addView(phoneStop,new LinearLayout.LayoutParams(0,dp(54),1));body.addView(phoneActions);
        phoneStart.setOnClickListener(v->{phoneWanted=true;startPhone();});
        phoneStop.setOnClickListener(v->{phoneWanted=false;if(PhoneCaptureService.active)startService(new Intent(this,PhoneCaptureService.class).setAction("STOP"));});
        phoneStatus=text("Phone GPS ready",15);body.addView(phoneStatus);
        phonePosition=card(body,"Waiting for phone GPS",17);phoneAge=text("",13);body.addView(phoneAge);
        phoneFiles=card(body,"",12);phoneFiles.setTextIsSelectable(true);
        label(body,"LATEST TELEMETRY"); position=card(body,"Waiting for GPS readings",19);
        age=text("No readings yet",14); body.addView(age);
        label(body,"RECEPTION"); metrics=card(body,"",15);
        label(body,"AUTOMATIC SESSION LOG"); file=card(body,"",13); file.setTextIsSelectable(true);
        TextView note=text("Both sources save Google Earth KML plus raw JSONL automatically. Open both KML files to compare: phone is blue, LoRa is orange. Phone RSSI/SNR are unavailable; HDOP depends on phone support. Close other USB terminal apps before connecting.",13); note.setTextColor(0xff9bafc2); body.addView(note);
        label(body,"RECENT RECEIVER OUTPUT"); raw=card(body,"",12); raw.setTypeface(Typeface.MONOSPACE); raw.setTextIsSelectable(true);
        setContentView(scroll);
        IntentFilter filter=new IntentFilter(USB_PERMISSION); filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(usbReceiver,filter,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(usbReceiver,filter);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);
    }
    public void onResume() { super.onResume(); handler.post(redraw); if(wanted) connect(); }
    public void onPause() { handler.removeCallbacks(redraw); super.onPause(); }
    public void onDestroy() { unregisterReceiver(usbReceiver); handler.removeCallbacks(redraw); super.onDestroy(); }
    public void onSaveInstanceState(Bundle state) { state.putBoolean("wanted",wanted);state.putBoolean("phoneWanted",phoneWanted);state.putBoolean("phonePending",phonePermissionPending); super.onSaveInstanceState(state); }
    private void startPhone(){
        if(phonePermissionPending || PhoneCaptureService.active)return;
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            phonePermissionPending=true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},20);return;
        }
        startForegroundService(new Intent(this,PhoneCaptureService.class));
    }
    public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==20){
            phonePermissionPending=false;
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){if(phoneWanted)startPhone();}
            else{PhoneCaptureService.status="Allow precise location to record phone GPS";new AlertDialog.Builder(this).setMessage("Phone GPS logging needs precise location. Enable it in this app's permissions, then tap Start phone.").setPositiveButton("OK",null).show();}
        }
    }
    private void connect() {
        if(awaitingPermission) return;
        UsbManager manager=(UsbManager)getSystemService(USB_SERVICE);
        UsbDevice selected=null;
        for(UsbDevice device:manager.getDeviceList().values()) if(Cp2102.supports(device)) { selected=device; break; }
        if(selected==null) { localStatus="Plug in your CubeCell receiver using USB OTG"; return; }
        if(!manager.hasPermission(selected)) {
            localStatus="Allow USB access to start automatic logging"; awaitingPermission=true;
            Intent permission=new Intent(USB_PERMISSION).setPackage(getPackageName());
            PendingIntent pending=PendingIntent.getBroadcast(this,0,permission,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
            manager.requestPermission(selected,pending); return;
        }
        localStatus=null;
        startForegroundService(new Intent(this,CaptureService.class));
    }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private TextView text(String value,int size) { TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(Color.WHITE); t.setLineSpacing(dp(3),1); return t; }
    private void label(LinearLayout parent,String value) { TextView t=text(value,12); t.setTextColor(0xff8ce4cd); t.setPadding(0,dp(22),0,dp(8)); parent.addView(t); }
    private TextView card(LinearLayout parent,String value,int size) {
        TextView t=text(value,size); t.setPadding(dp(16),dp(14),dp(16),dp(14));
        GradientDrawable bg=new GradientDrawable(); bg.setColor(0xff172631); bg.setCornerRadius(dp(12)); t.setBackground(bg);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(12); p.bottomMargin=dp(10); parent.addView(t,p); return t;
    }
}
