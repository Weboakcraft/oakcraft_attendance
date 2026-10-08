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

---

# Apps Script patch — strict punch security (recommended)

The app now blocks a punch on the phone unless:

* the person passed a **live face check** (random blink / head-turn / smile, with a
  3-D parallax test that a photo or another phone's screen cannot pass), and
* they are **within 100 m** of the office with GPS accuracy of **±50 m or better**,
  measured again at the moment they press Punch.

A phone-side check can be skipped by someone who calls the backend directly, so
add the same rules to `Code.gs`. Paste this helper above your punch handler:

```js
/* Server-side copy of the app's punch rules. Throw = punch refused. */
var OC_MAX_RADIUS_M = 100, OC_MAX_ACC_M = 50, OC_LIVE_MAX_AGE_MS = 3 * 60 * 1000;
var OC_DEVICE_CAPTURE_EMAILS = ['mis@oakcraft.in'];
function ocCheckPunch(payload, email, geofence, isStaff) {
  // 1 — location: hard 100 m, no allowance for GPS error
  if (geofence && geofence.lat && geofence.lng) {
    var lat = Number(payload.lat), lng = Number(payload.lng), acc = Number(payload.accuracy);
    if (!isFinite(lat) || !isFinite(lng)) throw new Error('PUNCH: Location missing');
    if (!(acc <= OC_MAX_ACC_M)) throw new Error('PUNCH: GPS accuracy must be ±' + OC_MAX_ACC_M + 'm or better');
    var R = 6371000, r = Math.PI / 180, dLa = (geofence.lat - lat) * r, dLo = (geofence.lng - lng) * r;
    var a = Math.pow(Math.sin(dLa / 2), 2) + Math.cos(lat * r) * Math.cos(geofence.lat * r) * Math.pow(Math.sin(dLo / 2), 2);
    var dist = 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    var radius = Math.min(Number(geofence.radius) || OC_MAX_RADIUS_M, OC_MAX_RADIUS_M);
    if (dist > radius) throw new Error('PUNCH: You are ' + Math.round(dist) + 'm from office — allowed ' + radius + 'm');
  }
  // 2 — live face check (Staff only — they are the ones who send a selfie)
  if (isStaff) {
    var lv = payload.liveness;
    if (!payload.photoBase64 || !lv || lv.passed !== true) throw new Error('PUNCH: Live face check required — update the app');
    var age = Date.now() - new Date(lv.at).getTime();
    if (!(age >= -60000 && age <= OC_LIVE_MAX_AGE_MS)) throw new Error('PUNCH: Live face check expired — do it again');
    if (payload.photoSource === 'device' &&
        OC_DEVICE_CAPTURE_EMAILS.indexOf(String(email).toLowerCase()) < 0)
      throw new Error('PUNCH: Device-camera photos are not allowed for this account');
  }
}
```

Call it at the top of the punch handler, after you know who is punching:

```js
ocCheckPunch(payload, email, { lat: <office lat>, lng: <office lng>, radius: <geofence_radius_m> },
             String(user.type || 'Staff').toLowerCase() === 'staff');
```

Messages starting with `PUNCH:` are shown to the employee as-is. Optionally store
`payload.liveness.steps`, `payload.photoSource` and `payload.distanceM` in extra
columns for audit. Then **Deploy → Manage deployments → ✏️ → New version → Deploy**.

**Note:** the old app version does not send `liveness`, so once this patch is live
anyone still on a cached old page is refused until it refreshes (it does so by
itself on the next open).
