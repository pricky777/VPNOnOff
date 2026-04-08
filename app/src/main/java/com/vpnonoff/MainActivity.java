package com.vpnonoff;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "vpnonoff_prefs";
    private static final String KEY_SERVICE_ENABLED = "service_enabled";
    private static final int MIUI_OP_BACKGROUND_START_ACTIVITY = 10021;

    private Button toggleButton;
    private TextView statusText;
    private TextView wifiStatusText;
    private TextView overlayStatusText;
    private TextView bgPopupStatusText;
    private boolean serviceRunning = false;

    private BroadcastReceiver statusReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        toggleButton = findViewById(R.id.toggleButton);
        statusText = findViewById(R.id.statusText);
        wifiStatusText = findViewById(R.id.wifiStatusText);
        overlayStatusText = findViewById(R.id.overlayStatusText);
        bgPopupStatusText = findViewById(R.id.bgPopupStatusText);

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }

        // Restore state
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        serviceRunning = prefs.getBoolean(KEY_SERVICE_ENABLED, false);

        if (serviceRunning) {
            startMonitorService();
        }

        updateUI();
        updateWifiStatus();
        checkPermissions();

        toggleButton.setOnClickListener(v -> {
            if (!serviceRunning && !Settings.canDrawOverlays(this)) {
                requestOverlayPermission();
                return;
            }

            serviceRunning = !serviceRunning;
            SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            editor.putBoolean(KEY_SERVICE_ENABLED, serviceRunning);
            editor.apply();

            if (serviceRunning) {
                startMonitorService();
            } else {
                stopMonitorService();
            }
            updateUI();
        });

        statusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateWifiStatus();
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter("com.vpnonoff.STATUS_CHANGED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        updateWifiStatus();
        updateUI();
        checkPermissions();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (statusReceiver != null) {
            unregisterReceiver(statusReceiver);
        }
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void openAppPermissionSettings() {
        try {
            Intent intent = new Intent("miui.intent.action.APP_PERM_EDITOR");
            intent.setClassName("com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity");
            intent.putExtra("extra_pkgname", getPackageName());
            startActivity(intent);
        } catch (Exception e) {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void checkPermissions() {
        // Overlay permission
        boolean hasOverlay = Settings.canDrawOverlays(this);
        overlayStatusText.setText(hasOverlay
                ? "悬浮窗权限: 已授予"
                : "悬浮窗权限: 未授予 (点击授予)");
        overlayStatusText.setTextColor(hasOverlay ? 0xFF4CAF50 : 0xFFFF9800);
        overlayStatusText.setOnClickListener(hasOverlay ? null : v -> requestOverlayPermission());

        // MIUI background popup permission
        boolean isMiui = isMiuiDevice();
        if (isMiui) {
            boolean hasBgPopup = checkMiuiBgStartPermission();
            bgPopupStatusText.setText(hasBgPopup
                    ? "后台弹出界面: 已授予"
                    : "后台弹出界面: 未授予 (点击授予)");
            bgPopupStatusText.setTextColor(hasBgPopup ? 0xFF4CAF50 : 0xFFFF9800);
            bgPopupStatusText.setOnClickListener(hasBgPopup ? null : v -> openAppPermissionSettings());
        } else {
            bgPopupStatusText.setVisibility(android.view.View.GONE);
        }
    }

    private boolean isMiuiDevice() {
        try {
            String miuiVersion = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class)
                    .invoke(null, "ro.miui.ui.version.name");
            return miuiVersion != null && !miuiVersion.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkMiuiBgStartPermission() {
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int result = (int) AppOpsManager.class
                    .getMethod("checkOpNoThrow", int.class, int.class, String.class)
                    .invoke(appOps, MIUI_OP_BACKGROUND_START_ACTIVITY,
                            android.os.Process.myUid(), getPackageName());
            return result == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return true;
        }
    }

    private void startMonitorService() {
        Intent intent = new Intent(this, WifiMonitorService.class);
        ContextCompat.startForegroundService(this, intent);
    }

    private void stopMonitorService() {
        Intent intent = new Intent(this, WifiMonitorService.class);
        stopService(intent);
    }

    private void updateUI() {
        if (serviceRunning) {
            toggleButton.setText("停止监听");
            statusText.setText("服务运行中");
            statusText.setTextColor(0xFF4CAF50);
        } else {
            toggleButton.setText("开始监听");
            statusText.setText("服务已停止");
            statusText.setTextColor(0xFFF44336);
        }
    }

    private void updateWifiStatus() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = cm.getActiveNetwork();
        boolean wifiConnected = false;
        if (network != null) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            wifiConnected = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        }
        wifiStatusText.setText(wifiConnected
                ? "WiFi: 已连接 | VPN: 应关闭"
                : "WiFi: 未连接 | VPN: 应开启");
    }
}
