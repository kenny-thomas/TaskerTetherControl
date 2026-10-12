package com.matejdro.taskertethercontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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

    static Snapshot snapshot() {
        return CURRENT;
    }

    private Object callback;
    private Method unregister;
    private WifiManager wifi;
    private final Map<String, Integer> clientsByInstance = new HashMap<>();
    private Integer aggregateClients;

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
        ensureChannel();
        startForeground(NOTIFICATION, notification(getString(R.string.monitor_initializing)));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ENABLED, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (callback != null) return START_STICKY;
        try {
            startObservation();
        } catch (Throwable error) {
            Log.e(TAG, "Soft AP monitoring unavailable", error);
            CURRENT = new Snapshot(false, null, null);
            String message = getString(R.string.monitor_unavailable) + " " +
                    (error.getCause() == null ? error.getClass().getSimpleName()
                            : error.getCause().getClass().getSimpleName());
            updateNotification(message);
            requestTaskerRequery();
            // Remain visible so a user can see permission/API errors and disable monitoring.
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
                        onStateChanged(((Number) args[0]).intValue());
                    } else if ("onConnectedClientsChanged".equals(name) &&
                            args != null && args.length > 0) {
                        Object last = args[args.length - 1];
                        if (last instanceof List<?>) {
                            onClientsChanged(args.length == 2 ? args[0] : null,
                                    ((List<?>) last).size());
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
        CURRENT = new Snapshot(true, null, null);
        updateNotification(getString(R.string.monitor_initializing));
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
        }
        Integer count = Boolean.FALSE.equals(enabled) ? 0 : getClientCount();
        CURRENT = new Snapshot(true, enabled, count);
        onNewSnapshot(before);
    }

    private void onClientsChanged(Object instanceInfo, int clientCount) {
        Snapshot before = CURRENT;
        if (instanceInfo == null) {
            aggregateClients = clientCount;
        } else {
            clientsByInstance.put(instanceInfo.toString(), clientCount);
        }
        int count = getClientCount();
        CURRENT = new Snapshot(true, before.hotspotEnabled, count);
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
        // Locale API requires the Activity class, not the broadcast receiver.
        Intent event = new Intent(LocaleConstants.ACTION_REQUEST_QUERY);
        event.putExtra(LocaleConstants.EXTRA_STRING_ACTIVITY_CLASS_NAME,
                TetherConditionActivity.class.getName());
        sendBroadcast(event);
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
        return builder.setSmallIcon(R.mipmap.ic_tethering)
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
        if (manager != null) manager.notify(NOTIFICATION, notification(message));
    }

    @Override
    public void onDestroy() {
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
