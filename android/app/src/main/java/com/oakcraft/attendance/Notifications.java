package com.oakcraft.attendance;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** The two update notifications. Silently skipped if notifications are off. */
final class Notifications {
    private static final String CHANNEL = "app_updates";
    private static final int ID_UPDATED = 4101;
    private static final int ID_INSTALL = 4102;

    private Notifications() { }

    private static NotificationManager channel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Tells you when a new version of ATTENDANCE is installed");
            nm.createNotificationChannel(ch);
        }
        return nm;
    }

    @SuppressWarnings("deprecation")
    private static Notification.Builder builder(Context ctx) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);
        return b.setSmallIcon(R.mipmap.ic_launcher_monochrome).setAutoCancel(true);
    }

    private static int immutable() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    /** "ATTENDANCE updated - tap to open" */
    static void updated(Context ctx) {
        try {
            NotificationManager nm = channel(ctx);
            if (nm == null) return;
            nm.cancel(ID_INSTALL);
            Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (open == null) return;
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            String ver = "";
            try { ver = " " + ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception ignored) { }
            Notification.Builder b = builder(ctx)
                    .setContentTitle("ATTENDANCE updated" + ver)
                    .setContentText("Tap to open the new version.")
                    .setContentIntent(PendingIntent.getActivity(ctx, 1, open, immutable()));
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(10 * 60 * 1000L);
            nm.notify(ID_UPDATED, b.build());
        } catch (Exception ignored) { }
    }

    /** "Update ready - tap to install" (when Android's confirm dialog could not open by itself) */
    static void tapToInstall(Context ctx, Intent confirm) {
        try {
            NotificationManager nm = channel(ctx);
            if (nm == null) return;
            Notification.Builder b = builder(ctx)
                    .setContentTitle("ATTENDANCE update ready")
                    .setContentText("Tap to install the new version.")
                    .setContentIntent(PendingIntent.getActivity(ctx, 2, confirm, immutable()));
            nm.notify(ID_INSTALL, b.build());
        } catch (Exception ignored) { }
    }
}
