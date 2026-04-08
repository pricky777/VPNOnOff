package com.vpnonoff;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences("vpnonoff_prefs", Context.MODE_PRIVATE);
            boolean enabled = prefs.getBoolean("service_enabled", false);
            if (enabled) {
                Intent serviceIntent = new Intent(context, WifiMonitorService.class);
                ContextCompat.startForegroundService(context, serviceIntent);
            }
        }
    }
}
