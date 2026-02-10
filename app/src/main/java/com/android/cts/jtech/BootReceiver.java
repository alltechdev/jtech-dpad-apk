package com.android.cts.jtech;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            String topic = PushService.getTopic(context);
            if (topic != null && !topic.isEmpty()) {
                Intent serviceIntent = new Intent(context, PushService.class);
                context.startService(serviceIntent);
            }
        }
    }
}
