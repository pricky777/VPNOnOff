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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "vpnonoff_prefs";
    private static final String KEY_SERVICE_ENABLED = "service_enabled";
    private static final int MIUI_OP_BACKGROUND_START_ACTIVITY = 10021;
    private static final int SHIZUKU_REQUEST_CODE = 100;

    private static final String KEY_SELECTED_CLIENT = "selected_client";

    private Button toggleButton;
    private Spinner clientSpinner;
    private TextView statusText;
    private TextView wifiStatusText;
    private TextView bgPopupStatusText;
    private TextView shizukuStatusText;
    private boolean serviceRunning = false;

    private BroadcastReceiver statusReceiver;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermListener =
            (requestCode, grantResult) -> {
                if (requestCode == SHIZUKU_REQUEST_CODE) {
                    runOnUiThread(this::checkPermissions);
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        toggleButton = findViewById(R.id.toggleButton);
        statusText = findViewById(R.id.statusText);
        wifiStatusText = findViewById(R.id.wifiStatusText);
        bgPopupStatusText = findViewById(R.id.bgPopupStatusText);
        shizukuStatusText = findViewById(R.id.shizukuStatusText);

        Shizuku.addRequestPermissionResultListener(shizukuPermListener);

        clientSpinner = findViewById(R.id.clientSpinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.vpn_clients, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        clientSpinner.setAdapter(adapter);

        SharedPreferences prefs2 = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        clientSpinner.setSelection(prefs2.getInt(KEY_SELECTED_CLIENT, 0));

        clientSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
                editor.putInt(KEY_SELECTED_CLIENT, position);
                editor.apply();

                // Restart service to pick up new client selection
                if (serviceRunning) {
                    stopMonitorService();
                    startMonitorService();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        serviceRunning = prefs.getBoolean(KEY_SERVICE_ENABLED, false);

        if (serviceRunning) {
            startMonitorService();
        }

        updateUI();
        updateWifiStatus();
        checkPermissions();

        toggleButton.setOnClickListener(v -> {
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(shizukuPermListener);
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

    private void requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                // Shizuku not running, try to open Shizuku app
                Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (intent != null) {
                    startActivity(intent);
                }
                return;
            }
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
        } catch (Exception e) {
            // Shizuku not installed
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/RikkaApps/Shizuku/releases"));
            startActivity(intent);
        }
    }

    private void checkPermissions() {
        checkShizukuPermission();

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

    private void checkShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                shizukuStatusText.setText("Shizuku: 未运行 (点击启动)");
                shizukuStatusText.setTextColor(0xFFF44336);
                shizukuStatusText.setOnClickListener(v -> requestShizukuPermission());
                return;
            }
            boolean granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            shizukuStatusText.setText(granted
                    ? "Shizuku: 已授权"
                    : "Shizuku: 未授权 (点击授权)");
            shizukuStatusText.setTextColor(granted ? 0xFF4CAF50 : 0xFFFF9800);
            shizukuStatusText.setOnClickListener(granted ? null : v -> requestShizukuPermission());
        } catch (Exception e) {
            shizukuStatusText.setText("Shizuku: 未安装 (点击下载)");
            shizukuStatusText.setTextColor(0xFFF44336);
            shizukuStatusText.setOnClickListener(v -> requestShizukuPermission());
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
