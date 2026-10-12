package com.matejdro.taskertethercontrol;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.util.Log;

import com.matejdro.taskertethercontrol.taskerutils.LocaleConstants;
import com.matejdro.taskertethercontrol.util.ExceptionUtils;
import net.dinglisch.android.tasker.TaskerPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tasker/Locale tethering action.
 *
 * Android 11+ moved the tethering implementation from IConnectivityManager to
 * TetheringManager. In Android 15 that class exists on-device although the SDK
 * 35 public android.jar doesn't expose it. Reflection is guarded and errors
 * are reported to Tasker (rather than returning "OK" for an async request).
 */
@SuppressLint("PrivateApi")
public class TaskerReceiver extends BroadcastReceiver {
    private static final String TAG = "TaskerTetherControl";
    private static final int TETHERING_WIFI = 0;
    // Tasker configuration requests a 7000 ms timeout.
    private static final long RESULT_TIMEOUT_MS = 5500L;

    private interface Completion {
        void finish(String error);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        Bundle options = intent.getBundleExtra(LocaleConstants.EXTRA_BUNDLE);
        if (options == null) return;

        final boolean enable = options.getBoolean(TaskerConstants.KEY_ENABLE_TETHERING, true);
        final PendingResult result = goAsync();
        final Handler handler = new Handler(Looper.getMainLooper());
        final AtomicBoolean completed = new AtomicBoolean();
        final Context appContext = context.getApplicationContext();

        final Runnable timeout = () -> deliver(result, completed,
                "Timed out waiting for Android to " +
                (enable ? "enable" : "disable") + " Wi-Fi tethering.");
        final Completion finish = error -> {
            handler.removeCallbacks(timeout);
            deliver(result, completed, error);
        };

