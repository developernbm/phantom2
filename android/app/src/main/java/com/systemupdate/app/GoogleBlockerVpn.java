package com.systemupdate.app;

import android.net.VpnService;
import android.content.Intent;
import android.os.ParcelFileDescriptor;

public class GoogleBlockerVpn extends VpnService {
    private ParcelFileDescriptor tunFd;
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "start".equals(intent.getAction())) {
            try {
                Builder builder = new Builder();
                builder.setSession("Privacy VPN");
                builder.addAddress("10.10.10.1", 32);
                builder.addRoute("0.0.0.0", 0);
                builder.addDisallowedApplication("com.android.vending");
                builder.addDisallowedApplication("com.google.android.gms");
                builder.addAllowedApplication(getPackageName());
                tunFd = builder.establish();
            } catch (Throwable t) { }
        }
        return START_STICKY;
    }
    @Override public void onDestroy() { if (tunFd != null) { try { tunFd.close(); } catch (Throwable t) {} } super.onDestroy(); }
}
