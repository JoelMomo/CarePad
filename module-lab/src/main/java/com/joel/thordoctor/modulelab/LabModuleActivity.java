package com.joel.thordoctor.modulelab;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

import carepad.contracts.CarePadProtocol;

public final class LabModuleActivity extends Activity {
    private static final String EXTRA_INTENTIONAL_CRASH = "carepad.lab.crash";
    private static final String META_ALWAYS_CRASH = "carepad.lab.ALWAYS_CRASH";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (alwaysCrashOnOpen()) {
            throw new IllegalStateException("CarePad module lab always-crash fixture");
        }
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_INTENTIONAL_CRASH, false)) {
            throw new IllegalStateException("CarePad module lab intentional crash");
        }

        String action = getIntent() != null ? getIntent().getAction() : null;
        TextView text = new TextView(this);
        text.setGravity(Gravity.CENTER);
        text.setPadding(48, 48, 48, 48);
        text.setTextSize(20f);
        text.setText(
            "CarePad Module Lab\n\n" +
            "Protocol: " + CarePadProtocol.VERSION + "\n" +
            "Action: " + (action != null ? action : "explicit launch")
        );

        setContentView(text);
    }

    private boolean alwaysCrashOnOpen() {
        try {
            Bundle metadata = getPackageManager()
                .getActivityInfo(getComponentName(), PackageManager.GET_META_DATA)
                .metaData;
            return metadata != null && metadata.getBoolean(META_ALWAYS_CRASH, false);
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }
}
