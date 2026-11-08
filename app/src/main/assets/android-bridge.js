(function () {
  if (!window.FlinqAndroid || window.__flinqBridgeInstalled) return;
  window.__flinqBridgeInstalled = true;
  if (typeof firebase === 'undefined') return;

  // Giriş yapmış kullanıcıya bu cihazın FCM token'ını bağla (sunucu bu token'a push atar).
  // Yazma işlemini sayfa yapıyor: veritabanı kurallarına göre doğrulanmış kullanıcı olarak.
  var lastWritten = '';
  function syncToken() {
    try {
      var u = firebase.auth().currentUser;
      if (!u) return;
      var t = FlinqAndroid.getFcmToken();
      if (!t) return;
      var sig = u.uid + '|' + t;
      if (sig === lastWritten) return;
      firebase.database().ref('fcmTokens/' + u.uid + '/' + t)
        .set(firebase.database.ServerValue.TIMESTAMP)
        .then(function () { lastWritten = sig; })
        .catch(function () {});
    } catch (e) {}
  }
  syncToken();
  setInterval(syncToken, 5000);

  // Oturumu Android'e ilet: uygulama kapalıyken bildirimleri dinleyen arka plan servisi
  // bu kullanıcının bildirimlerini (yenileme anahtarıyla, kendi kimliğiyle) okur.
  var lastSession = '';
  function syncSession() {
    try {
      if (typeof FlinqAndroid.setSession !== 'function') return;
      var u = firebase.auth().currentUser;
      if (u && u.refreshToken) {
        var sig = u.uid + '|' + u.refreshToken;
        if (sig !== lastSession) {
          lastSession = sig;
          FlinqAndroid.setSession(String(u.uid), String(u.refreshToken));
        }
      } else if (!u && lastSession !== 'none' && lastSession !== '') {
        lastSession = 'none';
        FlinqAndroid.clearSession();
      }
    } catch (e) {}
  }
  syncSession();
  setInterval(syncSession, 5000);
  try {
    firebase.auth().onAuthStateChanged(function (user) {
      lastWritten = '';
      setTimeout(syncToken, 500);
      if (!user) {
        lastSession = 'none';
        try { FlinqAndroid.clearSession(); } catch (e) {}
      } else {
        setTimeout(syncSession, 300);
      }
    });
  } catch (e) {}

  // Çıkış yapılırken token'ı sil: başka biri giriş yapınca eski hesabın bildirimleri bu telefona gelmesin.
  try {
    var proto = firebase.auth.Auth.prototype;
    var origSignOut = proto.signOut;
    proto.signOut = function () {
      var self = this, args = arguments, p = Promise.resolve();
      try {
        var u = self.currentUser, t = FlinqAndroid.getFcmToken();
        if (u && t) {
          p = firebase.database().ref('fcmTokens/' + u.uid + '/' + t).remove().catch(function () {});
        }
      } catch (e) {}
      return p.then(function () { return origSignOut.apply(self, args); });
    };
  } catch (e) {}
})();
