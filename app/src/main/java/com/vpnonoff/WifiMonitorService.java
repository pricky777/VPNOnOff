package com.vpnonoff;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import java.util.HashSet;
import java.util.Set;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

public class WifiMonitorService extends Service {

    private static final String TAG = "VPNOnOff";
    private static final String CHANNEL_ID = "vpnonoff_channel";
    private static final int NOTIFICATION_ID = 1;

    private static final String CLASH_PACKAGE = "com.github.metacubex.clash.meta";
    private static final String CLASH_CONTROL = "com.github.kr328.clash.ExternalControlActivity";
    private static final String ACTION_START = CLASH_PACKAGE + ".action.START_CLASH";
    private static final String ACTION_STOP = CLASH_PACKAGE + ".action.STOP_CLASH";

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private HandlerThread handlerThread;
    private Handler handler;
    private final Set<Network> wifiNetworks = new HashSet<>();
    private String lastDesiredAction = null;
    private static final long DEBOUNCE_MS = 500;
    private Runnable pendingAction;

    @Override
    public void onCreate() {
        super.onCreate();

        handlerThread = new HandlerThread("WifiMonitorThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("监听中..."));
        registerWifiCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
        if (handler != null && pendingAction != null) {
            handler.removeCallbacks(pendingAction);
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartIntent = new Intent(this, WifiMonitorService.class);
        PendingIntent pendingIntent = PendingIntent.getService(
                this, 0, restartIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        android.app.AlarmManager alarm = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
        alarm.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 3000, pendingIntent);
        super.onTaskRemoved(rootIntent);
    }

    private void registerWifiCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifiNetworks.add(network);
                    scheduleAction();
                }
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifiNetworks.add(network);
                } else {
                    wifiNetworks.remove(network);
                }
                scheduleAction();
            }

            @Override
            public void onLost(Network network) {
                wifiNetworks.remove(network);
                scheduleAction();
            }
        };

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        connectivityManager.registerNetworkCallback(request, networkCallback, handler);

        // Sync VPN state on startup, run on handler thread to avoid race with callbacks
        handler.post(() -> {
            Network[] networks = connectivityManager.getAllNetworks();
            for (Network network : networks) {
                NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifiNetworks.add(network);
                }
            }
            Log.i(TAG, "Initial WiFi state: " + (wifiNetworks.isEmpty() ? "disconnected" : "connected"));
            executeAction(wifiNetworks.isEmpty() ? ACTION_START : ACTION_STOP);
        });
    }

    private void scheduleAction() {
        if (pendingAction != null) {
            handler.removeCallbacks(pendingAction);
        }
        pendingAction = () -> executeAction(wifiNetworks.isEmpty() ? ACTION_START : ACTION_STOP);
        handler.postDelayed(pendingAction, DEBOUNCE_MS);
    }

    private void executeAction(String action) {
        if (action.equals(lastDesiredAction)) {
            Log.i(TAG, "Skipping duplicate action: " + action);
            return;
        }

        boolean wifiConnected = !wifiNetworks.isEmpty();
        Log.i(TAG, wifiConnected ? "WiFi connected" : "WiFi disconnected");

        if (controlClash(action)) {
            lastDesiredAction = action;
            updateNotification(wifiConnected ? "WiFi 已连接 - VPN 关闭" : "WiFi 未连接 - VPN 开启");
            sendStatusBroadcast(wifiConnected);
        }
    }

    private boolean controlClash(String action) {
        Log.i(TAG, "Controlling Clash: " + action);
        if (!controlClashViaShizuku(action)) {
            Log.e(TAG, "Failed to control Clash - check Shizuku is running and authorized");
            return false;
        }
        return true;
    }

    private boolean controlClashViaShizuku(String action) {
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku not available");
                return false;
            }

            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Shizuku permission not granted");
                return false;
            }

            String cmd = "am start"
                    + " -a " + action
                    + " -n " + CLASH_PACKAGE + "/" + CLASH_CONTROL
                    + " --activity-multiple-task"
                    + " --activity-no-history"
                    + " --activity-no-animation"
                    + " --activity-exclude-from-recents";

            Log.i(TAG, "Executing via Shizuku: " + cmd);
            Method newProcess = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            newProcess.setAccessible(true);
            Process process = (Process) newProcess.invoke(null,
                    new String[]{"sh", "-c", cmd}, null, null);
            int exitCode = process.waitFor();
            Log.i(TAG, "Shizuku command exit code: " + exitCode);
            return exitCode == 0;
        } catch (Exception e) {
            Log.e(TAG, "Shizuku execution failed: " + e.getMessage());
            return false;
        }
    }

    private void sendStatusBroadcast(boolean wifiConnected) {
        Intent intent = new Intent("com.vpnonoff.STATUS_CHANGED");
        intent.putExtra("wifi_connected", wifiConnected);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "VPN OnOff Service",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("WiFi 状态监听服务");
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("VPN OnOff")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
