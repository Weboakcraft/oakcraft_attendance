package com.oakcraft.attendance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * Receives PackageInstaller's answer for an in-app update.
 * PENDING_USER_ACTION: Android wants the user to confirm - show its dialog
 * (or, if the update card was hidden, a "tap to install" notification).
 */
public class InstallResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @SuppressWarnings("deprecation")
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) {
                UpdateActivity.report("Update failed: the installer did not respond.", true);
                return;
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            boolean visible = UpdateActivity.isShowing();
            try {
                ctx.startActivity(confirm);
                UpdateActivity.report("Tap \"Update\" on the next screen to finish.", false);
            } catch (Exception e) {
                visible = false;
            }
            // Android may silently block the dialog when the app is in the background.
            if (!visible) Notifications.tapToInstall(ctx, confirm);
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            UpdateActivity.report("Update installed - reopening…", false);
        } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            UpdateActivity.report("Update cancelled.", true);
        } else {
            UpdateActivity.report("Update failed" + (msg != null ? ": " + msg : "") + ".", true);
        }
    }
}
