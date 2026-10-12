package com.matejdro.taskertethercontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.matejdro.taskertethercontrol.taskerutils.LocaleConstants;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Single persistent event-driven SoftAP observer. No polling and no root shell.
 * System-api registration needs NETWORK_SETTINGS or an equivalent privileged
 * permission, normally granted by the systemless Magisk module after reboot.
 *
 * The foreground notification is intentional: a long-lived Android listener
 * needs a process that can survive while Tasker profiles are active.
 */
public final class HotspotMonitorService extends Service {
    private static final String TAG = "TaskerHotspotMonitor";
    private static final String CHANNEL = "tasker_tether_state";
    private static final int NOTIFICATION = 1824;
    private static final String PREFS = "hotspot_monitor_prefs";
    private static final String ENABLED = "enabled";
    private static final String ACTION_STOP = "com.matejdro.taskertethercontrol.STOP_MONITOR";
    private static final String ACTION_RETRY = "com.matejdro.taskertethercontrol.RETRY_MONITOR";
    private static final String AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    private static final String AP_STATE_EXTRA = "wifi_state";
    private static final String AP_MODE_EXTRA = "android.net.wifi.extra.WIFI_AP_MODE";
    private static final int AP_MODE_TETHERED = 1;
    private static final int AP_ENABLED = 13;
    private static final int AP_DISABLED = 11;
    private static final int AP_FAILED = 14;

    static final class Snapshot {
        final boolean registered;
        final Boolean hotspotEnabled;
        final Integer clients;
        Snapshot(boolean registered, Boolean hotspotEnabled, Integer clients) {
            this.registered = registered;
            this.hotspotEnabled = hotspotEnabled;
            this.clients = clients;
        }
    }

    private static volatile Snapshot CURRENT = new Snapshot(false, null, null);
    private static volatile String LAST_ERROR = "No monitor error";
    private static volatile String STATE_SOURCE = "none";
    private static volatile String CLIENT_SOURCE = "none";

    static String diagnostics(Context context) {
        Snapshot s = CURRENT;
        int permission = context.checkSelfPermission("android.permission.NETWORK_SETTINGS");
        return "Hotspot monitoring: " + (s.registered ? "active" : "not started") +
                "\nHotspot: " + (s.hotspotEnabled == null ? "UNKNOWN" : (s.hotspotEnabled ? "ON" : "OFF")) +
                "\nConnected clients: " + (s.clients == null ? "UNKNOWN" : s.clients) +
                "\nHotspot state source: " + STATE_SOURCE +
                "\nClients source: " + CLIENT_SOURCE +
                "\nNETWORK_SETTINGS: " +
                    (permission == android.content.pm.PackageManager.PERMISSION_GRANTED ? "GRANTED" : "NOT GRANTED") +
                "\nLast monitor error: " + LAST_ERROR +
                "\nIf NETWORK_SETTINGS is missing, reinstall the updated Magisk module and reboot.";
    }

