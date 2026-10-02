# FlinQ Android (WebView + localhost) + FCM push

HTML uygulaması `app/src/main/assets/www/index.html`; cihazda `http://localhost:38214` üzerinden yüklenir.

- İnternet yoksa "İnternet bağlantısı yok" ekranı / kırmızı şerit.
- Açılış logosu, düzgün adaptive ikon.
- Beğeni / yorum / takip / yanıt / etiket bildirimleri uygulama kapalıyken de telefona gelir (FCM).

## Kurulum (bir kez)
1. Firebase konsol → proje `flinq-8e74c` → Android uygulaması ekle: paket adı `com.xadan.flinq`
   → `google-services.json` indir → `app/google-services.json` olarak repoya koy.
2. Projeyi **Blaze** planına al (Functions için şart, ücretsiz kotalı).
3. GitHub → Settings → Secrets: `FIREBASE_TOKEN` (`firebase login:ci`) veya `FIREBASE_SERVICE_ACCOUNT` (servis hesabı JSON).
4. Realtime Database kuralları `fcmTokens` yoluna giriş yapmış kullanıcının kendi düğümüne yazmasına izin vermeli:
   ```
   "fcmTokens": { "$uid": { ".write": "auth != null && auth.uid === $uid" } }
   ```
   (Kuralların zaten herkese açıksa gerek yok.)
5. Push et: "Build APK" APK'yı, "Deploy Functions" sunucu kısmını kurar.

Giriş yapamazsan: Google Cloud konsol → API anahtarı kısıtlamalarında `localhost` engelleniyor olabilir.
