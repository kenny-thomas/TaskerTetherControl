package com.matejdro.taskertethercontrol;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keep one local, user-readable crash trace for debugging crashes which occur
 * before the Diagnostics activity can display an error.
 *
 * The system's original uncaught-exception handler still handles the crash.
 * No crash data is uploaded or sent off-device.
 */
public final class TetherApplication extends Application {
    private static final String TAG = "TaskerTetherCrash";
    private static final String PREFS = "tasker_tether_crash";
    private static final String LAST_CRASH = "last_crash";
    private static final int MAX_LENGTH = 14000;

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler system =
                Thread.getDefaultUncaughtExceptionHandler();
        final AtomicBoolean handling = new AtomicBoolean(false);
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (handling.compareAndSet(false, true)) {
                try {
                    saveCrash(thread.getName(), error);
                } catch (Throwable loggingFailure) {
                    Log.e(TAG, "Could not store crash report", loggingFailure);
                }
            }
            if (system != null) {
                system.uncaughtException(thread, error);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }

    private void saveCrash(String threadName, Throwable error) {
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        writer.println("Timestamp (epoch ms): " + System.currentTimeMillis());
        writer.println("Android API: " + android.os.Build.VERSION.SDK_INT);
        writer.println("Thread: " + threadName);
        if (error != null) error.printStackTrace(writer);
        writer.flush();
        String value = buffer.toString();
        if (value.length() > MAX_LENGTH) value = value.substring(0, MAX_LENGTH);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(LAST_CRASH, value).commit();
    }

    public static String getCrashReport(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(LAST_CRASH, "No recorded Java crash in this app.");
    }

    public static void clearCrashReport(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(LAST_CRASH).apply();
    }
}
