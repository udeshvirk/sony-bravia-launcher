package com.udesh.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Open-Meteo current weather for a fixed location. Fetches only when asked by the
 * visible home screen; no background work. Last good result is cached in prefs.
 */
final class Weather {
    static final class Data {
        double temp, hi, lo; int code; boolean day; long time;
    }
    interface Listener { void onWeather(Data d); }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean fetching;

    Weather(Context c) {
        ctx = c.getApplicationContext();
        prefs = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    Data cached() {
        if (!prefs.contains("time")) return null;
        Data d = new Data();
        d.temp = Double.longBitsToDouble(prefs.getLong("temp", 0));
        d.hi = Double.longBitsToDouble(prefs.getLong("hi", 0));
        d.lo = Double.longBitsToDouble(prefs.getLong("lo", 0));
        d.code = prefs.getInt("code", 0);
        d.day = prefs.getBoolean("day", true);
        d.time = prefs.getLong("time", 0);
        return d;
    }

    long ageMs() {
        Data d = cached();
        return d == null ? Long.MAX_VALUE : System.currentTimeMillis() - d.time;
    }

    void fetch(Listener l) {
        if (fetching) return;
        fetching = true;
        String lat = ctx.getString(R.string.weather_lat), lon = ctx.getString(R.string.weather_lon);
        new Thread(() -> {
            Data d = null;
            HttpURLConnection con = null;
            try {
                URL u = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code,is_day"
                        + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1");
                con = (HttpURLConnection) u.openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(8000);
                if (con.getResponseCode() == 200) {
                    InputStream in = con.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    for (int r; (r = in.read(buf)) > 0; ) bo.write(buf, 0, r);
                    JSONObject j = new JSONObject(bo.toString("UTF-8"));
                    JSONObject cur = j.getJSONObject("current");
                    JSONObject daily = j.getJSONObject("daily");
                    d = new Data();
                    d.temp = cur.getDouble("temperature_2m");
                    d.code = cur.getInt("weather_code");
                    d.day = cur.optInt("is_day", 1) == 1;
                    d.hi = daily.getJSONArray("temperature_2m_max").getDouble(0);
                    d.lo = daily.getJSONArray("temperature_2m_min").getDouble(0);
                    d.time = System.currentTimeMillis();
                    prefs.edit().putLong("temp", Double.doubleToLongBits(d.temp))
                            .putLong("hi", Double.doubleToLongBits(d.hi))
                            .putLong("lo", Double.doubleToLongBits(d.lo))
                            .putInt("code", d.code).putBoolean("day", d.day)
                            .putLong("time", d.time).apply();
                }
            } catch (Exception ignored) {
                // keep the cached value; the row dims once it is stale
            } finally {
                if (con != null) con.disconnect();
                fetching = false;
            }
            final Data out = d;
            if (out != null) main.post(() -> l.onWeather(out));
        }, "weather").start();
    }

    /** WMO weather code to icon. */
    static int icon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.wx_clear_day : R.drawable.wx_clear_night;
        if (code == 1 || code == 2) return day ? R.drawable.wx_partly_day : R.drawable.wx_partly_night;
        if (code == 3) return R.drawable.wx_cloudy;
        if (code == 45 || code == 48) return R.drawable.wx_fog;
        if (code >= 51 && code <= 57) return R.drawable.wx_drizzle;
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.wx_rain;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.wx_snow;
        if (code >= 95) return R.drawable.wx_thunder;
        return R.drawable.wx_cloudy;
    }
}
