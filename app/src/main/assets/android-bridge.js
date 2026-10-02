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
  try {
    firebase.auth().onAuthStateChanged(function () {
      lastWritten = '';
      setTimeout(syncToken, 500);
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
