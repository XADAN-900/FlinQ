package com.xadan.flinq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Telefon yeniden başlayınca / uygulama güncellenince bildirim dinlemeyi geri getirir. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Session s = Session.load(context);
        if (s.valid() && s.enabled) {
            NotificationHelper.createChannel(context);
            KeepAliveService.ensureRunning(context);
        }
    }
}
