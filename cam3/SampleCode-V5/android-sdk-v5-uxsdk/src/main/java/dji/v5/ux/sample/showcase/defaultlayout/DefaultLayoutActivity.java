/*
 * Copyright (c) 2018-2020 DJI
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package dji.v5.ux.sample.showcase.defaultlayout;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

// CAM3 v2.0: Aiming owns its lifecycle; inset handling protects all flight/camera controls.
import android.view.ViewGroup;
// CAM3 v2.1: Export through Android's save picker without storage permissions or a flight-control action.
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;
import dji.v5.ux.sample.showcase.defaultlayout.aiming.AimingSession;
import dji.v5.ux.sample.showcase.defaultlayout.aiming.YawAimingController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import dji.sdk.keyvalue.value.common.CameraLensType;
import dji.sdk.keyvalue.value.common.ComponentIndexType;
import dji.v5.manager.datacenter.MediaDataCenter;
import dji.v5.manager.interfaces.ICameraStreamManager;
import dji.v5.network.DJINetworkManager;
import dji.v5.network.IDJINetworkStatusListener;
import dji.v5.utils.common.JsonUtil;
import dji.v5.utils.common.LogPath;
import dji.v5.utils.common.LogUtils;
import dji.v5.ux.R;
import dji.v5.ux.accessory.RTKStartServiceHelper;
import dji.v5.ux.cameracore.widget.autoexposurelock.AutoExposureLockWidget;
import dji.v5.ux.cameracore.widget.cameracontrols.CameraControlsWidget;
import dji.v5.ux.cameracore.widget.cameracontrols.lenscontrol.LensControlWidget;
import dji.v5.ux.cameracore.widget.focusexposureswitch.FocusExposureSwitchWidget;
import dji.v5.ux.cameracore.widget.focusmode.FocusModeWidget;
import dji.v5.ux.cameracore.widget.fpvinteraction.FPVInteractionWidget;
import dji.v5.ux.core.base.SchedulerProvider;
import dji.v5.ux.core.communication.BroadcastValues;
import dji.v5.ux.core.communication.GlobalPreferenceKeys;
import dji.v5.ux.core.communication.ObservableInMemoryKeyedStore;
import dji.v5.ux.core.communication.UXKeys;
import dji.v5.ux.core.extension.ViewExtensions;
import dji.v5.ux.core.panel.systemstatus.SystemStatusListPanelWidget;
import dji.v5.ux.core.panel.topbar.TopBarPanelWidget;
import dji.v5.ux.core.util.CameraUtil;
import dji.v5.ux.core.util.DataProcessor;
import dji.v5.ux.core.util.ViewUtil;
import dji.v5.ux.core.widget.fpv.FPVWidget;
import dji.v5.ux.core.widget.hsi.HorizontalSituationIndicatorWidget;
import dji.v5.ux.core.widget.hsi.PrimaryFlightDisplayWidget;
import dji.v5.ux.core.widget.setting.SettingWidget;
import dji.v5.ux.core.widget.simulator.SimulatorIndicatorWidget;
import dji.v5.ux.core.widget.systemstatus.SystemStatusWidget;
import dji.v5.ux.gimbal.GimbalFineTuneWidget;
import dji.v5.ux.map.MapWidget;
import dji.v5.ux.mapkit.core.maps.DJIUiSettings;
import dji.v5.ux.training.simulatorcontrol.SimulatorControlWidget;
import dji.v5.ux.visualcamera.CameraNDVIPanelWidget;
import dji.v5.ux.visualcamera.CameraVisiblePanelWidget;
import dji.v5.ux.visualcamera.zoom.FocalZoomWidget;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

/**
 * Displays a sample layout of widgets similar to that of the various DJI apps.
 */
public class DefaultLayoutActivity extends AppCompatActivity {

    //region Fields
    private final String TAG = LogUtils.getTag(this);

