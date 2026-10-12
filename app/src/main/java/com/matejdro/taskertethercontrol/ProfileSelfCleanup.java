package com.matejdro.taskertethercontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherUserInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Removes only this package from Android's verified CLONE profile.
 * No system partition, Magisk module, user account or unrelated app is touched.
 * Fail closed if Android does not report the user's clone profile explicitly.
 */
final class ProfileSelfCleanup {
    private static final String TAG = "TaskerProfileCleanup";
    private static final String PREFS = "profile_self_cleanup";
    private static final String RESULT = "last_result";
    private static final String RUN_TIME = "run_time";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final String PACKAGE = "com.matejdro.taskertethercontrol";

    private ProfileSelfCleanup() {}

    /** Only the primary Android system user owns this Tasker monitor. */
    static boolean monitorAllowed(Context context) {
        try {
            UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
            return users == null || Build.VERSION.SDK_INT < 33 || !users.isProfile();
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot identify user profile; refusing duplicate monitor", error);
            return false;
        }
    }

    static void schedule(Context context) {
        if (Build.VERSION.SDK_INT < 35 || !monitorAllowed(context)) return;
        Context app = context.getApplicationContext();
        UserManager users = (UserManager) app.getSystemService(Context.USER_SERVICE);
        if (users == null || !users.isSystemUser()) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try {
                runCleanup(app, users);
            } catch (Throwable failure) {
                record(app, "Cleanup could not complete: " + failure);
                Log.w(TAG, "Clone-profile cleanup unavailable", failure);
            } finally {
                RUNNING.set(false);
            }
        }, "tasker-clone-cleanup");
        worker.setDaemon(true);
        worker.start();
    }

    private static void runCleanup(Context context, UserManager users) {
        LauncherApps launcher = (LauncherApps) context
                .getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcher == null) {
            record(context, "LauncherApps service unavailable; nothing removed");
            return;
        }
        List<UserHandle> handles = users.getUserProfiles();
        int found = 0;
        int removed = 0;
        int failures = 0;
        int ownUserId = Process.myUserHandle().getIdentifier();
        for (UserHandle handle : handles) {
            int id = handle.getIdentifier();
            if (id <= 0 || id == ownUserId) continue;
            LauncherUserInfo info;
            try {
                info = launcher.getLauncherUserInfo(handle);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to inspect profile " + id, e);
                continue;
            }
            if (info == null || !ProfileCleanupPolicy.CLONE_PROFILE.equals(info.getUserType()))
                continue;
            boolean installed;
            try {
                installed = launcher.getApplicationInfo(PACKAGE, 0, handle) != null;
            } catch (PackageManager.NameNotFoundException notInstalled) {
                installed = false;
            } catch (RuntimeException unknown) {
                Log.w(TAG, "Cannot verify installed clone " + id, unknown);
                continue;
            }
            if (!ProfileCleanupPolicy.canRemove(info.getUserType(), id, ownUserId, installed))
                continue;
            found++;
            try {
                String response = removeOnlyFromClone(id);
                if (response.startsWith("Success")) {
                    removed++;
                    Log.i(TAG, "Removed only Tasker Tether Control from clone user " + id);
                } else {
                    failures++;
                    Log.w(TAG, "Clone user " + id + ": " + response);
                }
            } catch (Exception e) {
                failures++;
                Log.w(TAG, "Could not clean clone " + id, e);
            }
        }
        record(context, "Verified clone copies: " + found +
                "; removed: " + removed + "; failed: " + failures +
                (found == 0 ? " (nothing to remove)" : ""));
    }

    /** 'pm uninstall --user' changes only the supplied user installation. */
    private static String removeOnlyFromClone(int userId) throws Exception {
        if (userId <= 0) throw new IllegalArgumentException("Cannot remove primary user");
        ProcessBuilder builder = new ProcessBuilder("su", "-c",
                "pm uninstall --user " + userId + " " + PACKAGE);
        builder.redirectErrorStream(true);
        java.lang.Process process = builder.start();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Root package cleanup timed out");
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader input = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null && output.length() < 2000) {
                    output.append(line).append(' ');
                }
            }
            String result = output.toString().trim();
            return process.exitValue() == 0 && result.contains("Success")
                    ? "Success" : "Exit " + process.exitValue() + ": " + result;
        } finally {
            process.destroy();
        }
    }

    private static void record(Context context, String value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(RESULT, value).putLong(RUN_TIME, System.currentTimeMillis()).apply();
    }

    static String describe(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
        String profile = users == null ? "unknown"
                : (Build.VERSION.SDK_INT >= 33 && users.isProfile() ? "secondary profile"
                        : "primary/full user");
        return "Current profile: " + profile +
                "\nCleanup status: " + preferences.getString(RESULT,
                        "Not checked yet (requires Android 15 and primary profile)") +
                "\nCleanup timestamp (epoch ms): " +
                preferences.getLong(RUN_TIME, 0) +
                "\nOnly verified CLONE profiles are uninstalled; work/private profiles untouched.";
    }
}
