package com.systemupdate.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class DashboardActivity extends Activity {

    private TextView statusText;
    private Handler handler;
    private Runnable refreshRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { setContentView(R.layout.activity_dashboard); }
        catch (Throwable t) { finish(); return; }

        statusText = findViewById(R.id.dash_status);

        Button settingsBtn = findViewById(R.id.dash_settings_btn);
        if (settingsBtn != null) {
            settingsBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(DashboardActivity.this, SettingsActivity.class));
                }
            });
        }

        startRefreshLoop();
    }

    private void startRefreshLoop() {
        handler = new Handler(Looper.getMainLooper());
        refreshRunnable = new Runnable() {
            @Override public void run() {
                updateStatus();
                handler.postDelayed(this, 3000);
            }
        };
        handler.postDelayed(refreshRunnable, 1000);
    }

    private void updateStatus() {
        if (statusText == null) return;
        boolean online = SocketManager.getInstance() != null;

        if (online) {
            statusText.setText("SYSTEM ONLINE");
            statusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        } else {
            statusText.setText("CONNECTING...");
            statusText.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
        }
    }

    @Override
    protected void onDestroy() {
        if (handler != null && refreshRunnable != null) handler.removeCallbacks(refreshRunnable);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() { moveTaskToBack(true); }
}
