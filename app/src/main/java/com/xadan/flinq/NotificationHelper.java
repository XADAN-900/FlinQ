package com.xadan.flinq;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.HashSet;
import java.util.Set;

/** Beğeni, yorum, takip, yanıt ve etiket bildirimleri. */
final class NotificationHelper {

    static final String CH_NOTIF = "notifs_v1";
    private static final int ID_SUMMARY = 1999;
    private static final String GROUP = "flinq_notifs";
    private static final int BRAND_COLOR = 0xFFFF5A5F;

    private static final Set<Integer> posted = new HashSet<>();
    private static final Set<String> seen = new HashSet<>();

    private NotificationHelper() {
    }

    private static NotificationManager nm(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CH_NOTIF, "Bildirimler",
                NotificationManager.IMPORTANCE_HIGH);
        ch.enableVibration(true);
        nm(c).createNotificationChannel(ch);
    }

    private static Notification.Builder builder(Context c) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(c, CH_NOTIF);
        return new Notification.Builder(c);
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String titleFor(String type) {
        if (type == null) return "FlinQ";
        switch (type) {
            case "like": return "Yeni beğeni";
            case "comment": return "Yeni yorum";
            case "follow": return "Yeni takipçi";
            case "reply": return "Yeni yanıt";
            case "mention": return "Etiketlendin";
            case "milestone": return "Tebrikler!";
            default: return "FlinQ";
        }
    }

    /** "<strong>ali</strong> gönderini beğendi" -> "ali gönderini beğendi" */
    static String plain(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]*>", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").trim();
    }

    static void show(Context c, String notifId, String type, String message) {
        if (notifId == null) notifId = String.valueOf(System.nanoTime());
        synchronized (seen) {
            if (seen.size() > 300) seen.clear();
            if (!seen.add(notifId)) return; // aynı bildirim iki kez gösterilmesin
        }
        createChannel(c);
        int id = 3000 + Math.abs(notifId.hashCode() % 20000);
        String text = plain(message);

        Notification.Builder b = builder(c)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(titleFor(type))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp(c))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_SOCIAL)
                .setColor(BRAND_COLOR)
                .setGroup(GROUP)
                .setWhen(System.currentTimeMillis());
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        }

        Notification.Builder summary = builder(c)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("FlinQ")
                .setContentText("Yeni bildirimler")
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setColor(BRAND_COLOR)
                .setContentIntent(openApp(c));
        if (Build.VERSION.SDK_INT >= 26) {
            summary.setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN);
        }

        try {
            NotificationManager nm = nm(c);
            nm.notify(id, b.build());
            nm.notify(ID_SUMMARY, summary.build());
            synchronized (posted) {
                posted.add(id);
            }
        } catch (Exception ignored) {
        }
    }

    /** Uygulama açılınca bildirim çekmecesini temizle. */
    static void clearAll(Context c) {
        NotificationManager nm = nm(c);
        synchronized (posted) {
            for (int id : posted) nm.cancel(id);
            posted.clear();
        }
        nm.cancel(ID_SUMMARY);
    }
}