    // CAM3 v2.0: Process-scoped control survives late SDK callbacks without retaining this Activity.
    private YawAimingController yawAimingController;
    // CAM3 v2.4: Track export availability independently of the overflow menu view.
    private boolean aimingExportPending;
    // CAM3 v2.0: Stable observer identity prevents an old screen detaching a newer screen's controls.
    private final YawAimingController.Observer aimingObserver = this::renderAimingState;
    // CAM3 v2.1: The picker grants access only to the document selected by the user.
    private final ActivityResultLauncher<String> aimingLogExport = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
                if (uri == null) { aimingExportPending = false; return; }
                yawAimingController.exportLog(uri, success -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    aimingExportPending = false;
                    Toast.makeText(this, success ? R.string.uxsdk_aiming_export_done
                            : R.string.uxsdk_aiming_export_failed, Toast.LENGTH_LONG).show();
                }));
            });

    protected FPVWidget primaryFpvWidget;
    protected FPVInteractionWidget fpvInteractionWidget;
    protected FPVWidget secondaryFPVWidget;
    protected SystemStatusListPanelWidget systemStatusListPanelWidget;
    protected SimulatorControlWidget simulatorControlWidget;
    protected LensControlWidget lensControlWidget;
    protected AutoExposureLockWidget autoExposureLockWidget;
    protected FocusModeWidget focusModeWidget;
    protected FocusExposureSwitchWidget focusExposureSwitchWidget;
    protected CameraControlsWidget cameraControlsWidget;
    protected HorizontalSituationIndicatorWidget horizontalSituationIndicatorWidget;
    protected PrimaryFlightDisplayWidget pfvFlightDisplayWidget;
    protected CameraNDVIPanelWidget ndviCameraPanel;
    protected CameraVisiblePanelWidget visualCameraPanel;
    protected FocalZoomWidget focalZoomWidget;
    protected SettingWidget settingWidget;
    protected MapWidget mapWidget;
    protected TopBarPanelWidget topBarPanel;
    protected ConstraintLayout fpvParentView;
    private DrawerLayout mDrawerLayout;
    private TextView gimbalAdjustDone;
    private GimbalFineTuneWidget gimbalFineTuneWidget;
    private ComponentIndexType lastDevicePosition = ComponentIndexType.UNKNOWN;
    private CameraLensType lastLensType = CameraLensType.UNKNOWN;


    private CompositeDisposable compositeDisposable;
    private final DataProcessor<CameraSource> cameraSourceProcessor = DataProcessor.create(new CameraSource(ComponentIndexType.UNKNOWN,
            CameraLensType.UNKNOWN));
    private final IDJINetworkStatusListener networkStatusListener = isNetworkAvailable -> {
        if (isNetworkAvailable) {
            LogUtils.d(TAG, "isNetworkAvailable=" + true);
            RTKStartServiceHelper.INSTANCE.startRtkService(false);
        }
    };
    private final ICameraStreamManager.AvailableCameraUpdatedListener availableCameraUpdatedListener = new ICameraStreamManager.AvailableCameraUpdatedListener() {
        @Override
        public void onAvailableCameraUpdated(@NonNull List<ComponentIndexType> availableCameraList) {
            runOnUiThread(() -> updateFPVWidgetSource(availableCameraList));
        }

        @Override
        public void onCameraStreamEnableUpdate(@NonNull Map<ComponentIndexType, Boolean> cameraStreamEnableMap) {
            //
        }
    };

    //endregion

    //region Lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.uxsdk_activity_default_layout);
        fpvParentView = findViewById(R.id.fpv_holder);
        mDrawerLayout = findViewById(R.id.root_view);
        topBarPanel = findViewById(R.id.panel_top_bar);
        settingWidget = topBarPanel.getSettingWidget();
        primaryFpvWidget = findViewById(R.id.widget_primary_fpv);
        fpvInteractionWidget = findViewById(R.id.widget_fpv_interaction);
        secondaryFPVWidget = findViewById(R.id.widget_secondary_fpv);
        systemStatusListPanelWidget = findViewById(R.id.widget_panel_system_status_list);
        simulatorControlWidget = findViewById(R.id.widget_simulator_control);
        lensControlWidget = findViewById(R.id.widget_lens_control);
        ndviCameraPanel = findViewById(R.id.panel_ndvi_camera);
        visualCameraPanel = findViewById(R.id.panel_visual_camera);
        autoExposureLockWidget = findViewById(R.id.widget_auto_exposure_lock);
        focusModeWidget = findViewById(R.id.widget_focus_mode);
        focusExposureSwitchWidget = findViewById(R.id.widget_focus_exposure_switch);
        pfvFlightDisplayWidget = findViewById(R.id.widget_fpv_flight_display_widget);
        focalZoomWidget = findViewById(R.id.widget_focal_zoom);
        cameraControlsWidget = findViewById(R.id.widget_camera_controls);
        horizontalSituationIndicatorWidget = findViewById(R.id.widget_horizontal_situation_indicator);
        gimbalAdjustDone = findViewById(R.id.fpv_gimbal_ok_btn);
        gimbalFineTuneWidget = findViewById(R.id.setting_menu_gimbal_fine_tune);
        mapWidget = findViewById(R.id.widget_map);

        // CAM3 v2.0: Create the yaw controller before wiring explicit Start/Stop actions.
        yawAimingController = YawAimingController.getInstance(getApplicationContext());
        applySystemBarInsets();
        initClickListener();
        MediaDataCenter.getInstance().getCameraStreamManager().addAvailableCameraUpdatedListener(availableCameraUpdatedListener);
        primaryFpvWidget.setOnFPVStreamSourceListener((devicePosition, lensType) -> cameraSourceProcessor.onNext(new CameraSource(devicePosition, lensType)));

        //小surfaceView放置在顶部，避免被大的遮挡
        secondaryFPVWidget.setSurfaceViewZOrderOnTop(true);
        secondaryFPVWidget.setSurfaceViewZOrderMediaOverlay(true);


        mapWidget.initMapLibreMap(getApplicationContext(), map -> {
            DJIUiSettings uiSetting = map.getUiSettings();
            if (uiSetting != null) {
                uiSetting.setZoomControlsEnabled(false);//hide zoom widget
            }
        });
        mapWidget.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        //实现RTK监测网络，并自动重连机制
        DJINetworkManager.getInstance().addNetworkStatusListener(networkStatusListener);

    }

    private void isGimableAdjustClicked(BroadcastValues broadcastValues) {
        if (mDrawerLayout.isDrawerOpen(GravityCompat.END)) {
            mDrawerLayout.closeDrawers();
        }
        horizontalSituationIndicatorWidget.setVisibility(View.GONE);
        if (gimbalFineTuneWidget != null) {
            gimbalFineTuneWidget.setVisibility(View.VISIBLE);
        }
    }

    private void initClickListener() {
        findViewById(R.id.uxsdk_aiming_source).setOnClickListener(v -> {
            if (!yawAimingController.canSelectGpsSource()) return;
            new android.app.AlertDialog.Builder(this).setTitle("Target GPS source")
                    .setSingleChoiceItems(new String[]{"LoRa Wi-Fi (GDL_LORA)", "Phone GPS"},
                            yawAimingController.usesPhoneGps() ? 1 : 0, (dialog, which) -> {
                                yawAimingController.selectPhoneGps(which == 1); dialog.dismiss();
                            })
                    .setNeutralButton("Wi-Fi settings", (dialog, which) ->
                            startActivity(new android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)))
                    .setNegativeButton(android.R.string.cancel, null).show();
        });
        // CAM3 v2.0: No automatic activation; Stop also cancels pending authority requests.
        findViewById(R.id.uxsdk_aiming_start).setOnClickListener(v -> yawAimingController.startAiming());
        // CAM3 v2.2: STOP is always accessible and every tap gets honest visible acknowledgement.
        findViewById(R.id.uxsdk_aiming_stop).setOnClickListener(v -> yawAimingController.stopFromUser(state -> {
            if (isFinishing() || isDestroyed()) return;
            int message = state == AimingSession.State.STOPPED || state == AimingSession.State.OFF
                    ? R.string.uxsdk_aiming_stop_idle : R.string.uxsdk_aiming_stop_pending;
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }));
        // CAM3 v2.4: Move secondary actions into overflow, preserving direct Start/Stop access.
        findViewById(R.id.uxsdk_aiming_more).setOnClickListener(v -> {
            android.widget.PopupMenu menu = new android.widget.PopupMenu(this, v);
            menu.getMenu().add(0, 1, 0, R.string.uxsdk_aiming_details);
            menu.getMenu().add(0, 2, 1, R.string.uxsdk_aiming_export).setEnabled(!aimingExportPending);
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    new android.app.AlertDialog.Builder(this).setTitle(R.string.uxsdk_aiming_details)
                            .setMessage(yawAimingController.readinessDetails())
                            .setPositiveButton(android.R.string.ok, null).show();
                } else if (item.getItemId() == 2 && !aimingExportPending) {
                    aimingExportPending = true;
                    // Existing screen-pause cancellation still applies to the document picker.
                    try { aimingLogExport.launch("cam3-v2.4-aiming-" + System.currentTimeMillis() + ".txt"); }
                    catch (RuntimeException ex) {
                        aimingExportPending = false;
                        Toast.makeText(this, R.string.uxsdk_aiming_export_failed, Toast.LENGTH_LONG).show();
                    }
                }
                return true;
            });
            menu.show();
        });
        secondaryFPVWidget.setOnClickListener(v -> swapVideoSource());

        if (settingWidget != null) {
            settingWidget.setOnClickListener(v -> toggleRightDrawer());
        }

        // Setup top bar state callbacks
        SystemStatusWidget systemStatusWidget = topBarPanel.getSystemStatusWidget();
        if (systemStatusWidget != null) {
            systemStatusWidget.setOnClickListener(v -> ViewExtensions.toggleVisibility(systemStatusListPanelWidget));
        }

        SimulatorIndicatorWidget simulatorIndicatorWidget = topBarPanel.getSimulatorIndicatorWidget();
        if (simulatorIndicatorWidget != null) {
            simulatorIndicatorWidget.setOnClickListener(v -> ViewExtensions.toggleVisibility(simulatorControlWidget));
        }
        gimbalAdjustDone.setOnClickListener(view -> {
            // CAM3 v2.4: Keep the compass overlay hidden after closing gimbal adjustment.
            horizontalSituationIndicatorWidget.setVisibility(View.GONE);
            if (gimbalFineTuneWidget != null) {
                gimbalFineTuneWidget.setVisibility(View.GONE);
            }

        });
    }

    private void toggleRightDrawer() {
        mDrawerLayout.openDrawer(GravityCompat.END);
    }


    @Override
    protected void onDestroy() {
        // CAM3 v2.0: Idempotent detach; unresolved grants remain managed without holding this screen.
        yawAimingController.pause(aimingObserver);
        super.onDestroy();
        mapWidget.onDestroy();
        MediaDataCenter.getInstance().getCameraStreamManager().removeAvailableCameraUpdatedListener(availableCameraUpdatedListener);
        DJINetworkManager.getInstance().removeNetworkStatusListener(networkStatusListener);

    }

    @Override
    protected void onResume() {
        super.onResume();
        // CAM3 v2.0: Resume observation only; aiming always requires a new Start action.
        yawAimingController.resume(aimingObserver);
        mapWidget.onResume();
        compositeDisposable = new CompositeDisposable();
        compositeDisposable.add(systemStatusListPanelWidget.closeButtonPressed()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(pressed -> {
                    if (pressed) {
                        ViewExtensions.hide(systemStatusListPanelWidget);
                    }
                }));
        compositeDisposable.add(simulatorControlWidget.getUIStateUpdates()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(simulatorControlWidgetState -> {
                    if (simulatorControlWidgetState instanceof SimulatorControlWidget.UIState.VisibilityUpdated) {
                        if (((SimulatorControlWidget.UIState.VisibilityUpdated) simulatorControlWidgetState).isVisible()) {
                            hideOtherPanels(simulatorControlWidget);
                        }
                    }
                }));
        compositeDisposable.add(cameraSourceProcessor.toFlowable()
                .observeOn(SchedulerProvider.io())
                .throttleLast(500, TimeUnit.MILLISECONDS)
                .subscribeOn(SchedulerProvider.io())
                .subscribe(result -> runOnUiThread(() -> onCameraSourceUpdated(result.devicePosition, result.lensType)))
        );
        compositeDisposable.add(ObservableInMemoryKeyedStore.getInstance()
                .addObserver(UXKeys.create(GlobalPreferenceKeys.GIMBAL_ADJUST_CLICKED))
                .observeOn(SchedulerProvider.ui())
                .subscribe(this::isGimableAdjustClicked));
        ViewUtil.setKeepScreen(this, true);
    }

    @Override
    protected void onPause() {
        // CAM3 v2.0: Latch cancellation before normal screen cleanup can delay flight shutdown.
        yawAimingController.pause(aimingObserver);
        if (compositeDisposable != null) {
            compositeDisposable.dispose();
            compositeDisposable = null;
        }
        mapWidget.onPause();
        super.onPause();
        ViewUtil.setKeepScreen(this, false);
    }
    //endregion

    // CAM3 v2.0: Reserve system-bar/cutout space, including hidden navigation bars in landscape.
    private void applySystemBarInsets() {
        View root = findViewById(R.id.root_view);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets safe = windowInsets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            // CAM3 v2.0: DrawerLayout measures content by margins, so padding alone is insufficient.
            for (int id : new int[] {R.id.uxsdk_safe_content, R.id.manual_right_nav_setting}) {
                View content = findViewById(id);
                ViewGroup.MarginLayoutParams bounds = (ViewGroup.MarginLayoutParams) content.getLayoutParams();
                bounds.setMargins(safe.left, safe.top, safe.right, safe.bottom);
                content.setLayoutParams(bounds);
            }
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
    }

    // CAM3 v2.0: Report requested control state honestly; release confirmation is not physical stopping.
    private void renderAimingState(AimingSession.State state, String reason, boolean ready,
                                   AimingSession.Fix fix, long now) {
        // CAM3 v2.4: Disabled icon remains visibly distinct without a large text button.
        findViewById(R.id.uxsdk_aiming_start).setEnabled(ready);
        findViewById(R.id.uxsdk_aiming_start).setAlpha(ready ? 1f : 0.35f);
        android.widget.Button source = findViewById(R.id.uxsdk_aiming_source);
        source.setText(yawAimingController.usesPhoneGps() ? "GPS: Phone ▾" : "GPS: LoRa ▾");
        source.setEnabled(yawAimingController.canSelectGpsSource());
        int stateLabel;
        switch (state) {
            case STARTING: stateLabel = R.string.uxsdk_aiming_starting; break;
            case AIMING: stateLabel = R.string.uxsdk_aiming_active; break;
            // CAM3 v2.3: Distinguish a recoverable pause from a permanent stop.
            case PAUSED: stateLabel = R.string.uxsdk_aiming_paused; break;
            case STOPPING: stateLabel = R.string.uxsdk_aiming_stopping; break;
            case RELEASE_UNCONFIRMED: stateLabel = R.string.uxsdk_aiming_unconfirmed; break;
            case STOPPED: stateLabel = R.string.uxsdk_aiming_stopped; break;
            default: stateLabel = R.string.uxsdk_aiming_off;
        }
        int reasonId = getResources().getIdentifier("uxsdk_aiming_reason_" + reason, "string", getPackageName());
        String explanation = getString(reasonId == 0 ? R.string.uxsdk_aiming_reason_sdk_error : reasonId);
        // CAM3 v2.4: Short footer wording; Details retains the complete blocker snapshot.
        if ("telemetry".equals(reason)) explanation = "Aircraft telemetry unavailable";
        if ("gps".equals(reason)) explanation = "Waiting for GPS";
        // CAM3 v2.3: Show actual recovery countdown and the shared distance threshold.
        if ("recovering".equals(reason)) explanation = getString(R.string.uxsdk_aiming_recovery_countdown,
                yawAimingController.recoveryRemainingMs() / 1000.0);
        if ("distance".equals(reason)) explanation = getString(R.string.uxsdk_aiming_distance_limit,
                dji.v5.ux.sample.showcase.defaultlayout.aiming.YawAimingMath.MIN_AIMING_DISTANCE_METERS);
        // CAM3 v2.4: One-line footer prioritizes state/reason; Details retains untruncated telemetry.
        ((TextView) findViewById(R.id.uxsdk_aiming_status)).setText(getString(stateLabel) + " — " + explanation
                + " · " + yawAimingController.targetSummary(fix, now).replace("\n", " · "));
    }

    private void hideOtherPanels(@Nullable View widget) {
        View[] panels = {
                simulatorControlWidget
        };

        for (View panel : panels) {
            if (widget != panel) {
                panel.setVisibility(View.GONE);
            }
        }
    }

    private void updateFPVWidgetSource(List<ComponentIndexType> availableCameraList) {
        LogUtils.i(TAG, JsonUtil.toJson(availableCameraList));
        if (availableCameraList == null) {
            return;
        }

        ArrayList<ComponentIndexType> cameraList = new ArrayList<>(availableCameraList);

        //没有数据
        if (cameraList.isEmpty()) {
            secondaryFPVWidget.setVisibility(View.GONE);
            return;
        }

        //仅一路数据
        if (cameraList.size() == 1) {
            primaryFpvWidget.updateVideoSource(availableCameraList.get(0));
            secondaryFPVWidget.setVisibility(View.GONE);
            return;
        }

        //大于两路数据
        ComponentIndexType primarySource = getSuitableSource(cameraList, ComponentIndexType.LEFT_OR_MAIN);
        primaryFpvWidget.updateVideoSource(primarySource);
        cameraList.remove(primarySource);

        ComponentIndexType secondarySource = getSuitableSource(cameraList, ComponentIndexType.FPV);
        secondaryFPVWidget.updateVideoSource(secondarySource);

        secondaryFPVWidget.setVisibility(View.VISIBLE);
    }

    private ComponentIndexType getSuitableSource(List<ComponentIndexType> cameraList, ComponentIndexType defaultSource) {
        if (cameraList.contains(ComponentIndexType.LEFT_OR_MAIN)) {
            return ComponentIndexType.LEFT_OR_MAIN;
        } else if (cameraList.contains(ComponentIndexType.RIGHT)) {
            return ComponentIndexType.RIGHT;
        } else if (cameraList.contains(ComponentIndexType.UP)) {
            return ComponentIndexType.UP;
        } else if (cameraList.contains(ComponentIndexType.PORT_1)) {
            return ComponentIndexType.PORT_1;
        } else if (cameraList.contains(ComponentIndexType.PORT_2)) {
            return ComponentIndexType.PORT_2;
        } else if (cameraList.contains(ComponentIndexType.PORT_3)) {
            return ComponentIndexType.PORT_3;
        } else if (cameraList.contains(ComponentIndexType.PORT_4)) {
            return ComponentIndexType.PORT_4;
        } else if (cameraList.contains(ComponentIndexType.VISION_ASSIST)) {
            return ComponentIndexType.VISION_ASSIST;
        }
        return defaultSource;
    }

    private void onCameraSourceUpdated(ComponentIndexType devicePosition, CameraLensType lensType) {
        LogUtils.i(LogPath.SAMPLE, "onCameraSourceUpdated", devicePosition, lensType);
        if (devicePosition == lastDevicePosition && lensType == lastLensType) {
            return;
        }
        lastDevicePosition = devicePosition;
        lastLensType = lensType;
        updateViewVisibility(devicePosition, lensType);
        updateInteractionEnabled();
        //如果无需使能或者显示的，也就没有必要切换了。
        if (fpvInteractionWidget.isInteractionEnabled()) {
            fpvInteractionWidget.updateCameraSource(devicePosition, lensType);
        }
        if (lensControlWidget.getVisibility() == View.VISIBLE) {
            lensControlWidget.updateCameraSource(devicePosition, lensType);
        }
        if (ndviCameraPanel.getVisibility() == View.VISIBLE) {
            ndviCameraPanel.updateCameraSource(devicePosition, lensType);
        }
        if (visualCameraPanel.getVisibility() == View.VISIBLE) {
            visualCameraPanel.updateCameraSource(devicePosition, lensType);
        }
        if (autoExposureLockWidget.getVisibility() == View.VISIBLE) {
            autoExposureLockWidget.updateCameraSource(devicePosition, lensType);
        }
        if (focusModeWidget.getVisibility() == View.VISIBLE) {
            focusModeWidget.updateCameraSource(devicePosition, lensType);
        }
        if (focusExposureSwitchWidget.getVisibility() == View.VISIBLE) {
            focusExposureSwitchWidget.updateCameraSource(devicePosition, lensType);
        }
        if (cameraControlsWidget.getVisibility() == View.VISIBLE) {
            cameraControlsWidget.updateCameraSource(devicePosition, lensType);
        }
        if (focalZoomWidget.getVisibility() == View.VISIBLE) {
            focalZoomWidget.updateCameraSource(devicePosition, lensType);
        }
        if (horizontalSituationIndicatorWidget.getVisibility() == View.VISIBLE) {
            horizontalSituationIndicatorWidget.updateCameraSource(devicePosition, lensType);
        }
    }

    private void updateViewVisibility(ComponentIndexType devicePosition, CameraLensType lensType) {
        //只在fpv下显示
        pfvFlightDisplayWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.VISIBLE : View.INVISIBLE);

        //fpv下不显示
        lensControlWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        ndviCameraPanel.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        visualCameraPanel.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        autoExposureLockWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        focusModeWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        focusExposureSwitchWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        cameraControlsWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        focalZoomWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        horizontalSituationIndicatorWidget.setSimpleModeEnable(CameraUtil.isFPVTypeView(devicePosition));

        //只在部分len下显示
        ndviCameraPanel.setVisibility(CameraUtil.isSupportForNDVI(lensType) ? View.VISIBLE : View.INVISIBLE);
    }

    /**
     * Swap the video sources of the FPV and secondary FPV widgets.
     */
    private void swapVideoSource() {
        ComponentIndexType primarySource = primaryFpvWidget.getWidgetModel().getCameraIndex();
        ComponentIndexType secondarySource = secondaryFPVWidget.getWidgetModel().getCameraIndex();
        //两个source都存在的情况下才进行切换
        if (primarySource != ComponentIndexType.UNKNOWN && secondarySource != ComponentIndexType.UNKNOWN) {
            primaryFpvWidget.updateVideoSource(secondarySource);
            secondaryFPVWidget.updateVideoSource(primarySource);
        }
    }

    private void updateInteractionEnabled() {
        fpvInteractionWidget.setInteractionEnabled(!CameraUtil.isFPVTypeView(primaryFpvWidget.getWidgetModel().getCameraIndex()));
    }

    private static class CameraSource {
        ComponentIndexType devicePosition;
        CameraLensType lensType;

        public CameraSource(ComponentIndexType devicePosition, CameraLensType lensType) {
            this.devicePosition = devicePosition;
            this.lensType = lensType;
        }
    }

    @Override
    public void onBackPressed() {
        if (mDrawerLayout.isDrawerOpen(GravityCompat.END)) {
            mDrawerLayout.closeDrawers();
        } else {
            super.onBackPressed();
        }
    }
}
