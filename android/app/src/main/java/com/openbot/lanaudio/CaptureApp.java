package com.openbot.lanaudio;

import android.graphics.drawable.Drawable;

final class CaptureApp {
    final String packageName, label;
    final int uid;
    final Drawable icon;

    CaptureApp(String packageName, String label, int uid, Drawable icon) {
        this.packageName = packageName;
        this.label = label;
        this.uid = uid;
        this.icon = icon;
    }
}
