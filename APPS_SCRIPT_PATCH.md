# Apps Script patch — offline punch

The app can already save a punch when there is no signal and send it up later.
It stays switched **off** until the backend agrees to use the time the phone
recorded, because otherwise a punch taken at 9:30 and uploaded at 11:00 would go
into the sheet as 11:00 — wrong attendance, silently.

Two small changes to your `Code.gs` turn it on. Nothing else in the app changes.

---

## 1 — Tell the app you support it

Find where `init` builds its reply (the object with `profile`, `today`,
`leaveTypes`, `geofence`). Add one key:

```js
return {
  profile: profile,
  today: today,
  leaveTypes: leaveTypes,
  geofence: geofence,
  caps: { clientTime: true }        // <-- add this line
};
```

That single flag is what unlocks offline punching in the app.

## 2 — Honour the time the phone sent

Find your punch handler — the function that runs for `action === 'punch'` and
writes a row with `new Date()`. Add this helper above it:

```js
/* The app sends the moment the punch was actually taken. Use it when it is
   present and believable; fall back to arrival time otherwise. A punch is
   accepted up to 18 hours late (an overnight shift with no signal) but never
   from the future, so a wrong phone clock cannot create tomorrow's attendance. */
function ocPunchTime(payload) {
  var now = new Date();
  var raw = payload && payload.clientTime;
  if (!raw) return now;
  var t = new Date(raw);
  if (isNaN(t.getTime())) return now;
  var driftMs = now.getTime() - t.getTime();
  if (driftMs < -3 * 60 * 1000) return now;          // clock ahead  -> distrust
  if (driftMs > 18 * 60 * 60 * 1000) return now;     // absurdly old -> distrust
  return t;
}
```

Then, inside the punch handler, replace the timestamp:

```js
// var when = new Date();
var when = ocPunchTime(payload);
```

…and use `when` everywhere you were using `new Date()` for that row.

## 3 — Ignore a punch that arrives twice

The app sends a `clientId` with every punch. If the same one arrives again
(patchy signal, a retry), skip it instead of writing a second row.

Near the top of the punch handler:

```js
var cid = payload && payload.clientId;
if (cid) {
  var cache = CacheService.getScriptCache();
  if (cache.get('punch_' + cid)) {
    return { ok: true, data: { duplicate: true } };   // already recorded
  }
  cache.put('punch_' + cid, '1', 21600);              // remember for 6 hours
}
```

If you keep a `clientId` column on the punch sheet you can dedupe against that
instead, which survives longer than the cache — but the cache alone already
covers the case that actually happens.

---

## After you paste it

**Deploy → Manage deployments → ✏️ → Version: New version → Deploy.**
Keeping the same deployment keeps the same `/exec` URL, so nothing in the app or
the APK has to change.

To check it worked: open the app, turn on flight mode, punch. You should see
*"No signal — saved on this phone, it will be sent by itself"* instead of a
refusal. Turn the internet back on; within a few seconds the banner clears and
the punch appears with the time you actually pressed the button.
