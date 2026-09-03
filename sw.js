/* OakCraft Attendance - service worker (app shell cache) */
var CACHE = 'oakcraft-shell-v11';
var ASSETS = ['./', './index.html', './manifest.json', './icon.svg',
              './icon-192.png', './icon-512.png', './icon-maskable-512.png', './apple-touch-icon.png'];

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

/* Same-origin GET: stale-while-revalidate.
   The cached copy is served immediately, while a fresh copy is fetched from the
   server in the background and written into the cache (bypassing the HTTP cache,
   so a new deploy is picked up on the very next load).
   Google APIs / Apps Script / CDN requests always go straight to the network. */
self.addEventListener('fetch', function(e){
  var req = e.request;
  if(req.method !== 'GET') return;
  var url;
  try { url = new URL(req.url); } catch(err){ return; }
  if(url.origin !== self.location.origin) return;

  e.respondWith(
    caches.match(req).then(function(hit){
      var fresh;
      try { fresh = new Request(req.url, { cache: 'no-cache', credentials: 'same-origin' }); }
      catch(err){ fresh = req; }
      var net = fetch(fresh).then(function(res){
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
