/* OakCraft Attendance - service worker (app shell cache) */
var CACHE = 'oakcraft-shell-v1';
var ASSETS = ['./', './index.html', './manifest.json', './icon.svg'];

self.addEventListener('install', function(e){
  self.skipWaiting();
  e.waitUntil(caches.open(CACHE).then(function(c){ return c.addAll(ASSETS).catch(function(){}); }));
});

self.addEventListener('activate', function(e){
  e.waitUntil(
    caches.keys().then(function(keys){
      return Promise.all(keys.map(function(k){ return k === CACHE ? null : caches.delete(k); }));
    }).then(function(){ return self.clients.claim(); })
  );
});

self.addEventListener('message', function(e){
  if(e.data === 'skipWaiting') self.skipWaiting();
});

/* Same-origin GET: stale-while-revalidate (instant open, background update).
   Everything else (Google APIs, Apps Script, CDN) goes straight to network. */
self.addEventListener('fetch', function(e){
  var req = e.request;
  if(req.method !== 'GET') return;
  var url;
  try { url = new URL(req.url); } catch(err){ return; }
  if(url.origin !== self.location.origin) return;

  e.respondWith(
    caches.match(req).then(function(hit){
      var net = fetch(req).then(function(res){
        if(res && res.status === 200 && res.type === 'basic'){
          var copy = res.clone();
          caches.open(CACHE).then(function(c){ c.put(req, copy); });
        }
        return res;
      }).catch(function(){ return hit; });
      return hit || net;
    })
  );
});
