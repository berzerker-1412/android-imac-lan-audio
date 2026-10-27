package com.openbot.lanaudio;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import java.util.List;

/** Stores only the selected package identity; the current UID is refreshed from PackageManager. */
final class CaptureAppSelection {
    private static final String PREFS = "capture_app_selection";
    private static final String PACKAGE = "package";
    private static final String UID = "uid";

    static CaptureApp load(Context context, PackageManager pm) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String selectedPackage = packageName(context);
        if (selectedPackage == null) return null;
        List<CaptureApp> apps = CaptureAppCatalog.launchableApps(pm, context.getPackageName());
        for (CaptureApp app : apps) {
            if (app.packageName.equals(selectedPackage)) {
                int storedUid = prefs.getInt(UID, app.uid);
                if (storedUid != app.uid) save(context, app);
                return app;
            }
        }
        return null;
    }

    static String packageName(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PACKAGE, null);
    }

    static void save(Context context, CaptureApp app) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PACKAGE, app.packageName).putInt(UID, app.uid).apply();
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private CaptureAppSelection() { }
}