    /**
     * Best-effort initial snapshot for Tasker queries before the foreground
     * callback delivers its first event. A failed hidden API lookup is UNKNOWN.
     */
    static Boolean queryHotspotNow(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return null;
            Method current = WifiManager.class.getMethod("getWifiApState");
            int state = ((Number) current.invoke(wifi)).intValue();
            if (state == AP_ENABLED) return true;
            if (state == AP_DISABLED || state == AP_FAILED) return false;
        } catch (ReflectiveOperationException | SecurityException e) {
            Log.d(TAG, "Hidden hotspot state getter not available; waiting for broadcast", e);
        }
        return null;
    }

    static Snapshot snapshot() {
        return CURRENT;
    }

    private boolean broadcastRegistered;
    private boolean callbackAttempted;
    private final BroadcastReceiver hotspotReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent event) {
            if (event == null || !AP_STATE_CHANGED.equals(event.getAction())) return;
            int mode = event.getIntExtra(AP_MODE_EXTRA, -1);
            if (mode != -1 && mode != AP_MODE_TETHERED) return;
            int state = event.getIntExtra(AP_STATE_EXTRA, -1);
            if (state != -1) {
                STATE_SOURCE = "Android WIFI_AP_STATE_CHANGED broadcast";
                Log.i(TAG, "Hotspot broadcast state=" + state);
                try {
                    onStateChanged(state);
                } catch (RuntimeException | LinkageError failure) {
                    recordMonitorError("Hotspot broadcast", failure);
                }
            }
        }
    };
    private Object callback;
    private Method unregister;
    private WifiManager wifi;
    private final Map<String, Integer> clientsByInstance = new HashMap<>();
    private Integer aggregateClients;

    static void retry(Context context) {
        enableAndStart(context);
        Intent retry = new Intent(context, HotspotMonitorService.class).setAction(ACTION_RETRY);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(retry);
            else context.startService(retry);
        } catch (RuntimeException error) {
            LAST_ERROR = "Cannot retry monitor: " + error;
        }
    }

    static void enableAndStart(Context context) {
        Context app = context.getApplicationContext();
        app.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ENABLED, true).apply();
        startIfEnabled(app);
    }

    static void startIfEnabled(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(ENABLED, false)) return;
        Intent start = new Intent(app, HotspotMonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(start);
            else app.startService(start);
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to start hotspot monitoring service", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ensureChannel();
        } catch (RuntimeException | LinkageError error) {
            // A broken notification channel must not crash the app.
            LAST_ERROR = "Notification channel: " + error;
            Log.e(TAG, LAST_ERROR, error);
        }
        try {
            startForeground(NOTIFICATION, notification(getString(R.string.monitor_initializing)));
        } catch (RuntimeException | LinkageError error) {
            // Previously this exception escaped from onCreate and repeatedly
            // crashed the entire app when Tasker tried to restart monitoring.
            LAST_ERROR = "Foreground service unavailable: " + error.toString();
            Log.e(TAG, LAST_ERROR, error);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(ENABLED, false).apply();
            CURRENT = new Snapshot(false, null, null);
            stopSelf();
            return;
        }
        // Hotspot state broadcasts work separately from the privileged client
        // callback. Missing NETWORK_SETTINGS must not disable hotspot detection.
        try {
            registerReceiver(hotspotReceiver, new IntentFilter(AP_STATE_CHANGED));
            broadcastRegistered = true;
            CURRENT = new Snapshot(true, null, null);
            STATE_SOURCE = "awaiting hotspot broadcast";
            Boolean initial = queryHotspotNow(this);
            if (initial != null) {
                STATE_SOURCE = "WifiManager.getWifiApState initial snapshot";
                onStateChanged(initial ? AP_ENABLED : AP_DISABLED);
            }
        } catch (RuntimeException e) {
            LAST_ERROR = "Hotspot broadcast registration: " + e.toString();
            Log.w(TAG, LAST_ERROR, e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ENABLED, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RETRY.equals(intent.getAction())) {
            if (wifi != null && unregister != null && callback != null) {
                try { unregister.invoke(wifi, callback); }
                catch (Exception e) { Log.w(TAG, "Unable to unregister old callback", e); }
            }
            callback = null;
            callbackAttempted = false;
        }
        if (!callbackAttempted) {
            callbackAttempted = true;
            try {
                startObservation();
                CLIENT_SOURCE = "WifiManager SoftApCallback";
                LAST_ERROR = "No monitor error";
            } catch (Throwable error) {
                Log.e(TAG, "Client monitoring unavailable; hotspot broadcast listener remains active", error);
                CLIENT_SOURCE = "unavailable";
                LAST_ERROR = "Client callback: " + error.toString();
                updateNotification(getString(R.string.monitor_unavailable) + " " +
                        error.getClass().getSimpleName() + " (see Diagnostics)");
                requestTaskerRequery();
            }
        }
        return START_STICKY;
    }

    private void startObservation() throws Exception {
        if (Build.VERSION.SDK_INT < 30) {
            throw new UnsupportedOperationException("Connected-client tracking requires Android 11+");
        }
        if (checkSelfPermission("android.permission.NETWORK_SETTINGS") !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("NETWORK_SETTINGS permission missing. Activate the updated Magisk module and reboot.");
        }
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) throw new IllegalStateException("WifiManager not available");
        Class<?> iface = Class.forName("android.net.wifi.WifiManager$SoftApCallback");
        if (!iface.isInterface()) {
            throw new UnsupportedOperationException("SoftApCallback is not an interface on this Android version");
        }
        callback = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("onStateChanged".equals(name) && args != null && args.length > 0) {
                        STATE_SOURCE = "WifiManager SoftApCallback";
                        try {
                            onStateChanged(((Number) args[0]).intValue());
                        } catch (RuntimeException | LinkageError failure) {
                            recordMonitorError("Wi-Fi state callback", failure);
                        }
                    } else if ("onConnectedClientsChanged".equals(name) &&
                            args != null && args.length > 0) {
                        Object last = args[args.length - 1];
                        if (last instanceof List<?>) {
                            try {
                                onClientsChanged(args.length == 2 ? args[0] : null,
                                        ((List<?>) last).size());
                            } catch (RuntimeException | LinkageError failure) {
                                recordMonitorError("Wi-Fi client callback", failure);
                            }
                        }
                    } else if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    } else if ("equals".equals(name)) {
                        return proxy == args[0];
                    } else if ("toString".equals(name)) {
                        return "Tasker Hotspot SoftAP listener";
                    }
                    return null;
                });

        Executor main = command -> new Handler(Looper.getMainLooper()).post(command);
        Method register = WifiManager.class.getMethod("registerSoftApCallback",
                Executor.class, iface);
        unregister = WifiManager.class.getMethod("unregisterSoftApCallback", iface);
        try {
            register.invoke(wifi, main, callback);
        } catch (InvocationTargetException e) {
            callback = null;
            throw e.getCause() instanceof Exception ?
                    (Exception) e.getCause() : new IllegalStateException(e.getCause());
        }
        // Some Android implementations invoke callbacks synchronously during
        // registration. Do not overwrite the state those callbacks delivered.
        Snapshot current = CURRENT;
        CURRENT = new Snapshot(true, current.hotspotEnabled, current.clients);
        if (current.hotspotEnabled == null) {
            updateNotification(getString(R.string.monitor_initializing));
        }
        // Android sends initial hotspot and client snapshots after registration.
    }

    private void onStateChanged(int state) {
        Snapshot before = CURRENT;
        Boolean enabled;
        if (state == AP_ENABLED) enabled = true;
        else if (state == AP_DISABLED || state == AP_FAILED) enabled = false;
        else enabled = null; // Transitional state is not falsely reported as off.

        if (Boolean.FALSE.equals(enabled)) {
            clientsByInstance.clear();
            aggregateClients = 0;
        } else if (Boolean.TRUE.equals(enabled) &&
                !Boolean.TRUE.equals(before.hotspotEnabled)) {
            // On transition from off to on, the old zero-client snapshot is
            // stale until an actual client callback arrives.
            clientsByInstance.clear();
            aggregateClients = null;
        }
        Integer count = Boolean.FALSE.equals(enabled) ? 0
                : (callback == null ? null : getClientCount());
        CURRENT = new Snapshot(broadcastRegistered || callback != null, enabled, count);
        onNewSnapshot(before);
    }

    private void onClientsChanged(Object instanceInfo, int clientCount) {
        Snapshot before = CURRENT;
        if (instanceInfo == null) {
            aggregateClients = clientCount;
        } else {
            clientsByInstance.put(instanceInfo.toString(), clientCount);
        }
        Integer count = getClientCount();
        CLIENT_SOURCE = "WifiManager SoftApCallback";
        CURRENT = new Snapshot(broadcastRegistered || callback != null, before.hotspotEnabled, count);
        onNewSnapshot(before);
    }

    private Integer getClientCount() {
        if (!clientsByInstance.isEmpty()) {
            int sum = 0;
            for (int count : clientsByInstance.values()) sum += count;
            return sum;
        }
        return aggregateClients;
    }

    private void onNewSnapshot(Snapshot before) {
        Snapshot now = CURRENT;
        if (!equalsNullable(before.hotspotEnabled, now.hotspotEnabled) ||
                !equalsNullable(before.clients, now.clients)) {
            updateNotification(getString(R.string.monitor_running) + " - " +
                    (now.hotspotEnabled == null ? "unknown" :
                            (now.hotspotEnabled ? "on" : "off")) + ", " +
                    (now.clients == null ? "clients unknown" : now.clients + " clients"));
            requestTaskerRequery();
        }
    }

    private static boolean equalsNullable(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private void requestTaskerRequery() {
        // A profile re-query must never be able to crash the monitoring process.
        try {
            Intent event = new Intent(LocaleConstants.ACTION_REQUEST_QUERY);
            event.putExtra(LocaleConstants.EXTRA_STRING_ACTIVITY_CLASS_NAME,
                    TetherConditionActivity.class.getName());
            sendBroadcast(event);
        } catch (RuntimeException | LinkageError error) {
            recordMonitorError("Requesting Tasker condition recheck", error);
        }
    }

    private void recordMonitorError(String operation, Throwable error) {
        LAST_ERROR = operation + ": " + error.toString();
        Log.e(TAG, LAST_ERROR, error);
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    getString(R.string.monitor_channel), NotificationManager.IMPORTANCE_LOW);
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification notification(String message) {
        Intent stop = new Intent(this, HotspotMonitorService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT |
                        (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Intent details = new Intent(this, MonitorDiagnosticsActivity.class);
        PendingIntent openDetails = PendingIntent.getActivity(this, 0, details,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return builder.setContentIntent(openDetails)
                .setSmallIcon(R.mipmap.ic_tethering)
                .setContentTitle(getString(R.string.monitor_channel))
                .setContentText(message)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.monitor_stop), stopIntent)
                .build();
    }

    private void updateNotification(String message) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            try {
                manager.notify(NOTIFICATION, notification(message));
            } catch (RuntimeException | LinkageError error) {
                recordMonitorError("Updating monitor notification", error);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (broadcastRegistered) {
            try { unregisterReceiver(hotspotReceiver); }
            catch (RuntimeException e) { Log.w(TAG, "Unregister broadcast failed", e); }
            broadcastRegistered = false;
        }
        if (wifi != null && unregister != null && callback != null) {
            try {
                unregister.invoke(wifi, callback);
            } catch (Exception e) {
                Log.w(TAG, "Unable to unregister SoftAP callback", e);
            }
        }
        callback = null;
        unregister = null;
        CURRENT = new Snapshot(false, null, null);
        requestTaskerRequery();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
