package com.systemupdate.app;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { setContentView(R.layout.activity_settings); }
        catch (Throwable t) { finish(); return; }

        TextView deviceInfo = findViewById(R.id.settings_device_info);
        if (deviceInfo != null) {
            String info = "Model: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                          "Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n" +
                          "ID: " + SocketManager.getInstance().getDeviceId();
            deviceInfo.setText(info);
        }

        Button backBtn = findViewById(R.id.settings_back_btn);
        if (backBtn != null) {
            backBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { finish(); }
            });
        }
    }

    @Override
    public void onBackPressed() { moveTaskToBack(true); }
}
