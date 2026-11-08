package com.xadan.flinq;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Uygulama arka plandayken / son uygulamalardan kapatılmışken de bildirimleri dinler
 * (NotifWatcher). Kalıcı, sessiz bir "bağlı" bildirimi gösterir.
 */
public class KeepAliveService extends Service {

    static void ensureRunning(Context c) {
        try {
            Intent i = new Intent(c, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, KeepAliveService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationHelper.createChannel(this); // kanal, bildirimden ÖNCE oluşmalı
        Notification n = NotificationHelper.serviceNotification(this);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NotificationHelper.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NotificationHelper.ID_SERVICE, n);
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Session s = Session.load(this);
        if (!s.valid() || !s.enabled) {
            NotifWatcher.get(this).stop();
            stopSelf();
            return START_NOT_STICKY;
        }
        NotifWatcher.get(this).start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        NotifWatcher.get(this).stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
