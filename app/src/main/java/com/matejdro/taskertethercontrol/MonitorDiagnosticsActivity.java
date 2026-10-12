package com.matejdro.taskertethercontrol;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Accessible from the launcher and hotspot-monitor notification. */
public final class MonitorDiagnosticsActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroller = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int inset = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20,
                getResources().getDisplayMetrics());
        panel.setPadding(inset, inset, inset, inset);

        TextView heading = new TextView(this);
        heading.setText("Tethering states - Diagnostics");
        heading.setTextSize(20);
        panel.addView(heading);

        status = new TextView(this);
        status.setTextIsSelectable(true);
        status.setTextSize(15);
        status.setPadding(0, inset, 0, inset);
        panel.addView(status);

        Button refresh = new Button(this);
        refresh.setText("Refresh status");
        refresh.setOnClickListener(v -> refresh());
        panel.addView(refresh);

        Button restart = new Button(this);
        restart.setText("Start / retry monitoring");
        restart.setOnClickListener(v -> {
            HotspotMonitorService.retry(this);
            status.postDelayed(this::refresh, 750);
        });
        panel.addView(restart);

        Button clean = new Button(this);
        clean.setText("Recheck and clean clone duplicate");
        clean.setOnClickListener(v -> {
            ProfileSelfCleanup.schedule(this);
            status.postDelayed(this::refresh, 1200);
        });
        panel.addView(clean);

        Button reinstall = new Button(this);
        reinstall.setText(R.string.force_reinstall_magisk_module);
        reinstall.setOnClickListener(v -> showReinstallConfirmation());
        panel.addView(reinstall);

        Button copy = new Button(this);
        copy.setText("Copy diagnostics");
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(
                    "Tasker tether monitoring diagnostics", status.getText()));
        });
        panel.addView(copy);

        Button copyCrash = new Button(this);
        copyCrash.setText("Copy last crash report");
        copyCrash.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(
                    "Tasker Tether Control last crash", TetherApplication.getCrashReport(this)));
        });
        panel.addView(copyCrash);

        Button clearCrash = new Button(this);
        clearCrash.setText("Clear saved crash report");
        clearCrash.setOnClickListener(v -> {
            TetherApplication.clearCrashReport(this);
            refresh();
        });
        panel.addView(clearCrash);

        TextView help = new TextView(this);
        help.setText("\nHotspot state uses Android state-change broadcasts. " +
                "Connected-client monitoring additionally requires privileged Wi-Fi access. " +
                "If NETWORK_SETTINGS is not granted, update the Magisk module and reboot. " +
                "Tap this app's monitor notification to reopen diagnostics. " +
                "For Tasker profile activation, check that recheck requests and " +
                "host condition queries both increment after toggling the hotspot. " +
                "If queries never increase, make sure Tasker monitoring is enabled " +
                "and re-add the plugin State in Tasker. " +
                "Copy diagnostics if the problem remains.");
        panel.addView(help);

        scroller.addView(panel);
        setContentView(scroller);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) refresh();
    }

    private void showReinstallConfirmation() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.magisk_install_confirm_title)
                .setMessage(R.string.magisk_install_confirm_text)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.magisk_install_confirm_button,
                        (dialog, which) -> new SystemInstallTask(this).execute((Void[]) null))
                .show();
    }

    private void refresh() {
        String version;
        try {
            android.content.pm.PackageInfo pkg = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pkg.versionName + " (" + pkg.versionCode + ")";
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            version = "unknown";
        }
        status.setText("App version: " + version + "\n" +
                "Android: " + android.os.Build.VERSION.RELEASE +
                " (API " + android.os.Build.VERSION.SDK_INT + ")\n\n" +
                HotspotMonitorService.diagnostics(this) +
                "\n\nDuplicate app cleanup:\n" +
                ProfileSelfCleanup.describe(this) +
                "\n\nTasker condition transport:\n" +
                TaskerQueryDiagnostics.describe(this) +
                "\n\nLast recorded app crash:\n" +
                TetherApplication.getCrashReport(this));
    }
}
