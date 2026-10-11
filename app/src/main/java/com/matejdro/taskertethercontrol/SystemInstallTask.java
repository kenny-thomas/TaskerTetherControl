package com.matejdro.taskertethercontrol;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;

import com.matejdro.taskertethercontrol.util.ExceptionUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Installs a systemless Magisk module, never remounts or directly edits /system.
 * This makes the application a privileged app after reboot, but it does not
 * change the older tethering implementation or guarantee Android 15 support.
 */
class SystemInstallTask extends AsyncTask<Void, Void, Boolean> {
    private static final String MODULE_ID = "tasker_tether_control";
    private static final String MODULE_APK =
            "system/priv-app/TaskerTetherControl/TaskerTetherControl.apk";
    private static final String MODULE_PERMISSIONS =
            "system/etc/permissions/privapp-permissions-com.matejdro.taskertethercontrol.xml";

    private final WeakReference<Context> contextRef;
    private final ProgressDialog progressDialog;
    private String errorMessage;

    SystemInstallTask(Context context) {
        contextRef = new WeakReference<>(context);
        progressDialog = new ProgressDialog(context);
        errorMessage = context.getString(R.string.unknown_error);
    }

    @Override
    protected void onPreExecute() {
        progressDialog.setIndeterminate(true);
        progressDialog.setCancelable(false);
        progressDialog.setMessage(progressDialog.getContext()
                .getString(R.string.magisk_install_progress));
        progressDialog.show();
    }

    @Override
    protected Boolean doInBackground(Void... params) {
        Context context = contextRef.get();
        if (context == null) {
            return false;
        }

        File archive = new File(context.getCacheDir(), "tasker-tether-magisk.zip");
        // This temporary path is deliberately predictable and contains no user input.
        String stagedPath = "/data/local/tmp/tasker-tether-magisk-" +
                android.os.Process.myUid() + ".zip";
        try {
            ShellResult root = runRoot("id -u", 20000);
            if (root.exitCode != 0 || !"0".equals(root.output.trim())) {
                errorMessage = context.getString(R.string.no_root) + "\n" + root.output;
                return false;
            }

            ShellResult magisk = runRoot("command -v magisk >/dev/null 2>&1 && magisk -v", 15000);
            if (magisk.exitCode != 0) {
                errorMessage = context.getString(R.string.magisk_missing) + "\n" + magisk.output;
                return false;
            }

            createModuleZip(context, archive);
            if (!archive.isFile() || archive.length() < 1000) {
                throw new IOException("Generated Magisk ZIP is incomplete.");
            }

            String target = shellQuote(stagedPath);
            String source = shellQuote(archive.getCanonicalPath());
            // Copy to a standard root-accessible location. Never call mount/remount.
            // Clean up the temporary ZIP regardless of installer outcome.
            String install = "cp " + source + " " + target +
                    " && chmod 600 " + target +
                    " && magisk --install-module " + target +
                    "; result=$?; rm -f " + target + "; exit $result";
            ShellResult result = runRoot(install, 120000);
            if (result.exitCode != 0) {
                errorMessage = "Magisk installer exited " + result.exitCode +
                        ":\n" + result.output;
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errorMessage = context.getString(R.string.magisk_install_timeout);
            return false;
        } catch (Exception e) {
            errorMessage = ExceptionUtils.getNestedExceptionMessages(e);
            return false;
        } finally {
            // Avoid persisting an installable ZIP in unprotected app storage.
            if (archive.exists()) {
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
            }
        }
    }

    private static void createModuleZip(Context context, File destination) throws Exception {
        // Magisk will overlay these paths at boot; no partition is remounted.
        String prop = "id=" + MODULE_ID + "\n" +
                "name=Tasker Tether Control (systemless)\n" +
                "version=1.0\n" +
                "versionCode=1\n" +
                "author=TaskerTetherControl\n" +
                "description=Systemless privileged permissions for Tasker Tether Control\n";
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(destination))) {
            out.putNextEntry(new ZipEntry("module.prop"));
            out.write(prop.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();

            try (InputStream apk = new FileInputStream(context.getApplicationInfo().sourceDir)) {
                addEntry(out, MODULE_APK, apk);
            }
            try (InputStream xml = context.getResources().openRawResource(
                    R.raw.oreo_permission_definition)) {
                addEntry(out, MODULE_PERMISSIONS, xml);
            }
        }
    }

    private static void addEntry(ZipOutputStream out, String name, InputStream input)
            throws IOException {
        out.putNextEntry(new ZipEntry(name));
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.closeEntry();
    }

    private static String shellQuote(String input) {
        return "'" + input.replace("'", "'\"'\"'") + "'";
    }

    private static final class ShellResult {
        final int exitCode;
        final String output;

        ShellResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static ShellResult runRoot(String command, long timeoutMs)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true).start();
        final StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (output.length() < 12000) {
                        output.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // Root process may close the stream when terminated.
            }
        }, "magisk-installer-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("Magisk/root command timed out after " +
                        (timeoutMs / 1000) + " seconds. Check root approval.");
            }
            reader.join(2000);
            return new ShellResult(process.exitValue(), output.toString().trim());
        } finally {
            process.destroy();
        }
    }

    @Override
    protected void onPostExecute(Boolean success) {
        if (progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        Context context = contextRef.get();
        if (context == null) {
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(success ? R.string.install_success : R.string.install_failed)
                .setMessage(success ? context.getString(R.string.reboot_request) : errorMessage)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
