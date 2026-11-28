package au.com.gildo.vtc;

import android.app.Activity;
import android.os.Bundle;
import android.net.ConnectivityManager;
import android.content.Intent;
import android.graphics.Color;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private MountPanel mountPanel;
    private CameraClient camera;
    private ImageView previewImage;
    private TextView previewStatus;
    private Button previewStart, previewStop;
    private boolean previewEnabled;
    private volatile int previewGeneration;
    private ScheduledFuture<?> nextFrame;
    private EditText address;
    private TextView connectionText, recordingText, zoomText, rangeText, log;
    private Button connect, start, stop, apply, refresh;
    private SeekBar slider;
    private boolean busy, hasZoom;
    private int minimum, maximum, step = 1, actual, selected;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(18), dp(22), dp(22));
        page.setBackgroundColor(Color.rgb(15,23,35));
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom; if (android.os.Build.VERSION.SDK_INT >= 30) { android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); left=bars.left; top=bars.top; right=bars.right; bottom=bars.bottom; } else { left=insets.getSystemWindowInsetLeft(); top=insets.getSystemWindowInsetTop(); right=insets.getSystemWindowInsetRight(); bottom=insets.getSystemWindowInsetBottom(); }
            v.setPadding(dp(22)+left, dp(18)+top, dp(22)+right, dp(22)+bottom);
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        setContentView(scroll);
        label(page,"VTC-Test",32,Color.WHITE);
        mountPanel = new MountPanel(this,page);
        label(page,"SX70 camera control (optional)",18,Color.rgb(72,214,192));
        label(page,"Use the same home Wi-Fi as the camera and GTi, or the camera's own Wi-Fi for camera-only tests.",15,Color.LTGRAY);
        Button wifi = button(page,"Open Wi-Fi settings");
        wifi.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        label(page,"Camera CCAPI address",15,Color.WHITE);
        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextColor(Color.WHITE);
        address.setTextSize(16);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(getPreferences(MODE_PRIVATE).getString("address","http://192.168.1.2:8080/ccapi"));
        address.setContentDescription("Camera CCAPI URL");
        page.addView(address);
        connect = button(page,"Connect to camera");
        connectionText = label(page,"Not connected",15,Color.LTGRAY);
        label(page,"Live preview",23,Color.WHITE);
        previewImage = new ImageView(this);
        previewImage.setBackgroundColor(Color.BLACK);
        previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewImage.setContentDescription("Live image from the SX70 camera");
        page.addView(previewImage,new LinearLayout.LayoutParams(-1,dp(210)));
        previewStatus = label(page,"Preview off",14,Color.LTGRAY);
        previewStart = button(page,"Start preview");
        previewStop = button(page,"Stop preview");
        previewStart.setOnClickListener(v -> beginPreview());
        previewStop.setOnClickListener(v -> endPreview("Preview off"));
        label(page,"Recording",23,Color.WHITE);
        recordingText = label(page,"Status unknown - check the camera display",15,Color.LTGRAY);
        start = button(page,"Start recording");
        stop = button(page,"Stop recording");
        label(page,"Optical zoom",23,Color.WHITE);
        zoomText = label(page,"Connect to read zoom",17,Color.WHITE);
        rangeText = label(page,"Camera position units, not magnification",14,Color.LTGRAY);
        slider = new SeekBar(this);
        slider.setContentDescription("Target camera zoom position");
        page.addView(slider,new LinearLayout.LayoutParams(-1,dp(52)));
        LinearLayout nudges = new LinearLayout(this);
        page.addView(nudges);
        Button less = new Button(this); less.setText("−");
        Button more = new Button(this); more.setText("+");
        nudges.addView(less,new LinearLayout.LayoutParams(0,dp(50),1));
        nudges.addView(more,new LinearLayout.LayoutParams(0,dp(50),1));
        less.setContentDescription("Reduce target zoom by one step");
        more.setContentDescription("Increase target zoom by one step");
        less.setOnClickListener(v -> { if (!busy && hasZoom) slider.setProgress(slider.getProgress()-1); });
        more.setOnClickListener(v -> { if (!busy && hasZoom) slider.setProgress(slider.getProgress()+1); });
        apply = button(page,"Apply zoom");
        refresh = button(page,"Read current zoom");
        label(page,"Activity",20,Color.WHITE);
        log = label(page,"Ready. No commands sent.",14,Color.LTGRAY);
        label(page,"VTC-Test 0.3.4 • GTi position tests + camera controls",12,Color.GRAY);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar,int value,boolean fromUser) {
                selected = Math.min(maximum, minimum + value * step);
                showZoom();
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        connect.setOnClickListener(v -> connectCamera());
        start.setOnClickListener(v -> execute("Starting recording", () -> {
            camera.startRecording();
            ui(() -> recordingText.setText("Start accepted - confirm REC on camera"));
        }));
        stop.setOnClickListener(v -> execute("Stopping recording", () -> {
            camera.stopRecording();
            ui(() -> recordingText.setText("Stop accepted"));
        }));
        refresh.setOnClickListener(v -> execute("Reading zoom", () -> readZoom(camera)));
        apply.setOnClickListener(v -> {
            final int target = selected;
            execute("Setting zoom to " + target, () -> {
                camera.setZoom(target);
                // Allow the physical lens to move, then request actual position.
                Thread.sleep(350);
                readZoom(camera);
            });
        });
        controls();
    }

    private void connectCamera() {
        if (busy) return;
        endPreview("Preview off");
        final String url = address.getText().toString();
        camera = null; hasZoom = false;
        recordingText.setText("Status unknown - check the camera display");
        connectionText.setText("Connecting…");
        execute("Connecting to camera", () -> {
            CameraClient candidate = new CameraClient((ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE),url);
            candidate.connect();
            JSONObject zoom = candidate.supports("zoom") ? candidate.zoom() : null;
            ui(() -> {
                camera = candidate;
                connectionText.setText("Connected over Wi-Fi • CCAPI responds");
                address.setText(candidate.base());
                getPreferences(MODE_PRIVATE).edit().putString("address",candidate.base()).apply();
                if (zoom != null) updateZoom(zoom);
                controls();
            });
        });
    }
    private void readZoom(CameraClient client) throws Exception {
        JSONObject result = client.zoom();
        ui(() -> updateZoom(result));
    }
    private void updateZoom(JSONObject data) {
        JSONObject ability = data.optJSONObject("ability");
        if (ability != null) {
            minimum = ability.optInt("min",0);
            maximum = ability.optInt("max",201);
            step = Math.max(1,ability.optInt("step",1));
        }
        if (!data.has("value") || maximum < minimum || maximum-minimum > 100000) {
            hasZoom = false; zoomText.setText("Invalid zoom response"); return;
        }
        actual = data.optInt("value",minimum);
        selected = Math.max(minimum,Math.min(maximum,actual));
        hasZoom = true;
        slider.setMax((maximum-minimum)/step);
        slider.setProgress((selected-minimum)/step);
        rangeText.setText("Range " + minimum + "–" + maximum + " • step " + step + " • position units");
        showZoom();
    }
    private void showZoom() {
        if (hasZoom) zoomText.setText("Current: " + actual + "     Target: " + selected);
    }
    private interface Job { void run() throws Exception; }
    private void execute(String description, Job job) {
        if (busy) return;
        busy = true; controls();
        log.setText(description + "…");
        worker.execute(() -> {
            try {
                job.run();
                ui(() -> log.setText(description + " — completed"));
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                ui(() -> {
                    log.setText(description + " failed: " + message + "\nNo automatic retry. Check camera before retrying.");
                    if (description.contains("recording")) recordingText.setText("Status unknown - check the camera display");
                    if (camera == null) connectionText.setText("Connection failed");
                });
            } finally {
                ui(() -> { busy = false; controls(); });
            }
        });
    }
    private void controls() {
        boolean connected = camera != null;
        connect.setEnabled(!busy); address.setEnabled(!busy);
        previewStart.setEnabled(!busy && connected && camera.supportsPreview() && !previewEnabled);
        previewStop.setEnabled(previewEnabled);
        start.setEnabled(!busy && connected && camera.canWrite("recbutton"));
        stop.setEnabled(!busy && connected && camera.canWrite("recbutton"));
        slider.setEnabled(!busy && hasZoom);
        apply.setEnabled(!busy && hasZoom && connected && camera.canWrite("zoom"));
        refresh.setEnabled(!busy && connected && camera.supports("zoom"));
    }
    private void beginPreview() {
        if (busy || camera == null || previewEnabled) return;
        final CameraClient client = camera;
        final int generation = ++previewGeneration;
        previewStatus.setText("Starting preview…");
        execute("Starting preview", () -> {
            try {
                client.startPreview();
                android.graphics.Bitmap frame = decodeFrame(client.previewFrame());
                ui(() -> {
                    if (generation != previewGeneration) return;
                    previewEnabled = true;
                    previewImage.setImageBitmap(frame);
                    previewStatus.setText("Live preview • updates up to 4 times/sec");
                    previewImage.setKeepScreenOn(true);
                    controls();
                    scheduleFrame(client,generation);
                });
            } catch (Exception error) {
                try { client.stopPreview(); } catch (Exception ignored) { }
                ui(() -> {
                    if (generation == previewGeneration) {
                        previewStatus.setText("Preview unavailable: " + error.getMessage());
                        previewImage.setImageDrawable(null);
                    }
                });
                throw error;
            }
        });
    }
    private android.graphics.Bitmap decodeFrame(byte[] bytes) throws java.io.IOException {
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if (options.outWidth <= 0 || options.outHeight <= 0
                || options.outWidth > 4096 || options.outHeight > 4096)
            throw new java.io.IOException("Invalid preview dimensions.");
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 1280) options.inSampleSize *= 2;
        android.graphics.Bitmap result = android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if (result == null) throw new java.io.IOException("Cannot decode camera preview.");
        return result;
    }
    private void scheduleFrame(CameraClient client, int generation) {
        if (!previewEnabled || generation != previewGeneration || worker.isShutdown()) return;
        nextFrame = worker.schedule(() -> {
            if (generation != previewGeneration) return;
            try {
                android.graphics.Bitmap frame = decodeFrame(client.previewFrame());
                ui(() -> {
                    if (generation != previewGeneration || !previewEnabled) return;
                    previewImage.setImageBitmap(frame);
                    scheduleFrame(client,generation);
                });
            } catch (Exception error) {
                ui(() -> {
                    if (generation == previewGeneration)
                        endPreview("Preview stopped: " + error.getMessage());
                });
            }
        },250,TimeUnit.MILLISECONDS);
    }
    private void endPreview(String message) {
        ++previewGeneration;
        previewEnabled = false;
        if (nextFrame != null) nextFrame.cancel(false);
        if (previewImage != null) {
            previewImage.setImageDrawable(null);
            previewImage.setKeepScreenOn(false);
            previewStatus.setText(message);
        }
        CameraClient client = camera;
        if (client != null && client.supportsPreview() && !worker.isShutdown()) {
            worker.execute(() -> {
                try { client.stopPreview(); }
                catch (Exception error) {
                    ui(() -> log.setText("Could not disable camera preview: " + error.getMessage()
                            + "\nCamera recording has not been stopped."));
                }
            });
        }
        if (previewStart != null) controls();
    }
    @Override protected void onPause() {
        if (mountPanel != null) mountPanel.pause();
        endPreview("Preview paused - tap Start preview to resume");
        super.onPause();
    }
    @Override protected void onResume() { super.onResume(); if (mountPanel != null) mountPanel.resume(); }
    private void ui(Runnable action) { runOnUiThread(() -> { if (!isDestroyed()) action.run(); }); }
    private TextView label(LinearLayout parent,String text,int size,int color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0,dp(10),0,dp(6)); parent.addView(view); return view;
    }
    private Button button(LinearLayout parent,String text) {
        Button view = new Button(this); view.setText(text); view.setAllCaps(false);
        parent.addView(view,new LinearLayout.LayoutParams(-1,dp(54))); return view;
    }
    private int dp(int value) { return (int)(value*getResources().getDisplayMetrics().density+0.5f); }
    @Override protected void onDestroy() { if (mountPanel != null) mountPanel.destroy(); worker.shutdown(); super.onDestroy(); }
}








