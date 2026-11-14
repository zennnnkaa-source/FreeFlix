package com.nepdon.wire.ui.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.widget.Toast;

import com.nepdon.wire.config.GameTargets;
import com.nepdon.wire.config.Prefs;
import com.nepdon.wire.R;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Multi-app target selection. Loads every launchable app (excluding this app's
 * own package) on a background thread, then shows a checkbox multi-select
 * dialog and persists the chosen set via {@link Prefs#setTargetPackages}.
 */
public final class AppPicker {

    /** Notified with the number of selected apps after the user confirms. */
    public interface Listener {
        void onSelectionChanged(int selectedCount);
    }

    private final Activity activity;
    private final Listener listener;
    private final List<ApplicationInfo> appInfos = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private boolean loaded = false;

    public AppPicker(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    /** True once the background app listing has finished. */
    public boolean isLoaded() {
        return loaded;
    }

    /**
     * Set from {@code MainActivity.onDestroy}. The loader thread captures the
     * Activity and enumerates every installed package with GET_PERMISSIONS,
     * which takes 1-3 s on a phone with ~200 apps -- so rotating or backing
     * out immediately after launch pinned a destroyed Activity for that whole
     * window. Checking this before touching the Activity releases it.
     */
    private volatile boolean cancelled = false;

    /** Stops the loader from touching a dead Activity. */
    public void cancel() {
        cancelled = true;
    }

    /** Loads launchable apps on a background thread; safe to call from onCreate. */
    public void loadApps() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    PackageManager pm = activity.getPackageManager();
                    List<PackageInfo> pkgs = pm.getInstalledPackages(0x80);
                    final List<ApplicationInfo> resList  = new ArrayList<>();
                    final List<String> nameList = new ArrayList<>();

                    for (int i = 0; i < pkgs.size(); i++) {
                        PackageInfo pi = pkgs.get(i);
                        if (pi == null || pi.applicationInfo == null) continue;
                        if (pi.packageName.equals(activity.getPackageName())) continue;
                        // Offer every supported Free Fire package variant. The
                        // previous MAX-only check made the picker empty for users
                        // running the global or a regional build, even though
                        // GameTargets already knows how to scope those packages.
                        // Do not offer unrelated apps: selecting one would make
                        // the VPN silently miss the game's traffic.
                        if (!GameTargets.isKnownGame(pi.packageName)) continue;
                        if (pm.getLaunchIntentForPackage(pi.packageName) == null) continue;
                        String label;
                        try {
                            label = pm.getApplicationLabel(pi.applicationInfo).toString();
                        } catch (Exception e) {
                            label = pi.packageName;
                        }
                        resList.add(pi.applicationInfo);
                        nameList.add(label);
                    }

                    for (int i = 1; i < resList.size(); i++) {
                        ApplicationInfo appVal  = resList.get(i);
                        String nameVal = nameList.get(i);
                        int j = i - 1;
                        while (j >= 0 && nameList.get(j).compareToIgnoreCase(nameVal) > 0) {
                            resList.set(j + 1, resList.get(j));
                            nameList.set(j + 1, nameList.get(j));
                            j--;
                        }
                        resList.set(j + 1, appVal);
                        nameList.set(j + 1, nameVal);
                    }

                    if (cancelled || activity.isFinishing()) return;
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            appInfos.clear();
                            appInfos.addAll(resList);
                            labels.clear();
                            labels.addAll(nameList);
                            loaded = true;
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "LoadApps").start();
    }

    /** Shows the multi-select dialog. Must be called on the UI thread. */
    public void show() {
        if (appInfos.isEmpty()) {
            Toast.makeText(activity, "No app!", Toast.LENGTH_SHORT).show();
            return;
        }
        final int n = appInfos.size();
        final String[] names = new String[n];
        final boolean[] checked = new boolean[n];
        Set<String> selected = Prefs.getTargetPackages(activity);
        for (int i = 0; i < n; i++) {
            names[i] = labels.get(i);
            checked[i] = selected.contains(appInfos.get(i).packageName);
        }

        // Themed, and with OUR button strings. android.R.string.cancel is
        // resolved against the PHONE's locale, which is why this dialog
        // still said "HUY" after every app string was translated.
        new AlertDialog.Builder(activity, R.style.Theme_Nepdon_Dialog)
            .setTitle(activity.getString(R.string.app_picker_title))
            .setMultiChoiceItems(names, checked, new DialogInterface.OnMultiChoiceClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                    checked[which] = isChecked;
                }
            })
            .setPositiveButton(R.string.nv_ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Set<String> chosen = new LinkedHashSet<>();
                    for (int i = 0; i < n; i++) {
                        if (checked[i]) chosen.add(appInfos.get(i).packageName);
                    }
                    Prefs.setTargetPackages(activity, chosen);
                    if (listener != null) listener.onSelectionChanged(chosen.size());
                }
            })
            .setNegativeButton(R.string.nv_cancel, null)
            .show();
    }
}
