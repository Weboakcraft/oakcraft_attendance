package com.oakcraft.attendance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Runs in the NEW version right after ATTENDANCE was updated.
 * Re-opens the app; where Android does not allow an app to open itself from the
 * background, the "updated - tap to open" notification does the job instead.
 */
public class UpdatedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        Notifications.updated(ctx);
        try {
            Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (open != null) {
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                ctx.startActivity(open);
            }
        } catch (Exception ignored) {
            // blocked by Android's background-start rules: the notification remains
        }
    }
}
