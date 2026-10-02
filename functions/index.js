const { onValueCreated } = require('firebase-functions/v2/database');
const admin = require('firebase-admin');

const DB_URL = 'https://flinq-8e74c-default-rtdb.europe-west1.firebasedatabase.app';
admin.initializeApp({ databaseURL: DB_URL });
const db = admin.database();

// Fonksiyonlar veritabanıyla aynı bölgede olmalı
const OPTS = { region: 'europe-west1', instance: 'flinq-8e74c-default-rtdb' };

const DEAD = ['messaging/registration-token-not-registered', 'messaging/invalid-registration-token'];

function plain(html) {
  return String(html || '')
    .replace(/<[^>]*>/g, '')
    .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .trim();
}

// ---- Yeni bildirim (beğeni, yorum, takip, yanıt, etiket...) -> telefona push ----
exports.onNotification = onValueCreated(
  { ...OPTS, ref: '/notifications/{uid}/{notifId}' },
  async (event) => {
    const n = event.data.val();
    if (!n || n.read === true) return;
    const { uid, notifId } = event.params;

    const snap = await db.ref(`fcmTokens/${uid}`).get();
    if (!snap.exists()) return;
    const tokens = Object.keys(snap.val());
    if (!tokens.length) return;

    const res = await admin.messaging().sendEachForMulticast({
      tokens,
      data: {
        type: 'notif',
        notifId,
        notifType: String(n.type || ''),
        message: plain(n.message),
        postId: String(n.postId || ''),
      },
      android: { priority: 'high', ttl: 24 * 3600 * 1000 },
    });

    const dead = [];
    res.responses.forEach((r, i) => {
      if (!r.success && r.error && DEAD.includes(r.error.code)) dead.push(tokens[i]);
    });
    await Promise.all(dead.map((t) => db.ref(`fcmTokens/${uid}/${t}`).remove()));
  }
);

// ---- Bir token aynı anda tek hesaba ait olsun (cihazda hesap değişince eski hesaptan düşsün) ----
exports.onTokenRegistered = onValueCreated(
  { ...OPTS, ref: '/fcmTokens/{uid}/{token}' },
  async (event) => {
    const { uid, token } = event.params;
    const ownerRef = db.ref(`tokenOwners/${token}`);
    const prev = (await ownerRef.get()).val();
    if (prev && prev !== uid) {
      await db.ref(`fcmTokens/${prev}/${token}`).remove();
    }
    await ownerRef.set(uid);
  }
);
