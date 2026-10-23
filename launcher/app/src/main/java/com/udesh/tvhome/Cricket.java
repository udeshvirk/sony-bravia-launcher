package com.udesh.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * One India or IPL match from CricketData.org: live first, then one starting soon, then a recent
 * result, with a mini scorecard (batters at the crease + current bowler, or top scorer + best
 * bowler once it's over). Free plan = 100 requests a day, so while a match is live only that
 * match's scorecard is fetched; the full match list is rescanned on the slow interval.
 * Fetches only when asked by the visible home screen; the last good card is cached in prefs.
 */
final class Cricket {
    static final int NONE = -1, LIVE = 0, UPCOMING = 1, RESULT = 2;

    static final class Team { String code, score = "", overs = "", logo = ""; boolean batting; }
    static final class Line { String tag, name, figures; }
    static final class Data {
        int state = NONE; String id = "", title = "", status = ""; long time;
        boolean hasCard;
        final List<Team> teams = new ArrayList<>();
        final List<Line> lines = new ArrayList<>();
    }
    interface Listener { void onCricket(Data d); }

    private static final String API = "https://api.cricapi.com/v1/";
    private static final Set<String> IPL_TEAMS = new HashSet<>(Arrays.asList(
            "chennai super kings", "mumbai indians", "royal challengers bengaluru", "royal challengers bangalore",
            "kolkata knight riders", "delhi capitals", "punjab kings", "rajasthan royals",
            "sunrisers hyderabad", "gujarat titans", "lucknow super giants"));
    private static final long UPCOMING_WINDOW = 18 * 3600_000L;   // show a match starting within this
    private static final long RESULT_WINDOW = 20 * 3600_000L;     // keep a result while start was within this

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean fetching;

    Cricket(Context c) {
        ctx = c.getApplicationContext();
        prefs = ctx.getSharedPreferences("cricket", Context.MODE_PRIVATE);
    }

    boolean enabled() {
        return Config.bool(ctx, R.bool.cricket_enabled) && !Config.str(ctx, R.string.cricket_api_key).isEmpty();
    }

    Data cached() {
        String s = prefs.getString("card", null);
        if (s == null) return null;
        try { return fromJson(new JSONObject(s)); } catch (Exception e) { return null; }
    }

    long ageMs() {
        Data d = cached();
        return d == null ? Long.MAX_VALUE : System.currentTimeMillis() - d.time;
    }

    void fetch(Listener l) {
        if (fetching || !enabled()) return;
        if (System.currentTimeMillis() < prefs.getLong("pausedUntil", 0)) return;   // daily limit reached
        fetching = true;
        String key = Config.str(ctx, R.string.cricket_api_key);
        boolean women = Config.bool(ctx, R.bool.cricket_india_women);
        long idle = Config.integer(ctx, R.integer.cricket_idle_refresh_minutes) * 60_000L;
        new Thread(() -> {
            Data d = null;
            try {
                long now = System.currentTimeMillis();
                Data old = cached();
                // 1) a live match we already know about: just its scorecard (1 request)
                if (old != null && old.state == LIVE && now - prefs.getLong("scanTime", 0) < idle) {
                    JSONObject sc = call(key, "match_scorecard?id=" + URLEncoder.encode(old.id, "UTF-8"));
                    if (sc != null) d = build(sc, true);
                }
                // 2) otherwise rescan the current-matches list (1-2 requests) and pick one
                if (d == null) {
                    JSONObject best = null; int bestRank = Integer.MAX_VALUE;
                    boolean ok = false;
                    int offset = 0, total = 1;
                    for (int page = 0; page < 2 && offset < total && best == null; page++) {
                        JSONObject j = call(key, "currentMatches?offset=" + offset);
                        if (j == null) break;
                        ok = true;
                        JSONObject info = j.optJSONObject("info");
                        total = info != null ? info.optInt("totalRows", 0) : 0;
                        JSONArray arr = j.optJSONArray("data");
                        if (arr == null || arr.length() == 0) break;
                        offset += arr.length();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject m = arr.getJSONObject(i);
                            int r = rank(m, women);
                            if (r < bestRank) { bestRank = r; best = m; }
                        }
                    }
                    if (ok) {
                        prefs.edit().putLong("scanTime", now).apply();
                        if (best == null) {
                            d = new Data();
                        } else if (state(best) == UPCOMING) {
                            d = build(best, false);
                        } else if (state(best) == RESULT && old != null && old.hasCard
                                && old.state == RESULT && best.optString("id").equals(old.id)) {
                            d = old;   // finished match already has its scorecard; don't spend a request
                        } else {
                            JSONObject sc = call(key, "match_scorecard?id=" + URLEncoder.encode(best.optString("id"), "UTF-8"));
                            d = sc != null ? build(sc, true) : build(best, false);
                        }
                    }
                }
                if (d != null) {
                    d.time = now;
                    prefs.edit().putString("card", toJson(d).toString()).apply();
                }
            } catch (Exception ignored) {
                d = null;   // keep the cached card
            } finally {
                fetching = false;
            }
            final Data out = d;
            if (out != null) main.post(() -> l.onCricket(out));
        }, "cricket").start();
    }

    /** GET an API endpoint; returns "data" (the object) or null. Pauses until UTC midnight at the daily limit. */
    private JSONObject call(String key, String path) throws Exception {
        JSONObject j = get(API + path + (path.contains("?") ? "&" : "?") + "apikey=" + URLEncoder.encode(key, "UTF-8"));
        if (j == null) return null;
        JSONObject info = j.optJSONObject("info");
        boolean limit = info != null && info.optInt("hitsLimit", 0) > 0
                && info.optInt("hitsToday", 0) >= info.optInt("hitsLimit") - 1;
        if (!"success".equals(j.optString("status")) && j.optString("reason").toLowerCase(Locale.ROOT).contains("limit"))
            limit = true;
        if (limit) {
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.add(Calendar.DAY_OF_YEAR, 1);
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 5); c.set(Calendar.SECOND, 0);
            prefs.edit().putLong("pausedUntil", c.getTimeInMillis()).apply();
        }
        if (!"success".equals(j.optString("status"))) return null;
        if (path.startsWith("currentMatches")) return j;
        return j.optJSONObject("data");
    }

    private static JSONObject get(String url) throws Exception {
        byte[] b = download(url);
        return b == null ? null : new JSONObject(new String(b, "UTF-8"));
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setConnectTimeout(8000);
            con.setReadTimeout(10000);
            if (con.getResponseCode() != 200) return null;
            InputStream in = con.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int r; (r = in.read(buf)) > 0; ) bo.write(buf, 0, r);
            return bo.toByteArray();
        } finally {
            con.disconnect();
        }
    }

    // ------------------------------------------------------------------ picking a match

    /** Lower is better; MAX_VALUE = not India/IPL or out of the time window. */
    private static int rank(JSONObject m, boolean women) {
        JSONArray teams = m.optJSONArray("teams");
        if (teams == null || teams.length() < 2) return Integer.MAX_VALUE;
        String a = teams.optString(0).trim().toLowerCase(Locale.ROOT), b = teams.optString(1).trim().toLowerCase(Locale.ROOT);
        boolean india = a.equals("india") || b.equals("india")
                || (women && (a.equals("india women") || b.equals("india women")));
        boolean ipl = m.optString("name").toLowerCase(Locale.ROOT).contains("indian premier league")
                || (IPL_TEAMS.contains(a) && IPL_TEAMS.contains(b));
        if (!india && !ipl) return Integer.MAX_VALUE;
        int pref = india ? 0 : 1;
        long start = startMs(m), now = System.currentTimeMillis();
        int state = state(m);
        if (state == LIVE) return pref;
        if (state == UPCOMING && start > 0 && start - now < UPCOMING_WINDOW && start - now > -3600_000L) return 10 + pref;
        if (state == RESULT && start > 0 && now - start < RESULT_WINDOW) return 20 + pref;
        return Integer.MAX_VALUE;
    }

    private static int state(JSONObject m) {
        if (!m.optBoolean("matchStarted")) return UPCOMING;
        return m.optBoolean("matchEnded") ? RESULT : LIVE;
    }

    private static long startMs(JSONObject m) {
        String s = str(m, "dateTimeGMT");
        if (s.isEmpty()) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("GMT"));
            Date d = f.parse(s);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ building the card

    /** m = a currentMatches entry or a match_scorecard "data" object (same fields + "scorecard"). */
    private Data build(JSONObject m, boolean withCard) throws Exception {
        Data d = new Data();
        d.state = state(m);
        d.id = str(m, "id");
        String[] parts = str(m, "name").split(", ");
        d.title = parts.length >= 3 ? parts[1] + "  ·  " + stripYear(parts[2]) : parts.length == 2 ? parts[1] : "";

        JSONArray teams = m.getJSONArray("teams");
        JSONArray scores = m.optJSONArray("score");
        String battingTeam = "";
        if (d.state == LIVE && scores != null && scores.length() > 0)
            battingTeam = innTeam(scores.getJSONObject(scores.length() - 1).optString("inning"));
        for (int t = 0; t < 2; t++) {
            String name = teams.optString(t).trim();
            Team tm = new Team();
            tm.code = shortName(m, name);
            tm.logo = logo(m, name);
            tm.batting = name.equalsIgnoreCase(battingTeam);
            if (scores != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < scores.length(); i++) {
                    JSONObject s = scores.getJSONObject(i);
                    if (!innTeam(s.optString("inning")).equalsIgnoreCase(name)) continue;
                    if (sb.length() > 0) sb.append(" & ");
                    int w = s.optInt("w");
                    sb.append(s.optInt("r"));
                    if (w < 10) sb.append('/').append(w);
                    tm.overs = overs(s.optDouble("o")) + " ov";
                }
                tm.score = sb.toString();
            }
            d.teams.add(tm);
        }

        if (d.state == UPCOMING) {
            Calendar c = Calendar.getInstance(), today = Calendar.getInstance();
            c.setTimeInMillis(startMs(m));
            String day = c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) ? "Today" : "Tomorrow";
            String time = DateFormat.format(DateFormat.is24HourFormat(ctx) ? "HH:mm" : "h:mm a", c).toString();
            String type = str(m, "matchType").toUpperCase(Locale.ROOT);
            d.status = "Starts " + day + " " + time + (type.isEmpty() ? "" : "  ·  " + type);
        } else {
            d.status = str(m, "status");
        }

        JSONArray card = withCard ? m.optJSONArray("scorecard") : null;
        if (card != null && card.length() > 0) {
            d.hasCard = true;
            if (d.state == LIVE) liveLines(d, card, scores);
            else resultLines(d, card);
        }
        return d;
    }

    /** Batters at the crease + the bowler in the middle of an over (or the latest one) in the current innings. */
    private static void liveLines(Data d, JSONArray card, JSONArray scores) throws Exception {
        JSONObject inn = card.getJSONObject(card.length() - 1);
        if (scores != null && scores.length() > 0) {
            String cur = scores.getJSONObject(scores.length() - 1).optString("inning");
            for (int i = 0; i < card.length(); i++)
                if (card.getJSONObject(i).optString("inning").equalsIgnoreCase(cur)) inn = card.getJSONObject(i);
        }
        JSONArray bat = inn.optJSONArray("batting");
        if (bat != null) for (int i = 0; i < bat.length() && countTag(d, "BAT") < 2; i++) {
            JSONObject b = bat.getJSONObject(i);
            String how = b.optString("dismissal-text").toLowerCase(Locale.ROOT);
            if (!how.equals("batting") && !how.equals("not out")) continue;
            d.lines.add(line("BAT", b.optJSONObject("batsman"), b.optInt("r") + " (" + b.optInt("b") + ")"));
        }
        JSONArray bowl = inn.optJSONArray("bowling");
        if (bowl != null && bowl.length() > 0) {
            JSONObject pick = bowl.getJSONObject(bowl.length() - 1);
            for (int i = 0; i < bowl.length(); i++) {
                double o = bowl.getJSONObject(i).optDouble("o");
                if (o != Math.floor(o)) pick = bowl.getJSONObject(i);   // part-way through an over = bowling now
            }
            d.lines.add(line("BOWL", pick.optJSONObject("bowler"), figures(pick)));
        }
    }

    /** Top scorer and best bowling across the match. */
    private static void resultLines(Data d, JSONArray card) throws Exception {
        JSONObject top = null, best = null;
        for (int i = 0; i < card.length(); i++) {
            JSONObject inn = card.getJSONObject(i);
            JSONArray bat = inn.optJSONArray("batting"), bowl = inn.optJSONArray("bowling");
            if (bat != null) for (int k = 0; k < bat.length(); k++) {
                JSONObject b = bat.getJSONObject(k);
                if (top == null || b.optInt("r") > top.optInt("r")) top = b;
            }
            if (bowl != null) for (int k = 0; k < bowl.length(); k++) {
                JSONObject b = bowl.getJSONObject(k);
                if (best == null || b.optInt("w") > best.optInt("w")
                        || (b.optInt("w") == best.optInt("w") && b.optInt("r") < best.optInt("r"))) best = b;
            }
        }
        if (top != null) d.lines.add(line("TOP", top.optJSONObject("batsman"), top.optInt("r") + " (" + top.optInt("b") + ")"));
        if (best != null) d.lines.add(line("BEST", best.optJSONObject("bowler"), figures(best)));
    }

    private static int countTag(Data d, String tag) {
        int n = 0;
        for (Line l : d.lines) if (l.tag.equals(tag)) n++;
        return n;
    }

    private static Line line(String tag, JSONObject player, String figures) {
        Line l = new Line();
        l.tag = tag;
        l.name = player != null ? player.optString("name") : "";
        l.figures = figures;
        return l;
    }

    private static String figures(JSONObject b) {
        return b.optInt("w") + "/" + b.optInt("r") + " (" + overs(b.optDouble("o")) + ")";
    }

    /** Team logo from teamInfo, downloaded once into the app's files; returns the local path or "". */
    private String logo(JSONObject m, String team) {
        JSONArray info = m.optJSONArray("teamInfo");
        if (info == null) return "";
        for (int i = 0; i < info.length(); i++) {
            JSONObject ti = info.optJSONObject(i);
            if (ti == null || !team.equalsIgnoreCase(ti.optString("name").trim())) continue;
            String url = str(ti, "img");
            if (url.isEmpty()) return "";
            File dir = new File(ctx.getFilesDir(), "cricket");
            File f = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
            if (f.length() > 0) return f.getPath();
            try {
                byte[] b = download(url);
                if (b == null) return "";
                dir.mkdirs();
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
                return f.getPath();
            } catch (Exception e) {
                return "";
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ helpers

    private static String str(JSONObject o, String k) { return o.isNull(k) ? "" : o.optString(k).trim(); }

    /** "Zimbabwe Women tour of India, 2026" arrives split; drop a trailing year anyway. */
    private static String stripYear(String s) { return s.replaceAll("\\s*\\d{4}(-\\d{2,4})?$", "").trim(); }

    /** "India Inning 1" -> "India". */
    private static String innTeam(String inning) {
        int i = inning.toLowerCase(Locale.ROOT).lastIndexOf(" inning");
        return (i > 0 ? inning.substring(0, i) : inning).trim();
    }

    private static String shortName(JSONObject m, String team) {
        JSONArray info = m.optJSONArray("teamInfo");
        if (info != null) for (int i = 0; i < info.length(); i++) {
            JSONObject ti = info.optJSONObject(i);
            if (ti != null && team.equalsIgnoreCase(ti.optString("name").trim())) {
                String s = str(ti, "shortname");
                if (!s.isEmpty()) return s.toUpperCase(Locale.ROOT);
            }
        }
        if (team.equalsIgnoreCase("india")) return "IND";
        if (team.equalsIgnoreCase("india women")) return "IND-W";
        return team.length() <= 4 ? team.toUpperCase(Locale.ROOT) : team.substring(0, 3).toUpperCase(Locale.ROOT);
    }

    private static String overs(double o) {
        if (Double.isNaN(o)) return "0";
        if (o == Math.floor(o)) return String.valueOf((long) o);
        return String.format(Locale.ROOT, "%.1f", o);
    }

    // ------------------------------------------------------------------ cache (de)serialisation

    private static JSONObject toJson(Data d) throws Exception {
        JSONObject j = new JSONObject();
        j.put("state", d.state).put("id", d.id).put("title", d.title).put("status", d.status)
                .put("time", d.time).put("hasCard", d.hasCard);
        JSONArray ts = new JSONArray();
        for (Team t : d.teams) ts.put(new JSONObject().put("code", t.code).put("score", t.score)
                .put("overs", t.overs).put("logo", t.logo).put("batting", t.batting));
        j.put("teams", ts);
        JSONArray ls = new JSONArray();
        for (Line l : d.lines) ls.put(new JSONObject().put("tag", l.tag).put("name", l.name).put("figures", l.figures));
        j.put("lines", ls);
        return j;
    }

    private static Data fromJson(JSONObject j) {
        Data d = new Data();
        d.state = j.optInt("state", NONE);
        d.id = j.optString("id"); d.title = j.optString("title"); d.status = j.optString("status");
        d.time = j.optLong("time"); d.hasCard = j.optBoolean("hasCard");
        JSONArray ts = j.optJSONArray("teams");
        if (ts != null) for (int i = 0; i < ts.length(); i++) {
            JSONObject o = ts.optJSONObject(i);
            Team t = new Team();
            t.code = o.optString("code"); t.score = o.optString("score"); t.overs = o.optString("overs");
            t.logo = o.optString("logo"); t.batting = o.optBoolean("batting");
            d.teams.add(t);
        }
        JSONArray ls = j.optJSONArray("lines");
        if (ls != null) for (int i = 0; i < ls.length(); i++) {
            JSONObject o = ls.optJSONObject(i);
            Line l = new Line();
            l.tag = o.optString("tag"); l.name = o.optString("name"); l.figures = o.optString("figures");
            d.lines.add(l);
        }
        return d;
    }
}
