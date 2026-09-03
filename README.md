# OakCraft Attendance

Punch in with a selfie and live location, leave, reports and payroll — one page,
one Google sign-in, works on any phone or laptop.

Live at **https://weboakcraft.github.io/oakcraft_attendance/**

| File | What it is |
|---|---|
| `index.html` | The whole app — HTML, CSS and JavaScript in one file, no build step |
| `sw.js` | Service worker: caches the shell and picks up new versions on the next load |
| `manifest.json`, `icon-*` | Makes it installable from the browser |
| `android/` | The Android app (a Trusted Web Activity) |
| `.well-known/assetlinks.json` | Proves the Android app owns this site — see below |
| `APPS_SCRIPT_PATCH.md` | Two small backend changes that switch on offline punching |
| `.github/workflows/android.yml` | Builds and signs the APK |

The backend is a Google Apps Script talking to a Google Sheet. Its address and
OAuth client id are in `CONFIG` at the top of the `<script>` block in `index.html`.

---

## The Android app

It is a **Trusted Web Activity**, not a WebView wrapper. That is not a style
choice: Google refuses to sign anyone in inside an embedded WebView
(`disallowed_useragent`), and both sign-in paths in this app go through Google.
A TWA runs the same site in Chrome's engine with **no address bar**, so sign-in,
the selfie camera, GPS, CSV downloads and payslip printing all behave exactly as
they do in the browser.

Build it from **Actions → Build Android APK → Run workflow**. It needs the same
four signing secrets as the stock app — `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`.

### The one thing that has to be right

Android checks that the app is allowed to own the site by fetching

```
https://weboakcraft.github.io/.well-known/assetlinks.json
```

— from the **domain root**, never from a sub-path. A GitHub Pages project site
lives under `/oakcraft_attendance/`, so the copy in this repository is *not* the
one Android reads. Publish a repository named `weboakcraft.github.io` containing
that same `.well-known/assetlinks.json` and the check passes.

Until it does, the app still works — it just opens in a Chrome tab with a visible
address bar instead of looking like a native app.

---

## What was fixed in this pass

* **Payout and Settings were unreachable on a phone.** The bottom bar showed only
  the first five screens; Admin has seven. There is now a *More* button.
* **The punch screen showed stale state.** After punching in it still said
  "Punch In", because the refresh was served from the offline cache.
* **Writes were retried three times.** A punch that timed out on the way back had
  already been recorded, so the retry created a duplicate. Only reads retry now.
* **Offline outbox.** A leave application made with no signal is saved and sent
  automatically. Punching offline stays blocked until the backend honours the
  time the phone recorded — see `APPS_SCRIPT_PATCH.md`.
* **Every failure said "Network error".** An Apps Script crash now says so.
* **CSV exports** could be broken by a comma in any field, and a name beginning
  with `=` ran as a formula when the file was opened.
* **Quotes in data broke inline click handlers** — a holiday or employee name
  containing `'` could break out of the JavaScript string.
* **Touch.** Chart and calendar tooltips only ever responded to a mouse; the
  approve/reject buttons were 28 px and unlabelled; muted text failed contrast.
* **Camera.** A selfie survived a screen change invisibly, *Retake* could strand
  you with no camera, and a too-early snap produced a broken image.
* **Service worker updates** apply by themselves: `sw.js` calls `skipWaiting()`,
  so a new build activates at once and the page reloads onto it. There is no
  "update available" banner, because there is never a waiting worker for one to
  offer. A full cache no longer wipes every screen's offline copy.
