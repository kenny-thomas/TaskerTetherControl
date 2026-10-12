package com.matejdro.taskertethercontrol;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.DateFormat;
import java.util.Date;

/**
 * Local only transport diagnostics. Sent requests are not acknowledgements:
 * only a matching QUERY_CONDITION observed by the receiver confirms delivery.
 */
final class TaskerQueryDiagnostics {
    private static final String PREFS = "tasker_condition_transport_debug";
    private static final String SENT_COUNT = "sent_count";
    private static final String QUERY_COUNT = "query_count";
    private static final String LAST_SENT_MS = "last_sent_ms";
    private static final String LAST_QUERY_MS = "last_query_ms";
    private static final String LAST_ROUTE = "last_route";
    private static final String LAST_MODE = "last_mode";
    private static final String LAST_CODE = "last_code";
    private static final String LAST_QUERY_INFO = "last_query_info";

    private TaskerQueryDiagnostics() {}

    static void sent(Context context, String route) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putLong(SENT_COUNT, prefs.getLong(SENT_COUNT, 0) + 1)
                .putLong(LAST_SENT_MS, System.currentTimeMillis())
                .putString(LAST_ROUTE, route)
                .apply();
    }

    static void queried(Context context, int mode, int code, String info) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putLong(QUERY_COUNT, prefs.getLong(QUERY_COUNT, 0) + 1)
                .putLong(LAST_QUERY_MS, System.currentTimeMillis())
                .putInt(LAST_MODE, mode)
                .putInt(LAST_CODE, code)
                .putString(LAST_QUERY_INFO, info)
                .apply();
    }

    static String describe(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return "Tasker recheck requests sent: " + prefs.getLong(SENT_COUNT, 0)
                + " (not delivery acknowledgements)"
                + "\nLast request time: " + time(prefs.getLong(LAST_SENT_MS, 0))
                + "\nLast request route: " + prefs.getString(LAST_ROUTE, "none")
                + "\nTasker condition queries received: " + prefs.getLong(QUERY_COUNT, 0)
                + "\nLast query time: " + time(prefs.getLong(LAST_QUERY_MS, 0))
                + "\nLast query mode: " + prefs.getInt(LAST_MODE, -1)
                + "\nLast query result code: " + prefs.getInt(LAST_CODE, -1)
                + "\nLast query details: " + prefs.getString(LAST_QUERY_INFO, "none")
                + "\nResult codes: 16=active, 17=inactive, 18=unknown.";
    }

    private static String time(long epoch) {
        return epoch == 0 ? "none"
                : DateFormat.getDateTimeInstance().format(new Date(epoch));
    }
}
