package com.oakcraft.attendance;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app updater.
 *
 * The web app shows an "Update" bar when a newer APK is on the "latest" GitHub
 * release and opens this screen with an intent:// link. It appears as a small card
 * over the app, downloads the new APK and installs it with Android's PackageInstaller:
 *
 *  - Android 12+ : once ATTENDANCE has installed itself one time, later updates
 *                  install without any prompt (USER_ACTION_NOT_REQUIRED).
 *  - otherwise   : Android shows its "Update this app?" confirmation, once per update.
 *
 * When the new version is installed, {@link UpdatedReceiver} re-opens the app (or,
 * where Android blocks that, shows a "tap to open" notification).
 *
 * Safety: the download address is fixed at build time (never taken from the link
 * that opened this screen), and the file is only installed if it is this same app
 * with a higher version. Android also refuses an update signed with another key.
 */
public class UpdateActivity extends Activity {

    private static WeakReference<UpdateActivity> current = new WeakReference<>(null);

    private TextView status;
    private ProgressBar bar;
    private Button action;
    private Button close;

    private volatile boolean busy = false;
    private boolean waitingForPermission = false;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = new WeakReference<>(this);
        buildUi();
        // Android 13+: allow the "update installed - tap to open" notification.
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
        begin();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!busy) begin();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForPermission) {          // back from "Install unknown apps"
            waitingForPermission = false;
            begin();
        }
    }

    @Override
    protected void onDestroy() {
        if (current.get() == this) current = new WeakReference<>(null);
        super.onDestroy();
    }

    static boolean isShowing() {
        UpdateActivity a = current.get();
        return a != null && !a.isFinishing() && a.visible;
    }

    private volatile boolean visible = false;

    @Override
    protected void onStart() { super.onStart(); visible = true; }

    @Override
    protected void onStop() { visible = false; super.onStop(); }

    /** Called by {@link InstallResultReceiver}; safe when this screen is gone. */
    static void report(final String message, final boolean failed) {
        final UpdateActivity a = current.get();
        if (a == null || a.isFinishing()) return;
        a.runOnUiThread(() -> {
            a.status.setText(message);
            a.bar.setVisibility(View.GONE);
            if (failed) {
                a.busy = false;
                a.showAction("Try again", v -> a.begin());
                a.close.setText("Close");
            }
        });
    }

    // ------------------------------------------------------------------- UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        if (primary) {
            bg.setColor(Color.WHITE);
            b.setTextColor(Color.parseColor("#0057D9"));
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(dp(1), Color.parseColor("#80FFFFFF"));
            b.setTextColor(Color.WHITE);
        }
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.setMargins(dp(4), dp(14), dp(4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void buildUi() {
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.parseColor("#66000000")));

        FrameLayout root = new FrameLayout(this);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#0061F2"), Color.parseColor("#0A9BE0"), Color.parseColor("#0FD6C2")});
        bg.setCornerRadius(dp(22));
        card.setBackground(bg);
        card.setElevation(dp(12));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        head.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText("Updating ATTENDANCE");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        texts.addView(title);
        status = new TextView(this);
        status.setTextColor(Color.parseColor("#E6FFFFFF"));
        status.setTextSize(13.5f);
        texts.addView(status);
        head.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(head);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        bp.topMargin = dp(14);
        card.addView(bar, bp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        close = button("Hide", false);
        close.setOnClickListener(v -> finish());   // the download/installation carries on
        action = button("", true);
        action.setVisibility(View.GONE);
        row.addView(close);
        row.addView(action);
        card.addView(row);

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        cp.setMargins(dp(14), 0, dp(14), dp(28));
        root.addView(card, cp);
        setContentView(root);
    }

    private void showAction(String text, View.OnClickListener l) {
        action.setText(text);
        action.setOnClickListener(l);
        action.setVisibility(View.VISIBLE);
    }

    private void fail(String msg) {
        runOnUiThread(() -> {
            busy = false;
            status.setText(msg);
            bar.setVisibility(View.GONE);
            showAction("Try again", v -> begin());
            close.setText("Close");
        });
    }

    // ----------------------------------------------------------------- flow

    private void begin() {
        if (busy) return;
        // One-time Android switch: "Allow from this source" for ATTENDANCE.
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            bar.setVisibility(View.GONE);
            status.setText("One-time step: turn on \"Allow from this source\" for ATTENDANCE, then come back.");
            showAction("Allow", v -> {
                waitingForPermission = true;
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                }
            });
            return;
        }
        busy = true;
        action.setVisibility(View.GONE);
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        close.setText("Hide");
        status.setText("Downloading the latest version…");
        final Context app = getApplicationContext();
        final String address = getString(R.string.update_apk_url);
        new Thread(() -> run(app, address)).start();
    }

    private void run(Context app, String address) {
        File dir = new File(app.getCacheDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File apk = new File(dir, "ATTENDANCE-update.apk");
        File part = new File(dir, "ATTENDANCE-update.apk.part");
        HttpURLConnection c = null;
        try {
            // ---- download (follow GitHub's redirects by hand, https only)
            URL url = new URL(address);
            boolean ok = false;
            for (int hop = 0; hop < 6; hop++) {
                c = (HttpURLConnection) url.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "ATTENDANCE-updater");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null) throw new Exception("bad redirect");
                    url = new URL(url, loc);
                    if (!"https".equals(url.getProtocol())) throw new Exception("insecure redirect");
                    continue;
                }
                if (code != 200) throw new Exception("HTTP " + code);
                ok = true;
                break;
            }
            if (!ok) throw new Exception("too many redirects");
            long len = -1;
            try { len = Long.parseLong(c.getHeaderField("Content-Length")); } catch (Exception ignored) { }
            final long total = len;
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n, last = -1;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        final int pct = (int) (done * 100 / total);
                        if (pct != last) {
                            last = pct;
                            ui(() -> {
                                bar.setIndeterminate(false);
                                bar.setProgress(pct);
                                status.setText("Downloading… " + pct + "%");
                            });
                        }
                    }
                }
            }
            c.disconnect();
            c = null;
            if (apk.exists()) //noinspection ResultOfMethodCallIgnored
                apk.delete();
            if (!part.renameTo(apk)) throw new Exception("could not save the file");

            // ---- check it is a newer ATTENDANCE
            PackageManager pm = app.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (info == null || !app.getPackageName().equals(info.packageName)) {
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                throw new Exception("the downloaded file is not an ATTENDANCE update");
            }
            long installed = versionOf(pm.getPackageInfo(app.getPackageName(), 0));
            if (versionOf(info) <= installed) {
                ui(() -> {
                    busy = false;
                    bar.setVisibility(View.GONE);
                    status.setText("You already have the latest version.");
                    close.setText("Close");
                });
                return;
            }
            final String name = info.versionName;
            ui(() -> {
                bar.setIndeterminate(true);
                status.setText("Installing version " + name + "…");
            });

            // ---- install with PackageInstaller
            install(app, apk);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            fail("Update failed: " + e.getMessage() + ". Check your internet and try again.");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void ui(Runnable r) {
        if (!isFinishing()) runOnUiThread(r);
    }

    @SuppressWarnings("deprecation")
    private static long versionOf(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    private static void install(Context app, File apk) throws Exception {
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) {
            // No prompt when ATTENDANCE is updating itself (after its first self-install).
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent cb = new Intent(app, InstallResultReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;   // installer adds the result
            PendingIntent pi = PendingIntent.getBroadcast(app, id, cb, flags);
            session.commit(pi.getIntentSender());
        }
    }
}
