package com.xadan.flinq;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Uygulama kapalıyken de (arka plan servisi çalıştığı sürece) kullanıcının bildirimlerini
 * Firebase'den canlı dinler ve telefon bildirimi gösterir.
 *
 * Eskiden bildirimler yalnızca FCM ile gelecekti; ancak google-services.json ve sunucu
 * fonksiyonları kurulu olmadığı için uygulama kapalıyken hiçbir bildirim gelmiyordu.
 * Bu sınıf Realtime Database'in REST "stream" (Server-Sent Events) özelliğini kullanır,
 * kimlik doğrulaması için web oturumunun yenileme anahtarından kısa ömürlü bir kimlik
 * jetonu üretir.
 */
final class NotifWatcher {

    static final String DB_URL = "https://flinq-8e74c-default-rtdb.europe-west1.firebasedatabase.app";
    private static final String API_KEY = "AIzaSyDdSWxFYZ8YsHFAk-NMpvVPNTdf-QyWvx4";
    /** API anahtarı yalnızca bu adresten gelen isteklere izinliyse diye web ile aynı kaynağı bildir. */
    private static final String REFERER = "http://localhost:38214/";
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000; // daha eski okunmamışları bildirme

    private static NotifWatcher instance;

    static synchronized NotifWatcher get(Context c) {
        if (instance == null) instance = new NotifWatcher(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private volatile Thread thread;
    private volatile HttpURLConnection conn;
    private volatile int generation = 0;

    private volatile String idToken;
    private volatile long idTokenExpiry = 0;
    private String tokenUid;

    private final Set<String> seen = Collections.synchronizedSet(new HashSet<>());

    private NotifWatcher(Context c) {
        this.ctx = c;
    }

    /** Oturuma göre başlatır; zaten aynı oturumla çalışıyorsa dokunmaz. */
    synchronized void start() {
        Session s = Session.load(ctx);
        if (!s.valid()) {
            stop();
            return;
        }
        if (thread != null && thread.isAlive() && s.uid.equals(tokenUid)) return;
        stop();
        final int gen = ++generation;
        tokenUid = s.uid;
        idToken = null;
        idTokenExpiry = 0;
        seen.clear();
        Thread t = new Thread(() -> loop(gen), "flinq-notif-watch");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** Oturum değişti (yeni anahtar): bağlantıyı yeniden kur. */
    synchronized void restart() {
        stop();
        start();
    }

    synchronized void stop() {
        generation++;
        HttpURLConnection c = conn;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
        conn = null;
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
    }

    // ---------- ana döngü ----------

    private void loop(int gen) {
        long backoff = 3000;
        while (gen == generation) {
            Session s = Session.load(ctx);
            if (!s.valid()) return;
            try {
                if (!ensureToken(s)) {
                    // anahtar geçersiz (şifre değişti / oturum kapandı): uygulama tekrar açılınca yenilenir
                    return;
                }
                stream(gen, s.uid);
                backoff = 3000;
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // ağ hatası: bekleyip yeniden dene
            }
            if (gen != generation) return;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                return;
            }
            backoff = Math.min(backoff * 2, 60_000);
        }
    }

    /** Geçerli bir kimlik jetonu yoksa yenileme anahtarıyla alır. false = anahtar artık geçersiz. */
    private final Object tokenLock = new Object();

    private boolean ensureToken(Session s) throws Exception {
        synchronized (tokenLock) {
            return ensureTokenLocked(s);
        }
    }

    private boolean ensureTokenLocked(Session s) throws Exception {
        if (idToken != null && System.currentTimeMillis() < idTokenExpiry - 120_000) return true;
        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://securetoken.googleapis.com/v1/token?key=" + API_KEY).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("Referer", REFERER);
            String body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(s.refreshToken, "UTF-8");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            if (code == 400 || code == 401) return false;
            if (code != 200) throw new Exception("token " + code);
            JSONObject o = new JSONObject(read(c));
            idToken = o.getString("id_token");
            long expiresIn = o.optLong("expires_in", 3600);
            idTokenExpiry = System.currentTimeMillis() + expiresIn * 1000;
            String newRefresh = o.optString("refresh_token", null);
            if (newRefresh != null && !newRefresh.equals(s.refreshToken)) Session.updateRefresh(ctx, newRefresh);
            return true;
        } finally {
            c.disconnect();
        }
    }

    private void stream(int gen, String uid) throws Exception {
        URL url = new URL(DB_URL + "/notifications/" + uid + ".json?auth=" + URLEncoder.encode(idToken, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        conn = c;
        c.setRequestProperty("Accept", "text/event-stream");
        c.setConnectTimeout(15000);
        c.setReadTimeout(90_000); // sunucu ~30 sn'de bir keep-alive gönderir
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code == 401 || code == 403) {
                idToken = null;
                throw new Exception("auth " + code);
            }
            if (code != 200) throw new Exception("stream " + code);
            boolean first = true;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String event = null;
                String line;
                while (gen == generation && (line = br.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        if ("cancel".equals(event) || "auth_revoked".equals(event)) {
                            idToken = null;
                            return; // yeni jetonla yeniden bağlan
                        }
                        if ("put".equals(event) || "patch".equals(event)) {
                            handle(event, data, first);
                            if ("put".equals(event)) first = false;
                        }
                    }
                    // jetonun süresi dolmak üzereyse bağlantıyı yenile
                    if (System.currentTimeMillis() > idTokenExpiry - 120_000) return;
                }
            }
        } finally {
            conn = null;
            c.disconnect();
        }
    }

