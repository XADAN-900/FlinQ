package com.xadan.flinq;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/** Sunucudan (Cloud Functions) gelen FCM push'ları: uygulama kapalıyken de çalışır. */
public class FlinQMessagingService extends FirebaseMessagingService {

    static final String PREFS = "fcm";
    static final String KEY_TOKEN = "token";

    @Override
    public void onNewToken(String token) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TOKEN, token).apply();
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        Map<String, String> d = message.getData();
        if (!"notif".equals(d.get("type"))) return;
        if (MainActivity.visible) return; // uygulama açıkken sayfa kendi bildirimini gösteriyor
        NotificationHelper.show(this, d.get("notifId"), d.get("notifType"), d.get("message"));
    }
}
