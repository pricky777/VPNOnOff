package com.vpnonoff;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class RelayActivity extends Activity {

    private static final String TAG = "VPNOnOff";
    private static final String CLASH_PACKAGE = "com.github.metacubex.clash.meta";
    private static final String CLASH_CONTROL = "com.github.kr328.clash.ExternalControlActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String action = getIntent().getStringExtra("clash_action");
        if (action != null) {
            Log.i(TAG, "RelayActivity launching Clash: " + action);
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
                Log.e(TAG, "RelayActivity failed: " + e.getMessage());
            }
        }

        // Dismiss the trigger notification
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.cancel(99);

        finish();
    }
}