    // ---------- olayları işle ----------

    private void handle(String event, String data, boolean initial) {
        try {
            JSONObject o = new JSONObject(data);
            String path = o.optString("path", "/");
            Object d = o.opt("data");

            if ("/".equals(path)) {
                if (!(d instanceof JSONObject)) {
                    if ("put".equals(event)) NotificationHelper.clearAll(ctx);
                    return;
                }
                JSONObject all = (JSONObject) d;
                if (initial && "put".equals(event)) {
                    initialSnapshot(all);
                } else {
                    Iterator<String> it = all.keys();
                    while (it.hasNext()) {
                        String id = it.next();
                        Object v = all.opt(id);
                        if (v instanceof JSONObject) onNotif(id, (JSONObject) v, "patch".equals(event));
                        else if (v == null || v == JSONObject.NULL) NotificationHelper.cancel(ctx, id);
                    }
                }
                return;
            }

            String[] parts = path.substring(1).split("/");
            String id = parts[0];
            if (parts.length == 1) {
                if (d instanceof JSONObject) onNotif(id, (JSONObject) d, "patch".equals(event));
                else NotificationHelper.cancel(ctx, id); // silindi
            } else if ("read".equals(parts[1]) && Boolean.TRUE.equals(d)) {
                NotificationHelper.cancel(ctx, id); // başka cihazdan / uygulamadan okundu
            }
        } catch (Exception ignored) {
        }
    }

    /** İlk bağlantı: mevcut bildirimleri "görüldü" say, sadece arada kaçırılan yenileri göster. */
    private void initialSnapshot(JSONObject all) {
        long last = Session.lastTime(ctx);
        long now = System.currentTimeMillis();
        List<JSONObject> fresh = new ArrayList<>();
        List<String> freshIds = new ArrayList<>();
        Iterator<String> it = all.keys();
        long max = last;
        while (it.hasNext()) {
            String id = it.next();
            JSONObject n = all.optJSONObject(id);
            seen.add(id);
            if (n == null) continue;
            long t = n.optLong("time", 0);
            if (t > max) max = t;
            // ilk kurulumda (last==0) geçmiş bildirimleri yağdırma
            if (last > 0 && !n.optBoolean("read", false) && t > last && now - t < MAX_AGE_MS) {
                fresh.add(n);
                freshIds.add(id);
            }
        }
        // en fazla son 5 kaçırılmış bildirimi göster
        int from = Math.max(0, fresh.size() - 5);
        for (int i = from; i < fresh.size(); i++) {
            JSONObject n = fresh.get(i);
            NotificationHelper.show(ctx, freshIds.get(i), n.optString("type"), n.optString("message"),
                    n.optString("postId"), n.optLong("time", now));
        }
        if (max == 0) max = now;
        Session.setLastTime(ctx, max);
    }

    private void onNotif(String id, JSONObject n, boolean partial) {
        if (n.optBoolean("read", false)) {
            NotificationHelper.cancel(ctx, id);
            return;
        }
        if (partial && !n.has("message")) return; // sadece bir alan güncellendi
        if (!seen.add(id)) return;
        long t = n.optLong("time", System.currentTimeMillis());
        Session.setLastTime(ctx, t);
        Session s = Session.load(ctx);
        if (!s.enabled || MainActivity.visible) return; // uygulama açıkken sayfa kendisi gösteriyor
        NotificationHelper.show(ctx, id, n.optString("type"), n.optString("message"), n.optString("postId"), t);
    }

    // ---------- "Okundu" butonu ----------

    void markRead(final String notifId) {
        new Thread(() -> {
            Session s = Session.load(ctx);
            if (!s.valid()) return;
            HttpURLConnection c = null;
            try {
                if (!ensureToken(s)) return;
                c = (HttpURLConnection) new URL(DB_URL + "/notifications/" + s.uid + "/" + notifId
                        + "/read.json?auth=" + URLEncoder.encode(idToken, "UTF-8")).openConnection();
                c.setRequestMethod("PUT");
                c.setDoOutput(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                try (OutputStream os = c.getOutputStream()) {
                    os.write("true".getBytes("UTF-8"));
                }
                c.getResponseCode();
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }, "flinq-mark-read").start();
    }

    private static String read(HttpURLConnection c) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
