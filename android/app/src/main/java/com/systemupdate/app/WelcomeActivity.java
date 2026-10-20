package com.systemupdate.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

public class WelcomeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { setContentView(R.layout.activity_welcome); }
        catch (Throwable t) { finish(); return; }

        Button startBtn = findViewById(R.id.welcome_start_btn);
        if (startBtn != null) {
            startBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(WelcomeActivity.this, PermissionSetupActivity.class));
                    finish();
                }
            });
        }
    }

    @Override
    public void onBackPressed() { moveTaskToBack(true); }
}
