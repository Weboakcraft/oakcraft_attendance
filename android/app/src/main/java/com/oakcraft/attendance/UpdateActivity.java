package com.oakcraft.attendance;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app updater.
 *
 * The web app shows an "Update" button when a newer APK is on the "latest" GitHub
 * release, and opens this screen through an intent:// link. This screen downloads
 * that APK and hands it to Android's installer, which asks the user to confirm.
 *
 * Safety: the download address is fixed at build time (it never comes from the
 * link that opened this screen), and the file is only offered for install if it
 * is this same app with a higher version. Android itself also refuses an update
 * that is not signed with the same key as the installed app.
 */
public class UpdateActivity extends Activity {

    private static final String APK_MIME = "application/vnd.android.package-archive";

    private TextView status;
    private ProgressBar bar;
    private Button action;
    private Button close;

    private volatile boolean downloading = false;
    private File apkFile;
    private boolean waitingForPermission = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        startDownload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the "Install unknown apps" settings screen.
        if (waitingForPermission && apkFile != null && apkFile.exists()) {
            waitingForPermission = false;
            install();
        }
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        if (primary) {
            bg.setColor(Color.WHITE);
            b.setTextColor(Color.parseColor("#0057D9"));
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(dp(1), Color.parseColor("#66FFFFFF"));
            b.setTextColor(Color.WHITE);
        }
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#0061F2"), Color.parseColor("#0A86E0"), Color.parseColor("#0FD6C2")});
        root.setBackground(bg);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(96), dp(96));
        ip.bottomMargin = dp(18);
        root.addView(icon, ip);

        TextView title = new TextView(this);
        title.setText("Updating ATTENDANCE");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#E6FFFFFF"));
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(10);
        sp.bottomMargin = dp(18);
        root.addView(status, sp);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)));

        action = button("", true);
        action.setVisibility(View.GONE);
        root.addView(action);

        close = button("Cancel", false);
        close.setOnClickListener(v -> finish());
        root.addView(close);

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Color.parseColor("#0061F2"));
            getWindow().setNavigationBarColor(Color.parseColor("#0A86E0"));
        }
    }

    private void showAction(String text, View.OnClickListener l) {
        action.setText(text);
        action.setOnClickListener(l);
        action.setVisibility(View.VISIBLE);
    }

    private void fail(String msg) {
        runOnUiThread(() -> {
            status.setText(msg);
            bar.setVisibility(View.GONE);
            showAction("Try again", v -> startDownload());
            close.setText("Close");
        });
    }

    // ------------------------------------------------------------ download

    private File targetFile() {
        File dir;
        if (Build.VERSION.SDK_INT >= 24) {
            dir = new File(getCacheDir(), "updates");          // shared through FileProvider
        } else {
            File ext = getExternalCacheDir();                   // pre-7.0 installer needs a file:// it can read
            dir = new File(ext != null ? ext : getCacheDir(), "updates");
        }
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "ATTENDANCE-update.apk");
    }

    private void startDownload() {
        if (downloading) return;
        downloading = true;
        action.setVisibility(View.GONE);
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        status.setText("Downloading the latest version…");
        close.setText("Cancel");

        final String address = getString(R.string.update_apk_url);
        new Thread(() -> {
            File out = targetFile();
            File part = new File(out.getParentFile(), out.getName() + ".part");
            HttpURLConnection c = null;
            try {
                URL url = new URL(address);
                boolean ok = false;
                // GitHub answers with redirects to its file servers; follow them by hand
                // so an https -> https hop to another host always works.
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
                final long total = len;   // getContentLengthLong() needs Android 7+
                try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(part)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int n, lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        if (isFinishing()) return;
                        os.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            final int pct = (int) (done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                final long d = done;
                                runOnUiThread(() -> {
                                    bar.setIndeterminate(false);
                                    bar.setProgress(pct);
                                    status.setText("Downloading… " + pct + "%  (" + (d / 1024) + " / " + (total / 1024) + " KB)");
                                });
                            }
                        }
                    }
                }
                if (out.exists()) //noinspection ResultOfMethodCallIgnored
                    out.delete();
                if (!part.renameTo(out)) throw new Exception("could not save the file");
                runOnUiThread(() -> verifyAndInstall(out));
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                fail("Download failed. Check your internet connection and try again.\n(" + e.getMessage() + ")");
            } finally {
                if (c != null) c.disconnect();
                downloading = false;
            }
        }).start();
    }

    // ------------------------------------------------------------- install

    @SuppressWarnings("deprecation")
    private static long versionOf(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    private void verifyAndInstall(File file) {
        PackageManager pm = getPackageManager();
        PackageInfo apk = pm.getPackageArchiveInfo(file.getAbsolutePath(), 0);
        if (apk == null || !getPackageName().equals(apk.packageName)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            fail("The downloaded file is not a valid ATTENDANCE update.");
            return;
        }
        long installed;
        try {
            installed = versionOf(pm.getPackageInfo(getPackageName(), 0));
        } catch (PackageManager.NameNotFoundException e) {
            installed = 0;
        }
        bar.setIndeterminate(false);
        bar.setProgress(100);
        if (versionOf(apk) <= installed) {
            status.setText("You already have the latest version (" + apk.versionName + ").");
            bar.setVisibility(View.GONE);
            action.setVisibility(View.GONE);
            close.setText("Close");
            return;
        }
        apkFile = file;
        status.setText("Version " + apk.versionName + " is ready. Tap Install to finish.");
        showAction("Install", v -> install());
        close.setText("Later");
        install();
    }

    private void install() {
        if (apkFile == null || !apkFile.exists()) { startDownload(); return; }

        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            status.setText("One-time step: allow ATTENDANCE to install updates.\n"
                    + "Turn on \"Allow from this source\", then come back.");
            showAction("Open settings", v -> {
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

        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            if (Build.VERSION.SDK_INT >= 24) {
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
                i.setDataAndType(uri, APK_MIME);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                i.setDataAndType(Uri.fromFile(apkFile), APK_MIME);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            status.setText("Confirm the update on the next screen.\nAfter it installs, open ATTENDANCE again.");
            showAction("Install", v -> install());
        } catch (Exception e) {
            fail("Could not open the installer: " + e.getMessage());
        }
    }
}
