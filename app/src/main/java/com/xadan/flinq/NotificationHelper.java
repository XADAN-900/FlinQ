package com.xadan.flinq;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Beğeni, yorum, takip, yanıt ve etiket bildirimleri (sesli, kilit ekranı destekli). */
final class NotificationHelper {

    // Kanal sesi oluşturulduktan sonra değiştirilemediği için yeni kimlikler
    static final String CH_NOTIF = "notifs_v2";          // FlinQ bildirim sesi + titreşim
    static final String CH_NOTIF_QUIET = "notifs_quiet_v2"; // ses kapalıyken
    static final String CH_SERVICE = "service_v1";
    private static final String CH_OLD = "notifs_v1";

    static final int ID_SERVICE = 1;
    private static final int ID_SUMMARY = 1999;
    private static final String GROUP = "flinq_notifs";
    private static final int BRAND_COLOR = 0xFFFF5A5F;
    private static final long[] VIBRATION = {0, 150, 100, 150};

    static final String ACTION_MARK_READ = "com.xadan.flinq.MARK_READ";
    static final String ACTION_OPEN_NOTIFS = "com.xadan.flinq.OPEN_NOTIFS";
    static final String EXTRA_NOTIF_ID = "notif_id";
    static final String EXTRA_POST_ID = "post_id";

    private static final Map<String, Integer> posted = new HashMap<>(); // notifId -> bildirim id
    private static final Set<String> seen = new HashSet<>();
    private static final Map<String, Icon> avatarCache = new HashMap<>();
    private static final Pattern STRONG = Pattern.compile("<strong>(.*?)</strong>");

    private NotificationHelper() {
    }

    private static NotificationManager nm(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    static Uri sound(Context c) {
        return Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + c.getPackageName() + "/" + R.raw.flinq_notify);
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = nm(c);
        try {
            nm.deleteNotificationChannel(CH_OLD);
        } catch (Exception ignored) {
        }
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        NotificationChannel ch = new NotificationChannel(CH_NOTIF, "Bildirimler", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Beğeni, yorum, takip, yanıt ve etiketler (sesli)");
        ch.setSound(sound(c), aa);
        ch.enableVibration(true);
        ch.setVibrationPattern(VIBRATION);
        ch.enableLights(true);
        ch.setLightColor(BRAND_COLOR);
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);

        NotificationChannel quiet = new NotificationChannel(CH_NOTIF_QUIET, "Bildirimler (sessiz)",
                NotificationManager.IMPORTANCE_HIGH);
        quiet.setDescription("Ayarlar'da bildirim sesi kapalıyken kullanılır");
        quiet.setSound(null, null);
        quiet.enableVibration(true);
        quiet.setVibrationPattern(VIBRATION);
        quiet.setShowBadge(true);
        nm.createNotificationChannel(quiet);

        NotificationChannel service = new NotificationChannel(CH_SERVICE, "Arka plan bağlantısı",
                NotificationManager.IMPORTANCE_LOW);
        service.setDescription("Uygulama kapalıyken bildirimleri almak için");
        service.setShowBadge(false);
        nm.createNotificationChannel(service);
    }

