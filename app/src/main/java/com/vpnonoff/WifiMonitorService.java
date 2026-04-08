package com.vpnonoff;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

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
    private Handler mainHandler;
    private PowerManager.WakeLock wakeLock;
    private boolean wifiConnected = false;
    private boolean initialized = false;

    private static final long DEBOUNCE_MS = 500;
    private Runnable pendingAction;

    @Override
    public void onCreate() {
        super.onCreate();

        // Use a dedicated thread for network callbacks
        handlerThread = new HandlerThread("WifiMonitorThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        mainHandler = new Handler(getMainLooper());

        // Acquire partial wake lock to keep CPU running
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VPNOnOff::WifiMonitor");
        wakeLock.acquire();

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
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Restart service if swiped from recents
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

        wifiConnected = isWifiConnected();
        initialized = true;
        Log.i(TAG, "Initial WiFi state: " + (wifiConnected ? "connected" : "disconnected"));
        updateNotification(wifiConnected ? "WiFi 已连接 - VPN 关闭" : "WiFi 未连接 - VPN 开启");

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    Log.i(TAG, "WiFi connected");
                    onWifiStateChanged(true);
                }
            }

            @Override
            public void onLost(Network network) {
                handler.postDelayed(() -> {
                    boolean stillConnected = isWifiConnected();
                    if (!stillConnected) {
                        Log.i(TAG, "WiFi disconnected");
                        onWifiStateChanged(false);
                    }
                }, 500);
            }
        };

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        connectivityManager.registerNetworkCallback(request, networkCallback, handler);
    }

    private boolean isWifiConnected() {
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork == null) return false;
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNetwork);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private void onWifiStateChanged(boolean connected) {
        if (!initialized || connected == wifiConnected) return;
        wifiConnected = connected;

        if (pendingAction != null) {
            handler.removeCallbacks(pendingAction);
        }

        pendingAction = () -> {
            if (connected) {
                controlClash(ACTION_STOP);
                updateNotification("WiFi 已连接 - VPN 关闭");
            } else {
                controlClash(ACTION_START);
                updateNotification("WiFi 未连接 - VPN 开启");
            }
            sendStatusBroadcast(connected);
        };
        handler.postDelayed(pendingAction, DEBOUNCE_MS);
    }

    private void controlClash(String action) {
        Log.i(TAG, "Controlling Clash: " + action);
        try {
            Intent intent = new Intent(action);
            intent.setComponent(new ComponentName(CLASH_PACKAGE, CLASH_CONTROL));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_NO_HISTORY
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "startActivity failed, trying am command: " + e.getMessage());
            controlClashViaShell(action);
        }
    }

    private void controlClashViaShell(String action) {
        try {
            String[] cmd = {
                "am", "start",
                "-a", action,
                "-n", CLASH_PACKAGE + "/" + CLASH_CONTROL,
                "--activity-no-history",
                "--activity-no-animation",
                "--activity-exclude-from-recents"
            };
            Runtime.getRuntime().exec(cmd);
        } catch (Exception e) {
            Log.e(TAG, "Shell command also failed: " + e.getMessage());
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
