package com.matejdro.taskertethercontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import com.matejdro.taskertethercontrol.taskerutils.LocaleConstants;

/**
 * All Tasker states share the single Locale QUERY_CONDITION broadcast receiver.
 * Unknown means missing permissions or no fresh callback, never "false".
 */
public final class TetherConditionReceiver extends BroadcastReceiver {
    private static final String TAG = "TaskerTetherState";
    static final String KEY_CONDITION = "condition_type";
    static final int HOTSPOT_ON = 1;
    static final int HOTSPOT_OFF = 2;
    static final int CLIENTS_ON = 3;
    static final int CLIENTS_OFF = 4;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null ||
                !LocaleConstants.ACTION_QUERY_CONDITION.equals(intent.getAction())) return;
        Bundle options = intent.getBundleExtra(LocaleConstants.EXTRA_BUNDLE);
        if (options == null) {
            setResultCode(LocaleConstants.RESULT_CONDITION_UNKNOWN);
            return;
        }
        int mode = options.getInt(KEY_CONDITION, HOTSPOT_ON);
        // Query only uses in-memory state from an active callback listener.
        // Never interpret missing permission / stopped service as hotspot off.
        HotspotMonitorService.Snapshot state = HotspotMonitorService.snapshot();
        // A query can arrive before the foreground service starts or receives
        // the initial broadcast. Attempt an immediate read where Android allows it.
        // Missing access is UNKNOWN, not an incorrect "hotspot disabled".
        if (state.hotspotEnabled == null) {
            Boolean initial = HotspotMonitorService.queryHotspotNow(context);
            if (initial != null) {
                state = new HotspotMonitorService.Snapshot(
                        true, initial, initial ? state.clients : 0);
            }
        }
        if (!state.registered) {
            HotspotMonitorService.enableAndStart(context);
        }

        Boolean satisfied = null;
        switch (mode) {
            case HOTSPOT_ON:
                if (state.hotspotEnabled != null) satisfied = state.hotspotEnabled;
                break;
            case HOTSPOT_OFF:
                if (state.hotspotEnabled != null) satisfied = !state.hotspotEnabled;
                break;
            case CLIENTS_ON:
                if (state.hotspotEnabled != null && !state.hotspotEnabled) {
                    satisfied = false;
                } else if (state.clients != null) {
                    satisfied = state.clients > 0;
                }
                break;
            case CLIENTS_OFF:
                if (state.hotspotEnabled != null && !state.hotspotEnabled) {
                    satisfied = true;
                } else if (state.clients != null) {
                    satisfied = state.clients == 0;
                }
                break;
            default:
                Log.w(TAG, "Unknown state condition " + mode);
        }

        setResultCode(satisfied == null
                ? LocaleConstants.RESULT_CONDITION_UNKNOWN
                : (satisfied ? LocaleConstants.RESULT_CONDITION_SATISFIED
                             : LocaleConstants.RESULT_CONDITION_UNSATISFIED));
    }
}