    private static Notification.Builder builder(Context c, String ch) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(c, ch);
        return new Notification.Builder(c);
    }

    private static PendingIntent openNotifications(Context c, int req, String postId) {
        Intent i = new Intent(c, MainActivity.class)
                .setAction(ACTION_OPEN_NOTIFS)
                .putExtra(EXTRA_POST_ID, postId == null ? "" : postId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Notification serviceNotification(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = builder(c, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("FlinQ")
                .setContentText("Bildirimler için bağlı")
                .setContentIntent(pi)
                .setOngoing(true)
                .setColor(BRAND_COLOR)
                .setVisibility(Notification.VISIBILITY_SECRET);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MIN);
        return b.build();
    }

    private static String titleFor(String type) {
        if (type == null) return "FlinQ";
        switch (type) {
            case "like": return "Yeni beğeni ❤️";
            case "comment": return "Yeni yorum 💬";
            case "follow": return "Yeni takipçi 👤";
            case "reply": return "Yeni yanıt ↩️";
            case "mention": return "Etiketlendin @";
            case "milestone": return "Tebrikler! 🎉";
            default: return "FlinQ";
        }
    }

    /** "<strong>ali</strong> gönderini beğendi" -> "ali gönderini beğendi" */
    static String plain(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]*>", "")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&").trim();
    }

    /** Bildirimi tetikleyen kişinin adı (mesajdaki ilk <strong>). */
    private static String actor(String html) {
        if (html == null) return null;
        Matcher m = STRONG.matcher(html);
        if (!m.find()) return null;
        String n = plain(m.group(1));
        return n.isEmpty() || n.contains(" ") ? null : n;
    }

    private static final int[] AVATAR_COLORS = {
            0xFFFF5A5F, 0xFFFF8A00, 0xFF8B5CF6, 0xFF0EA5E9, 0xFF10B981, 0xFFEC4899, 0xFFF59E0B, 0xFF14B8A6
    };

    private static Icon avatar(String name) {
        if (name == null) return null;
        Icon cached = avatarCache.get(name);
        if (cached != null) return cached;
        try {
            int size = 128;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bmp);
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(AVATAR_COLORS[Math.abs(name.hashCode()) % AVATAR_COLORS.length]);
            cv.drawCircle(size / 2f, size / 2f, size / 2f, bg);
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(0xFFFFFFFF);
            tp.setTextSize(size * 0.48f);
            tp.setFakeBoldText(true);
            tp.setTextAlign(Paint.Align.CENTER);
            String letter = name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(new Locale("tr", "TR"));
            Rect r = new Rect();
            tp.getTextBounds(letter, 0, letter.length(), r);
            cv.drawText(letter, size / 2f, size / 2f + r.height() / 2f, tp);
            Icon icon = Icon.createWithBitmap(bmp);
            if (avatarCache.size() > 60) avatarCache.clear();
            avatarCache.put(name, icon);
            return icon;
        } catch (Exception e) {
            return null;
        }
    }

    private static int idFor(String notifId) {
        return 3000 + Math.abs(notifId.hashCode() % 20000);
    }

    /** Eski imza (FCM servisi için). */
    static void show(Context c, String notifId, String type, String message) {
        show(c, notifId, type, message, null, System.currentTimeMillis());
    }

    static void show(Context c, String notifId, String type, String message, String postId, long when) {
        if (notifId == null) notifId = String.valueOf(System.nanoTime());
        synchronized (seen) {
            if (seen.size() > 300) seen.clear();
            if (!seen.add(notifId)) return; // aynı bildirim iki kez gösterilmesin (FCM + canlı dinleyici)
        }
        createChannel(c);
        Session s = Session.load(c);
        String channel = s.sound ? CH_NOTIF : CH_NOTIF_QUIET;
        int id = idFor(notifId);
        String text = plain(message);
        String title = titleFor(type);

        Intent readI = new Intent(c, NotifActionReceiver.class)
                .setAction(ACTION_MARK_READ)
                .putExtra(EXTRA_NOTIF_ID, notifId);
        PendingIntent readPI = PendingIntent.getBroadcast(c, id, readI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action.Builder readB = new Notification.Action.Builder(
                Icon.createWithResource(c, R.drawable.ic_notification), "Okundu", readPI);
        if (Build.VERSION.SDK_INT >= 28) readB.setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ);

        Notification.Action.Builder openB = new Notification.Action.Builder(
                Icon.createWithResource(c, R.drawable.ic_notification), "Görüntüle",
                openNotifications(c, id + 1, postId));

        // Kilit ekranında içerik gizliyse görünen sade sürüm
        Notification publicVersion = builder(c, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("FlinQ")
                .setContentText("Yeni bildirim")
                .setColor(BRAND_COLOR)
                .build();

        Notification.Builder b = builder(c, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openNotifications(c, id + 2, postId))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_SOCIAL)
                .setColor(BRAND_COLOR)
                .setGroup(GROUP)
                .setShowWhen(true)
                .setWhen(when > 0 ? when : System.currentTimeMillis())
                .setVisibility(s.preview ? Notification.VISIBILITY_PUBLIC : Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .addAction(openB.build())
                .addAction(readB.build());
        Icon av = avatar(actor(message));
        if (av != null) b.setLargeIcon(av);
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_HIGH).setVibrate(VIBRATION).setLights(BRAND_COLOR, 600, 2000);
            if (s.sound) b.setSound(sound(c), AudioManager.STREAM_NOTIFICATION);
        }

        int count;
        synchronized (posted) {
            posted.put(notifId, id);
            count = posted.size();
        }
        Notification.Builder summary = builder(c, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("FlinQ")
                .setContentText(count > 1 ? count + " yeni bildirim" : "Yeni bildirim")
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setNumber(count)
                .setColor(BRAND_COLOR)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openNotifications(c, 0, null));
        if (Build.VERSION.SDK_INT >= 26) {
            summary.setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN);
        }

        try {
            NotificationManager nm = nm(c);
            nm.notify(id, b.build());
            nm.notify(ID_SUMMARY, summary.build());
        } catch (Exception ignored) {
        }
    }

    /** Tek bir bildirimi kaldır (okundu / silindi). */
    static void cancel(Context c, String notifId) {
        boolean empty;
        synchronized (posted) {
            Integer id = posted.remove(notifId);
            nm(c).cancel(id != null ? id : idFor(notifId));
            empty = posted.isEmpty();
        }
        if (empty) nm(c).cancel(ID_SUMMARY);
    }

    /** Uygulama açılınca bildirim çekmecesini temizle. */
    static void clearAll(Context c) {
        NotificationManager nm = nm(c);
        synchronized (posted) {
            for (int id : posted.values()) nm.cancel(id);
            posted.clear();
        }
        nm.cancel(ID_SUMMARY);
    }

    /** Ayarlar'daki "Bildirimi dene". */
    static void showTest(Context c) {
        show(c, "test-" + System.currentTimeMillis(), "like",
                "<strong>FlinQ</strong> bildirimlerin çalışıyor 🎉", null, System.currentTimeMillis());
    }
}
