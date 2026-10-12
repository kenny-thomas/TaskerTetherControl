package com.matejdro.taskertethercontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Restart the event-driven condition listener after reboot when configured. */
public final class HotspotBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            try {
                HotspotMonitorService.startIfEnabled(context);
            } catch (RuntimeException error) {
                Log.e("TaskerTetherState", "Unable to restart hotspot monitoring", error);
            }
        }
    }
}
