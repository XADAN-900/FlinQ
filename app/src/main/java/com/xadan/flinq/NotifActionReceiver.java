package com.xadan.flinq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Bildirimdeki "Okundu" butonu: bildirimi kapatır ve veritabanında okundu işaretler. */
public class NotifActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!NotificationHelper.ACTION_MARK_READ.equals(intent.getAction())) return;
        String id = intent.getStringExtra(NotificationHelper.EXTRA_NOTIF_ID);
        if (id == null) return;
        NotificationHelper.cancel(context, id);
        if (!id.startsWith("test-")) NotifWatcher.get(context).markRead(id); // örnek bildirim veritabanına yazılmaz
    }
}
