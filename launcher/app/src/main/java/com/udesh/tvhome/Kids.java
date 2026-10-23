package com.udesh.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Kids mode: the home screen shows only the apps in kids_apps, anything else that comes to the
 * front is sent back home by ColorKeyService, and leaving needs kids_pin. The on/off state is kept
 * in prefs, so it survives the TV being switched off. No PIN configured = the feature is hidden.
 */
final class Kids {
    private Kids() {}

    /** Always allowed in kids mode: system dialogs, keyboards, the screensaver, the soundbar panel. */
    private static final Set<String> SYSTEM_OK = new HashSet<>(Arrays.asList(
            "android", "com.android.systemui", "com.google.android.apps.tv.dreamx",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.sony.dtv.HomeTheatreControl"));

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    static boolean available(Context c) { return pin(c).matches("\\d{4}"); }

    static boolean on(Context c) { return available(c) && prefs(c).getBoolean("kidsMode", false); }

    static void set(Context c, boolean on) {
        prefs(c).edit().putBoolean("kidsMode", on).putInt("kidsFails", 0).commit();
    }

    static String pin(Context c) { return Config.str(c, R.string.kids_pin).trim(); }

    static String name(Context c) { return Config.str(c, R.string.kids_name).trim(); }

    static List<String> apps(Context c) { return Arrays.asList(Config.array(c, R.array.kids_apps)); }

    static boolean allowedApp(Context c, String pkg) { return apps(c).contains(pkg); }

    /** May this package be on screen while kids mode is on? */
    static boolean allowedForeground(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (pkg.equals(c.getPackageName()) || SYSTEM_OK.contains(pkg) || allowedApp(c, pkg)) return true;
        return isKeyboard(c, pkg);
    }

    private static boolean isKeyboard(Context c, String pkg) {
        String def = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (def != null && def.startsWith(pkg + "/")) return true;
        try {
            InputMethodManager imm = c.getSystemService(InputMethodManager.class);
            for (InputMethodInfo i : imm.getEnabledInputMethodList()) if (pkg.equals(i.getPackageName())) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // ---- wrong-PIN lockout: 5 misses = 60 s wait ----
    static long lockedForMs(Context c) {
        long until = prefs(c).getLong("kidsLockUntil", 0);
        return Math.max(0, until - System.currentTimeMillis());
    }

    /** Returns true when the PIN is right; counts misses otherwise. */
    static boolean check(Context c, String entered) {
        if (lockedForMs(c) > 0) return false;
        if (entered.equals(pin(c))) {
            prefs(c).edit().putInt("kidsFails", 0).apply();
            return true;
        }
        int fails = prefs(c).getInt("kidsFails", 0) + 1;
        SharedPreferences.Editor e = prefs(c).edit().putInt("kidsFails", fails);
        if (fails >= 5) e.putInt("kidsFails", 0).putLong("kidsLockUntil", System.currentTimeMillis() + 60_000L);
        e.apply();
        return false;
    }
}
