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

        Button copy = new Button(this);
        copy.setText("Copy diagnostics");
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(
                    "Tasker tether monitoring diagnostics", status.getText()));
        });
        panel.addView(copy);

        TextView help = new TextView(this);
        help.setText("\nHotspot state uses Android state-change broadcasts. " +
                "Connected-client monitoring additionally requires privileged Wi-Fi access. " +
                "If NETWORK_SETTINGS is not granted, update the Magisk module and reboot. " +
                "Tap this app's monitor notification to reopen diagnostics. " +
                "If state remains UNKNOWN, copy these diagnostics for troubleshooting.");
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
                HotspotMonitorService.diagnostics(this));
    }
}
