package com.matejdro.taskertethercontrol;

import android.content.Intent;
import android.os.Bundle;
import android.widget.RadioButton;
import com.matejdro.taskertethercontrol.taskerutils.LocaleConstants;
import com.matejdro.taskertethercontrol.taskerutils.TaskerSetupActivity;

/**
 * One Locale/Tasker condition editor with four selectable states.
 * Every instance saves a mode in the host's persistent configuration bundle.
 */
public final class TetherConditionActivity extends TaskerSetupActivity {
    private RadioButton hotspotOn;
    private RadioButton hotspotOff;
    private RadioButton clientsOn;
    private RadioButton clientsOff;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setContentView(R.layout.activity_tether_condition);
        hotspotOn = findViewById(R.id.conditionHotspotOn);
        hotspotOff = findViewById(R.id.conditionHotspotOff);
        clientsOn = findViewById(R.id.conditionClientsOn);
        clientsOff = findViewById(R.id.conditionClientsOff);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onFreshTaskerSetup() {
        hotspotOn.setChecked(true);
    }

    @Override
    protected boolean onPreviousTaskerOptionsLoaded(Bundle options) {
        int selected = options.getInt(TetherConditionReceiver.KEY_CONDITION,
                TetherConditionReceiver.HOTSPOT_ON);
        switch (selected) {
            case TetherConditionReceiver.HOTSPOT_OFF:
                hotspotOff.setChecked(true);
                break;
            case TetherConditionReceiver.CLIENTS_ON:
                clientsOn.setChecked(true);
                break;
            case TetherConditionReceiver.CLIENTS_OFF:
                clientsOff.setChecked(true);
                break;
            default:
                hotspotOn.setChecked(true);
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        int mode;
        int description;
        if (hotspotOff.isChecked()) {
            mode = TetherConditionReceiver.HOTSPOT_OFF;
            description = R.string.condition_hotspot_off;
        } else if (clientsOn.isChecked()) {
            mode = TetherConditionReceiver.CLIENTS_ON;
            description = R.string.condition_clients_on;
        } else if (clientsOff.isChecked()) {
            mode = TetherConditionReceiver.CLIENTS_OFF;
            description = R.string.condition_clients_off;
        } else {
            mode = TetherConditionReceiver.HOTSPOT_ON;
            description = R.string.condition_hotspot_on;
        }

        Bundle data = new Bundle();
        data.putInt(TetherConditionReceiver.KEY_CONDITION, mode);
        Intent result = new Intent();
        result.putExtra(LocaleConstants.EXTRA_BUNDLE, data);
        result.putExtra(LocaleConstants.EXTRA_STRING_BLURB, getString(description));
        setResult(RESULT_OK, result);

        // Start a persistent listener when a condition is configured.
        HotspotMonitorService.enableAndStart(this);
        super.onBackPressed();
    }
}
