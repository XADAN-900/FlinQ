package com.xadan.flinq;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Giriş yapmış kullanıcı (web'deki Firebase Auth oturumu) ve bildirim tercihleri.
 * Uygulama kapalıyken bildirimleri dinleyebilmek için oturumun yenileme anahtarı saklanır
 * (Firebase SDK'nın da yaptığı gibi, yalnızca uygulamanın kendi özel alanında).
 */
final class Session {
    final String uid;
    final String refreshToken;
    final boolean enabled;
    final boolean sound;
    final boolean preview;

    private Session(SharedPreferences s, SharedPreferences p) {
        uid = s.getString("uid", null);
        refreshToken = s.getString("refresh", null);
        enabled = p.getBoolean("enabled", true);
        sound = p.getBoolean("sound", true);
        preview = p.getBoolean("preview", true);
    }

    boolean valid() {
        return uid != null && !uid.isEmpty() && refreshToken != null && !refreshToken.isEmpty();
    }

    private static SharedPreferences s(Context c) {
        return c.getApplicationContext().getSharedPreferences("session", Context.MODE_PRIVATE);
    }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("notif_prefs", Context.MODE_PRIVATE);
    }

    static Session load(Context c) {
        return new Session(s(c), p(c));
    }

    /** true dönerse oturum değişti (yeni kullanıcı ya da yeni anahtar). */
    static boolean save(Context c, String uid, String refreshToken) {
        Session old = load(c);
        boolean changed = old.uid == null || !old.uid.equals(uid)
                || old.refreshToken == null || !old.refreshToken.equals(refreshToken);
        if (changed) {
            SharedPreferences.Editor e = s(c).edit().putString("uid", uid).putString("refresh", refreshToken);
            if (old.uid == null || !old.uid.equals(uid)) e.putLong("last_time", 0);
            e.apply();
        }
        return changed;
    }

    /** Arka planda yenilenen anahtarı günceller (Google bazen yeni anahtar döner). */
    static void updateRefresh(Context c, String refreshToken) {
        if (refreshToken != null && !refreshToken.isEmpty()) {
            s(c).edit().putString("refresh", refreshToken).apply();
        }
    }

    static void clear(Context c) {
        s(c).edit().clear().apply();
    }

    static void savePrefs(Context c, boolean enabled, boolean sound, boolean preview) {
        p(c).edit().putBoolean("enabled", enabled).putBoolean("sound", sound)
                .putBoolean("preview", preview).apply();
    }

    /** Bildirimi gösterilmiş en yeni kaydın zamanı (tekrar gösterilmesin diye). */
    static long lastTime(Context c) {
        return s(c).getLong("last_time", 0);
    }

    static void setLastTime(Context c, long t) {
        if (t > lastTime(c)) s(c).edit().putLong("last_time", t).apply();
    }

    static boolean askedOnce(Context c, String key) {
        SharedPreferences sp = p(c);
        if (sp.getBoolean("asked_" + key, false)) return true;
        sp.edit().putBoolean("asked_" + key, true).apply();
        return false;
    }
}
