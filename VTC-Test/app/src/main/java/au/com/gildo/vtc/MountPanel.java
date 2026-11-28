package au.com.gildo.vtc;

import android.app.Activity;
import android.graphics.Color;
import android.net.*;
import android.widget.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Session-only saved positions: reconnecting invalidates them, as motor origins can change. */
final class MountPanel {
    private final Activity activity;
    private final DiagnosticLog diagnosticLog;
    private TextView logLocation;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final List<Button> actions=new ArrayList<>();
    private volatile GtiClient client;
    private EditText host,port;
    private TextView status,position,reference,history,diagnosticText,rateText;
    private final MotorSpeedMeter speedMeter=new MotorSpeedMeter();
    private Button connect,stop,saveA,saveB,gotoA,gotoB;
    private Spinner step, speed, gotoMode;
    private boolean busy,moving,foreground=true;
    private int generation;
    private GtiClient.Position a,b;
    private ScheduledFuture<?> polling;
    private final ArrayDeque<String> entries=new ArrayDeque<>();
    MountPanel(Activity activity,LinearLayout page) {
        this.activity=activity;
        diagnosticLog=new DiagnosticLog(activity);
        text(page,"GTi position tests",25);
        text(page,"Use home Wi-Fi: configure GTi Station mode in SynScan, then join that same Wi-Fi on this phone. Enter its assigned IP below. Close SynScan before using these controls.",14);
        host=new EditText(activity); host.setSingleLine(); host.setTextColor(Color.WHITE);
        host.setHint("GTi IP address on home Wi-Fi"); host.setHintTextColor(Color.GRAY);
        host.setText(activity.getPreferences(0).getString("gtiHost",""));
        host.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        page.addView(host);
        port=new EditText(activity); port.setSingleLine(); port.setTextColor(Color.WHITE);
        port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        port.setText(activity.getPreferences(0).getString("gtiPort","11880"));
        page.addView(port);
        connect=button(page,"Connect GTi",false);
        connect.setOnClickListener(v -> connect());
        status=text(page,"GTi disconnected • camera connection is optional",15);
        position=text(page,"Motor position: —",16);
        rateText=text(page,"Sample-average motor rate: —",15);
        diagnosticText=text(page,"No movement requested",14);
        reference=text(page,"Saved A: —\nSaved B: —",14);
        text(page,"Native GoTo mode • applies to Pan/Tilt and Go to A/B",16);
        gotoMode=new Spinner(activity);
        gotoMode.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Slow native GoTo","Fast native GoTo"}));
        gotoMode.setSelection(activity.getPreferences(0).getBoolean("gtiFastGoto",false)?1:0);
        page.addView(gotoMode);
        text(page,"The GTi handles the destination and stops itself. Fast/Slow are firmware modes, not exact degrees/sec.",14);
        text(page,"Step distance (separate from speed)",16);
        step=new Spinner(activity);
        step.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,new String[]{"0.1° step","0.5° step","1° step","5° step"}));
        step.setSelection(2); page.addView(step);
        LinearLayout pan=new LinearLayout(activity); page.addView(pan);
        Button panMinus=button(pan,"Pan −",true),panPlus=button(pan,"Pan +",true);
        panMinus.setOnClickListener(v -> nudge(1,-1)); panPlus.setOnClickListener(v -> nudge(1,1));
        LinearLayout tilt=new LinearLayout(activity); page.addView(tilt);
        Button tiltMinus=button(tilt,"Tilt −",true),tiltPlus=button(tilt,"Tilt +",true);
        tiltMinus.setOnClickListener(v -> nudge(2,-1)); tiltPlus.setOnClickListener(v -> nudge(2,1));
        LinearLayout rowA=new LinearLayout(activity); page.addView(rowA);
        saveA=button(rowA,"Save A",true); gotoA=button(rowA,"Go to A",true);
        LinearLayout rowB=new LinearLayout(activity); page.addView(rowB);
        saveB=button(rowB,"Save B",true); gotoB=button(rowB,"Go to B",true);
        saveA.setOnClickListener(v -> save(true)); saveB.setOnClickListener(v -> save(false));
        gotoA.setOnClickListener(v -> go(true)); gotoB.setOnClickListener(v -> go(false));
        text(page,"Move at selected speed • separate test",20);
        speed=new Spinner(activity);
        speed.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,
                new String[]{"0.05°/s","0.1°/s","0.25°/s","0.5°/s • VTC default","1°/s"}));
        speed.setSelection(Math.max(0,Math.min(4,activity.getPreferences(0).getInt("gtiSpeedIndex",3))));
        page.addView(speed);
        text(page,"Moves the selected step distance at the requested rate, slowing near the target. Here the APP sends Stop. Wi-Fi loss can leave the mount moving; keep its power switch accessible.",14);
        LinearLayout speedPan=new LinearLayout(activity); page.addView(speedPan);
        Button spMinus=button(speedPan,"Speed test Pan −",true),spPlus=button(speedPan,"Speed test Pan +",true);
        spMinus.setOnClickListener(v -> speedTest(1,-1)); spPlus.setOnClickListener(v -> speedTest(1,1));
        LinearLayout speedTilt=new LinearLayout(activity); page.addView(speedTilt);
        Button stMinus=button(speedTilt,"Speed test Tilt −",true),stPlus=button(speedTilt,"Speed test Tilt +",true);
        stMinus.setOnClickListener(v -> speedTest(2,-1)); stPlus.setOnClickListener(v -> speedTest(2,1));
        stop=button(page,"STOP BOTH AXES",false); stop.setTextColor(Color.rgb(255,100,100));
        stop.setOnClickListener(v -> stop());
        Button refresh=button(page,"Read motor position",true);
        refresh.setOnClickListener(v -> run("Read position",() -> { GtiClient.Position here=client.position(); ui(() -> update(here)); }));
        text(page,"Aim at a landmark → Save A → move away → Go to A. Compare landmark placement. Counter errors below do not measure physical backlash. Positions are valid only for this connection; do not release clutches or change alignment. Return moves are limited to 10° per axis.",14);
        Button exportLog=button(page,"Save diagnostic log to Downloads/CAM3",false);
        logLocation=text(page,"Diagnostics capture automatically. Save after testing to share the file.",13);
        exportLog.setOnClickListener(v -> {
            if(android.os.Build.VERSION.SDK_INT<29 && activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},719);
                logLocation.setText("Allow storage, then tap Save diagnostic log again.");return;
            }
            exportLog.setEnabled(false);logLocation.setText("Saving diagnostic log…");
            worker.execute(() -> {
                try { String path=diagnosticLog.publish();ui(() -> logLocation.setText("Saved: "+path)); }
                catch(Exception error) { ui(() -> logLocation.setText("Log save failed: "+error.getMessage())); }
                finally { ui(() -> exportLog.setEnabled(true)); }
            });
        });
        history=text(page,"GTi activity",13);
        controls();
    }
    private void connect() {
        if(busy || moving) return;
        final String ip=host.getText().toString().trim(),p=port.getText().toString().trim();
        if(ip.isEmpty()) { note("Enter the GTi's home-network IP from SynScan/router."); return; }
        speedMeter.reset();
        ++generation;
        if(polling!=null) polling.cancel(false);
        GtiClient old=client; client=null; a=null;b=null; reference.setText("Saved A: —\nSaved B: —");
        if(old!=null) old.close();
        run("Connect GTi",() -> {
            ConnectivityManager cm=(ConnectivityManager)activity.getSystemService(Activity.CONNECTIVITY_SERVICE);
            Network wifi=null;
            for(Network n:cm.getAllNetworks()) {
                NetworkCapabilities caps=cm.getNetworkCapabilities(n);
                if(caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { wifi=n; break; }
            }
            if(wifi==null) throw new IOException("Connect phone to home Wi-Fi first");
            final GtiClient candidate=new GtiClient(new GtiUdpTransport(wifi,ip,Integer.parseInt(p),diagnosticLog));
            try { candidate.connect(); GtiClient.Position here=candidate.position();
                ui(() -> {
                    client=candidate;
                    activity.getPreferences(0).edit().putString("gtiHost",ip).putString("gtiPort",p).apply();
                    status.setText("GTi connected • "+ip+":"+p+"\nResolution: pan "+fmt(1296000.0/candidate.cpr[0])+"″ / tilt "+fmt(1296000.0/candidate.cpr[1])+"″ per count");
                    update(here); controls(); schedulePoll(generation);
                });
            } catch(Exception error) { candidate.close(); throw error; }
        });
    }
    private double rate() { int index=speed.getSelectedItemPosition(); activity.getPreferences(0).edit().putInt("gtiSpeedIndex",index).apply(); return new double[]{0.05,0.1,0.25,0.5,1}[index]; }
    private double amount() { return new double[]{0.1,0.5,1,5}[step.getSelectedItemPosition()]; }
    private boolean fastGoto() { boolean fast=gotoMode.getSelectedItemPosition()==1; activity.getPreferences(0).edit().putBoolean("gtiFastGoto",fast).apply(); return fast; }
    private void speedTest(int axis,int sign) {
        final double degrees=sign*amount();
        final double selectedRate=rate();
        client.beginOperation();
        run((axis==1?"Pan ":"Tilt ")+degrees+"° • speed test "+selectedRate+"°/s",() -> {
            client.moveByAtSpeed(axis,degrees,selectedRate,here -> ui(() -> update(here)));
            GtiClient.Position here=client.position(); ui(() -> update(here));
        });
    }
    private void nudge(int axis,int sign) {
        final double degrees=sign*amount();
        final boolean fast=fastGoto();
        client.beginOperation();
        run((axis==1?"Pan ":"Tilt ")+degrees+"° • native "+(fast?"Fast":"Slow"),() -> {
            client.moveBy(axis,degrees,fast);
            GtiClient.Position here=client.position();ui(() -> update(here));
        });
    }
    private void save(boolean first) {
        run("Save "+(first?"A":"B"),() -> {
            GtiClient.Position here=client.position();
            if(here.moving) throw new IOException("Wait for mount to stop before saving");
            ui(() -> {
                if(first) a=here; else b=here;
                reference.setText("Saved A: "+(a==null?"—":a.pan+", "+a.tilt)+"\nSaved B: "+(b==null?"—":b.pan+", "+b.tilt));
                update(here);
            });
        });
    }
    private void go(boolean first) {
        GtiClient.Position target=first?a:b;
        if(target==null) return;
        final boolean fast=fastGoto();
        client.beginOperation();
        run("Go to "+(first?"A":"B")+" • native "+(fast?"Fast":"Slow"),() -> {
            client.goToSaved(target,fast);
            GtiClient.Position here=client.position(); ui(() -> update(here));
        });
    }
    private interface Job { void run() throws Exception; }
    private void run(String label,Job job) {
        if(busy) return;
        busy=true; controls(); note(label+"…");
        worker.execute(() -> {
            try { job.run(); ui(() -> note(label+" acknowledged")); }
            catch(Exception error) { ui(() -> note(label+" failed: "+error.getMessage())); }
            finally { ui(() -> { busy=false; controls(); }); }
        });
    }
    private void stop() {
        GtiClient current=client;
        if(current==null) return;
        current.cancel(); // checked before every motion-start command, even if a command is in flight
        note("Stop requested…");
        worker.execute(() -> {
            try { current.stop(); GtiClient.Position here=current.position(); ui(() -> { update(here);note("STOP acknowledged");controls(); }); }
            catch(Exception error) { ui(() -> note("STOP not confirmed: "+error.getMessage()+". Use mount power switch if needed.")); }
        });
    }
    private void update(GtiClient.Position here) {
        if(client==null) return;
        diagnosticLog.event("POSITION","pan="+here.pan+" tilt="+here.tilt+" moving="+here.moving+" cpr_pan="+client.cpr[0]+" cpr_tilt="+client.cpr[1]);
        moving=here.moving;
        rateText.setText(speedMeter.sample(here,client.cpr,System.nanoTime()));
        diagnosticText.setText(client.diagnostics+"\n"+client.axisStatus);
        diagnosticLog.event("MOTION_DIAGNOSTICS",client.diagnostics+"; "+client.axisStatus+"; "+rateText.getText());
        if (!diagnosticLog.error().isEmpty()) logLocation.setText("Log capture error: "+diagnosticLog.error());
        String value="Motor counts: pan "+here.pan+" • tilt "+here.tilt+"\n"+(moving?"MOVING":"STOPPED");
        if(a!=null) value+="\nFrom A: pan "+fmt(client.degreesFrom(here.pan,a.pan,1))+"° • tilt "+fmt(client.degreesFrom(here.tilt,a.tilt,2))+"°";
        if(b!=null) value+="\nFrom B: pan "+fmt(client.degreesFrom(here.pan,b.pan,1))+"° • tilt "+fmt(client.degreesFrom(here.tilt,b.tilt,2))+"°";
        position.setText(value); controls();
    }
    private void schedulePoll(int token) {
        if(!foreground || client==null || token!=generation || worker.isShutdown()) return;
        polling=worker.schedule(() -> {
            if(token!=generation) return;
            GtiClient current=client;
            if(current==null) return;
            try { GtiClient.Position here=current.position(); ui(() -> { if(token==generation) { update(here); schedulePoll(token); } }); }
            catch(Exception error) {
                current.cancel();
                try { current.stop(); } catch(Exception ignored) { }
                ui(() -> {
                    if(token!=generation) return;
                    status.setText("GTi connection lost; stop attempted, not guaranteed. Reconnect before moving.");
                    note("Position read failed: "+error.getMessage());
                    current.close();client=null;moving=false;a=null;b=null; reference.setText("Saved A: —\nSaved B: —"); position.setText("Position unavailable"); controls();
                });
            }
        },1000,TimeUnit.MILLISECONDS);
    }
    void pause() {
        foreground=false; ++generation;
        if(polling!=null) polling.cancel(false);
        stop();
    }
    void resume() { foreground=true; if(client!=null) schedulePoll(generation); }
    void destroy() { pause(); worker.execute(diagnosticLog::close); worker.shutdown(); }
    private void controls() {
        boolean ready=client!=null && !busy && !moving;
        gotoMode.setEnabled(!busy && !moving);
        speed.setEnabled(!busy && !moving); step.setEnabled(!busy && !moving);
        connect.setEnabled(!busy && !moving);
        host.setEnabled(!busy && !moving);port.setEnabled(!busy && !moving);
        for(Button button:actions) button.setEnabled(ready);
        gotoA.setEnabled(ready && a!=null);gotoB.setEnabled(ready && b!=null);
        stop.setEnabled(client!=null);
    }
    private void note(String message) {
        diagnosticLog.event("ACTION",message);
        entries.addFirst(new java.text.SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date())+" "+message);
        while(entries.size()>12) entries.removeLast();
        history.setText(String.join("\n",entries));
    }
    private TextView text(LinearLayout parent,String text,int size) {
        TextView view=new TextView(activity);view.setText(text);view.setTextColor(Color.WHITE);view.setTextSize(size);
        view.setPadding(0,12,0,10);parent.addView(view);return view;
    }
    private Button button(LinearLayout parent,String label,boolean action) {
        Button button=new Button(activity);button.setText(label);button.setAllCaps(false);
        if(parent.getOrientation()==LinearLayout.HORIZONTAL) parent.addView(button,new LinearLayout.LayoutParams(0,dp(52),1));
        else parent.addView(button,new LinearLayout.LayoutParams(-1,dp(52)));
        if(action) actions.add(button);return button;
    }
    private int dp(int value) { return (int)(value*activity.getResources().getDisplayMetrics().density+0.5f); }
    private String fmt(double n) { return String.format(Locale.US,"%.5f",n); }
    private void ui(Runnable action) { activity.runOnUiThread(() -> { if(!activity.isDestroyed()) action.run(); }); }
}







