package com.udesh.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Loads launchable TV apps and splits them into dock and grid. */
final class AppRepo {
    static final class App {
        String pkg, label;
        Drawable banner;   // null when the app has no TV banner
        Drawable icon;
        int glow = Color.WHITE;
    }

    final List<App> dock = new ArrayList<>();
    final List<App> grid = new ArrayList<>();

    void load(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> all = new HashMap<>();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || all.containsKey(ai.packageName) || ai.packageName.equals(c.getPackageName())) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm)).trim();
            a.banner = ai.loadBanner(pm);
            if (a.banner == null) a.banner = ai.applicationInfo.loadBanner(pm);
            a.icon = ri.loadIcon(pm);
            a.glow = sampleGlow(a.banner != null ? a.banner : a.icon);
            all.put(a.pkg, a);
        }
        dock.clear();
        grid.clear();
        Set<String> used = new HashSet<>();
        for (String p : Config.array(c, R.array.dock_apps)) {
            App a = all.get(p);
            if (a != null && used.add(p)) dock.add(a);
        }
        used.addAll(Arrays.asList(Config.array(c, R.array.hidden_apps)));
        for (App a : all.values()) if (!used.contains(a.pkg)) grid.add(a);
        grid.sort((x, y) -> x.label.toLowerCase(Locale.ROOT).compareTo(y.label.toLowerCase(Locale.ROOT)));

        SharedPreferences prefs = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        applyCustomOrder(prefs, dock, "dockOrder");
        applyCustomOrder(prefs, grid, "gridOrder");
    }

    /** Re-orders a list to match a saved comma-joined package list (from long-press reorder on the
     *  home screen). Apps missing from the saved order (newly installed, or order never saved)
     *  keep their place at the end, in the order they were already in. */
    private static void applyCustomOrder(SharedPreferences prefs, List<App> list, String key) {
        String saved = prefs.getString(key, null);
        if (saved == null || saved.isEmpty()) return;
        Map<String, App> byPkg = new java.util.LinkedHashMap<>();
        for (App a : list) byPkg.put(a.pkg, a);
        List<App> reordered = new ArrayList<>();
        for (String pkg : saved.split(",")) {
            App a = byPkg.remove(pkg);
            if (a != null) reordered.add(a);
        }
        reordered.addAll(byPkg.values());
        list.clear();
        list.addAll(reordered);
    }

    static Intent launchIntent(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }

    /** Most vivid colour in a small render of the banner, brightened for use as a glow. */
    static int sampleGlow(Drawable d) {
        if (d == null) return 0xFF8FA8FF;
        final int w = 32, h = 18;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        copy.setBounds(0, 0, w, h);
        copy.draw(new Canvas(b));
        float[] hsv = new float[3];
        double sx = 0, sy = 0, wsum = 0;
        float satSum = 0, valSum = 0;
        int n = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int p = b.getPixel(x, y);
            if (Color.alpha(p) < 128) continue;
            Color.colorToHSV(p, hsv);
            double weight = hsv[1] * hsv[1] * hsv[2];
            double rad = Math.toRadians(hsv[0]);
            sx += Math.cos(rad) * weight; sy += Math.sin(rad) * weight; wsum += weight;
            satSum += hsv[1] * hsv[2]; valSum += hsv[2]; n++;
        }
        b.recycle();
        if (n == 0 || wsum < 0.02 * n || satSum / n < 0.08f) {
            return 0xFFB8C4E0; // greyscale banner: soft cool white
        }
        float hue = (float) Math.toDegrees(Math.atan2(sy, sx));
        if (hue < 0) hue += 360;
        return Color.HSVToColor(new float[]{hue, 0.75f, 1f});
    }
}
