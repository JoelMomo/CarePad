package dev.carepad.fixture.emulator;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class FakeEmulatorActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView textView = new TextView(this);
        textView.setText("CarePad performance emulator fixture");
        textView.setTextSize(24f);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        textView.setPadding(padding, padding, padding, padding);
        setContentView(textView);
    }
}
