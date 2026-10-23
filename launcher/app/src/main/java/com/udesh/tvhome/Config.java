package com.udesh.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Settings without a rebuild: values in
 *   /sdcard/Android/data/com.udesh.tvhome/files/config.json
 * override the defaults in res/values/config.xml. Keys are the config.xml names
 * (e.g. "dock_apps", "key_red", "weather_lat", "cricket_india_women"); anything missing or of
 * the wrong type falls back to config.xml. Push it with ./config.sh (which also restarts the app).
 * The file is re-read whenever it changes.
 */
final class Config {
    private static final String TAG = "TvHomeConfig";
    private static JSONObject json = new JSONObject();
    private static long stamp = Long.MIN_VALUE;

    private Config() {}

    static File file(Context c) {
        File dir = c.getExternalFilesDir(null);
        return new File(dir != null ? dir : c.getFilesDir(), "config.json");
    }

    private static synchronized JSONObject load(Context c) {
        File f = file(c);
        long m = f.exists() ? f.lastModified() ^ f.length() : 0;
        if (m != stamp) {
            stamp = m;
            json = new JSONObject();
            if (m != 0) {
                try (InputStream in = new FileInputStream(f)) {
                    byte[] b = new byte[(int) f.length()];
                    int n = 0;
                    for (int r; n < b.length && (r = in.read(b, n, b.length - n)) > 0; ) n += r;
                    json = new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
                    Log.i(TAG, "loaded " + json.length() + " settings from " + f);
                } catch (Exception e) {
                    Log.w(TAG, "config.json ignored (not valid JSON): " + e.getMessage());
                }
            }
        }
        return json;
    }

    private static String key(Context c, int id) { return c.getResources().getResourceEntryName(id); }

    static String str(Context c, int id) {
        JSONObject j = load(c);
        String k = key(c, id);
        Object v = j.opt(k);
        return v instanceof String || v instanceof Number ? String.valueOf(v) : c.getString(id);
    }

    static boolean bool(Context c, int id) {
        Object v = load(c).opt(key(c, id));
        return v instanceof Boolean ? (Boolean) v : c.getResources().getBoolean(id);
    }

    static int integer(Context c, int id) {
        Object v = load(c).opt(key(c, id));
        return v instanceof Number ? ((Number) v).intValue() : c.getResources().getInteger(id);
    }

    static String[] array(Context c, int id) {
        Object v = load(c).opt(key(c, id));
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            String[] out = new String[a.length()];
            for (int i = 0; i < a.length(); i++) out[i] = a.optString(i).trim();
            return out;
        }
        return c.getResources().getStringArray(id);
    }
}
