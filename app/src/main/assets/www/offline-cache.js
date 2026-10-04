// ============================================================================
// ÇEVRİMDIŞI ÖNBELLEK (IndexedDB)
// Akış (feed) ve yorumlar ekrana çizildikten sonra HTML olarak cihaza kaydedilir.
// İnternet yokken aynı HTML geri yüklenir; böylece eski gönderiler okunabilir.
// ============================================================================
(function () {
    const DB_NAME = 'flinq-offline';
    const STORE = 'kv';
    const MAX_COMMENT_PAGES = 40;   // en fazla kaç gönderinin yorumları saklansın

    let dbPromise = null;
    function openDb() {
        if (dbPromise) return dbPromise;
        dbPromise = new Promise((resolve, reject) => {
            try {
                const req = indexedDB.open(DB_NAME, 1);
                req.onupgradeneeded = () => req.result.createObjectStore(STORE);
                req.onsuccess = () => resolve(req.result);
                req.onerror = () => reject(req.error);
            } catch (e) { reject(e); }
        });
        return dbPromise;
    }
    function idb(mode, fn) {
        return openDb().then(db => new Promise((resolve) => {
            try {
                const tx = db.transaction(STORE, mode);
                const r = fn(tx.objectStore(STORE));
                tx.oncomplete = () => resolve(r && r.result);
                tx.onerror = () => resolve(undefined);
                tx.onabort = () => resolve(undefined);
            } catch (e) { resolve(undefined); }
        })).catch(() => undefined);
    }

    function uid() {
        try { return (typeof currentUser !== 'undefined' && currentUser && currentUser.uid) || null; } catch (e) { return null; }
    }
    function key(name) { const u = uid(); return u ? (u + '::' + name) : null; }

    function put(name, value) {
        const k = key(name);
        if (!k) return Promise.resolve(false);
        return idb('readwrite', s => s.put(value, k)).then(() => true);
    }
    function get(name) {
        const k = key(name);
        if (!k) return Promise.resolve(undefined);
        return idb('readonly', s => s.get(k));
    }

    // Yorumlar: { postId: html } tek kayıtta, en yeni MAX_COMMENT_PAGES tanesi
    async function putComments(postId, html) {
        const all = (await get('comments')) || { order: [], pages: {} };
        all.order = all.order.filter(id => id !== postId);
        all.order.push(postId);
        all.pages[postId] = html;
        while (all.order.length > MAX_COMMENT_PAGES) {
            const old = all.order.shift();
            delete all.pages[old];
        }
        return put('comments', all);
    }
    async function getComments(postId) {
        const all = await get('comments');
        return all && all.pages ? all.pages[postId] : undefined;
    }

    function clearAll() {
        return idb('readwrite', s => s.clear());
    }

    // Android tarafı (MainActivity) gerçek internet kontrolünü yapıp __ffOffline'ı set eder.
    window.__ffOffline = false;
    window.isOfflineNow = function () {
        return window.__ffOffline === true || navigator.onLine === false;
    };

    // Firebase sorgusu takılırsa (internet yok) sonsuza kadar bekleme
    window.withTimeout = function (promise, ms) {
        return Promise.race([
            promise,
            new Promise((_, reject) => setTimeout(() => reject(new Error('timeout')), ms || 7000))
        ]);
    };

    window.FFCache = { put, get, putComments, getComments, clearAll };
})();

// ============================================================================
// KIRMIZI "İNTERNET BAĞLANTISI YOK" ŞERİDİ
// Her ekranda, internet yokken en üstte görünür. Sadece tek satır yazı var.
// Android (MainActivity) da aynı şeridi koyar; aynı id kullanıldığı için çift olmaz.
// ============================================================================
(function () {
    const ID = 'ff-offline-banner';
    function build() {
        const e = document.createElement('div');
        e.id = ID;
        e.textContent = 'İnternet bağlantısı yok';
        e.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:2147483647;' +
            'background:#d32f2f;color:#fff;text-align:center;font:600 14px sans-serif;' +
            'padding:8px;padding-top:max(8px,env(safe-area-inset-top));pointer-events:none';
        return e;
    }
    function update() {
        if (!document.body) return;
        const el = document.getElementById(ID);
        if (window.isOfflineNow()) {
            if (!el) document.body.appendChild(build());
        } else if (el) {
            el.remove();
        }
    }
    window.addEventListener('offline', update);
    window.addEventListener('online', update);
    document.addEventListener('DOMContentLoaded', update);
    setInterval(update, 2000);
    update();
})();