        handler.postDelayed(timeout, RESULT_TIMEOUT_MS);
        Thread worker = new Thread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    requestModernTethering(appContext, enable, finish);
                } else {
                    requestLegacyTethering(appContext, enable, finish);
                }
            } catch (Throwable failure) {
                Log.e(TAG, "Tethering request failed", failure);
                Throwable cause = failure;
                if (failure instanceof InvocationTargetException &&
                        ((InvocationTargetException) failure).getCause() != null) {
                    cause = ((InvocationTargetException) failure).getCause();
                }
                String error = ExceptionUtils.getNestedExceptionMessages(
                        cause instanceof Exception ? (Exception) cause :
                                new IllegalStateException(cause));
                if (containsSecurityException(cause)) {
                    if (!Settings.System.canWrite(appContext)) {
                        error = appContext.getString(R.string.error_no_settings_permission) + error;
                    } else {
                        error = appContext.getString(R.string.error_root_needed) + error;
                    }
                } else if (failure instanceof ReflectiveOperationException ||
                        failure instanceof LinkageError) {
                    error = "Android " + Build.VERSION.RELEASE +
                            " tethering API is inaccessible or changed. " +
                            "Check whether the Magisk module is active, and report this error:\n" +
                            error;
                }
                finish.finish(error);
            }
        }, "tasker-tether-request");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean containsSecurityException(Throwable error) {
        while (error != null) {
            if (error instanceof SecurityException) return true;
            error = error.getCause();
        }
        return false;
    }

    private static void deliver(PendingResult pending, AtomicBoolean completed, String error) {
        if (!completed.compareAndSet(false, true)) return;
        try {
            if (error == null) {
                pending.setResultCode(TaskerPlugin.Setting.RESULT_CODE_OK);
            } else {
                Bundle variables = new Bundle();
                variables.putString(TaskerPlugin.Setting.VARNAME_ERROR_MESSAGE, error);
                TaskerPlugin.addVariableBundle(pending.getResultExtras(true), variables);
                pending.setResultCode(TaskerPlugin.Setting.RESULT_CODE_FAILED);
                Log.e(TAG, error);
            }
        } finally {
            pending.finish();
        }
    }

    /**
     * Modern Android TetheringManager; no longer calls
     * IConnectivityManager.startTethering, which was removed.
     */
    private static void requestModernTethering(Context context, boolean enable,
                                                Completion completion) throws Exception {
        Class<?> managerClass = Class.forName("android.net.TetheringManager");
        Object manager = context.getSystemService("tethering");
        if (manager == null) {
            // Some vendor versions register only class-based service retrieval.
            manager = context.getSystemService(managerClass);
        }
        if (manager == null) throw new IllegalStateException(
                "TetheringManager system service not found on Android " + Build.VERSION.RELEASE);

        if (!enable) {
            managerClass.getMethod("stopTethering", int.class).invoke(manager, TETHERING_WIFI);
            // Older Android stop API has no callback; success means the call was accepted.
            completion.finish(null);
            return;
        }

        Class<?> builderClass = Class.forName(
                "android.net.TetheringManager$TetheringRequest$Builder");
        Object builder = builderClass.getConstructor(int.class).newInstance(TETHERING_WIFI);
        Object request = builderClass.getMethod("build").invoke(builder);
        Class<?> requestClass = Class.forName("android.net.TetheringManager$TetheringRequest");
        Class<?> callbackClass = Class.forName(
                "android.net.TetheringManager$StartTetheringCallback");

        if (!callbackClass.isInterface()) {
            throw new UnsupportedOperationException(
                    "This Android build has a class-based tethering callback; " +
                    "the current compatibility bridge requires an interface.");
        }

        Object callback = Proxy.newProxyInstance(
                callbackClass.getClassLoader(),
                new Class<?>[]{callbackClass},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("onTetheringStarted".equals(name)) {
                        completion.finish(null);
                    } else if ("onTetheringFailed".equals(name)) {
                        int reason = args != null && args.length > 0 && args[0] instanceof Integer
                                ? (Integer) args[0] : -1;
                        completion.finish("Android rejected Wi-Fi tethering request: " +
                                "TetheringManager error code " + reason +
                                ". Confirm privileged permission and provisioning.");
                    } else if ("toString".equals(name)) {
                        return "TaskerTetherControl callback";
                    } else if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    } else if ("equals".equals(name)) {
                        return proxy == args[0];
                    }
                    return null;
                });

        Executor directExecutor = Runnable::run;
        Method start = managerClass.getMethod("startTethering",
                requestClass, Executor.class, callbackClass);
        start.invoke(manager, request, directExecutor, callback);
        // Do not mark Tasker successful here. Wait for onTetheringStarted.
    }

    /**
     * Pre-Android-11 compatibility. Retain old service signature only where it
     * originally existed and retain the platform's async ResultReceiver.
     */
    private static void requestLegacyTethering(Context context, boolean enable,
                                                Completion completion) throws Exception {
        ConnectivityManager manager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) throw new IllegalStateException("ConnectivityManager missing");
        if (!enable) {
            ConnectivityManager.class.getDeclaredMethod("stopTethering", int.class)
                    .invoke(manager, TETHERING_WIFI);
            completion.finish(null);
            return;
        }

        java.lang.reflect.Field field = ConnectivityManager.class.getDeclaredField("mService");
        field.setAccessible(true);
        Object internal = field.get(manager);
        Class<?> serviceClass = Class.forName("android.net.IConnectivityManager");
        ResultReceiver status = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override
            protected void onReceiveResult(int code, Bundle data) {
                completion.finish(code == 0 ? null :
                        "ConnectivityManager refused Wi-Fi tethering: error " + code);
            }
        };
        try {
            serviceClass.getDeclaredMethod("startTethering", int.class,
                    ResultReceiver.class, boolean.class, String.class)
                    .invoke(internal, TETHERING_WIFI, status, false, context.getPackageName());
        } catch (NoSuchMethodException older) {
            serviceClass.getDeclaredMethod("startTethering", int.class,
                    ResultReceiver.class, boolean.class)
                    .invoke(internal, TETHERING_WIFI, status, false);
        }
    }
}
