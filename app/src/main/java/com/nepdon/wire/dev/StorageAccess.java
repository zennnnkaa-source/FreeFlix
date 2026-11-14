package com.nepdon.wire.dev;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

/**
 * Ensures FileWatchLog can write under /storage/emulated/0/NepdonWire.
 * On API 30+ that needs MANAGE_EXTERNAL_STORAGE (all-files access).
 */
public final class StorageAccess {

    public static final int REQ_LEGACY_WRITE = 0x4E57; // 'NW'

    private StorageAccess() { }

    public static boolean hasAllFiles(Context ctx) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                return Environment.isExternalStorageManager();
            } catch (Throwable t) {
                return false;
            }
        }
        if (Build.VERSION.SDK_INT >= 23) {
            return ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** Init log + prompt for all-files / write if missing. */
    public static void ensure(Activity activity) {
        if (activity == null) return;
        FileWatchLog.init(activity.getApplicationContext());
        if (hasAllFiles(activity)) {
            FileWatchLog.line("STORAGE", "ok path=" + FileWatchLog.lastPath());
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(i);
                FileWatchLog.line("STORAGE", "requested MANAGE_EXTERNAL_STORAGE settings");
            } else if (Build.VERSION.SDK_INT >= 23) {
                activity.requestPermissions(
                        new String[]{
                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                Manifest.permission.READ_EXTERNAL_STORAGE
                        },
                        REQ_LEGACY_WRITE);
                FileWatchLog.line("STORAGE", "requested WRITE_EXTERNAL_STORAGE");
            }
        } catch (Throwable t) {
            // Fallback: open general all-files screen
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    activity.startActivity(i);
                }
            } catch (Throwable ignored) { }
            FileWatchLog.line("STORAGE", "prompt error: " + t.getClass().getSimpleName());
        }
    }
}
