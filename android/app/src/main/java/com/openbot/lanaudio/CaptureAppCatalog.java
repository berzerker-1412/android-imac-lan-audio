package com.openbot.lanaudio;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lists only launchable apps, so Android 11+ package visibility stays scoped. */
final class CaptureAppCatalog {
    static List<CaptureApp> launchableApps(PackageManager pm, String ownPackage) {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = pm.queryIntentActivities(launcher, 0);
        Map<String, CaptureApp> unique = new HashMap<>();
        for (ResolveInfo activity : activities) {
            if (activity.activityInfo == null || activity.activityInfo.applicationInfo == null) continue;
            String pkg = activity.activityInfo.packageName;
            if (pkg == null || pkg.equals(ownPackage)) continue;
            try {
                android.content.pm.ApplicationInfo app = pm.getApplicationInfo(pkg, 0);
                String label = String.valueOf(app.loadLabel(pm)).trim();
                if (label.isEmpty()) label = pkg;
                unique.putIfAbsent(pkg, new CaptureApp(pkg, label, app.uid, app.loadIcon(pm)));
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        List<CaptureApp> result = new ArrayList<>(unique.values());
        Collator collator = Collator.getInstance(Locale.getDefault());
        result.sort((a, b) -> collator.compare(a.label, b.label));
        return result;
    }

    private CaptureAppCatalog() { }
}
