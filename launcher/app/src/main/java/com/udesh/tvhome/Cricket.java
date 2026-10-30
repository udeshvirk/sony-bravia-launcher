package com.udesh.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * One India or IPL match from ESPN's public cricket feed (no key, no daily limit): live first,
 * then one starting within 18 h, then a recent result, with a mini scorecard (batters at the
 * crease + current bowler, or top scorer + best bowler once it's over).
 * A scan reads today's scoreboard (plus tomorrow's when nothing is live); while a match is live
 * only its summary is fetched, with a full rescan on the slow interval.
 * The feed is unofficial, so every failure is soft: the last good card stays (a live one is
 * marked with its time after STALE_MS and hidden after GIVE_UP_MS) and retries back off.
 * Fetches only when asked by the visible home screen or the screensaver; cached in prefs.
 * Being a polite client of a free feed: one request in flight at a time, gzip (~9 KB scoreboard,
 * ~22 KB summary), tomorrow's scoreboard at most every 3 h, slow polling during breaks (stumps,
 * rain, innings break), and a 429/503 from ESPN pauses all fetching (Retry-After, at least 30 min).
 */
final class Cricket {
    static final int NONE = -1, LIVE = 0, UPCOMING = 1, RESULT = 2;

    static final class Team { String code, score = "", overs = "", logo = "", flag = ""; boolean batting; }
    static final class Line { String tag, name, figures; }
    static final class Data {
        int state = NONE; String id = "", title = "", status = ""; long time, start;
        boolean hasCard, paused;   // paused = live but play stopped (stumps, rain, a break)
        final List<Team> teams = new ArrayList<>();
        final List<Line> lines = new ArrayList<>();
    }
    /** Called after every fetch: the new card, or the cached one (maybe null) if the fetch failed. */
    interface Listener { void onCricket(Data d); }

    /** A live card not refreshed for this long is shown dimmed with its time... */
    static final long STALE_MS = 10 * 60_000L;
    /** ...and hidden after this (a "LIVE" score an hour old is worse than none). */
    static final long GIVE_UP_MS = 60 * 60_000L;

    private static final String TAG = "Cricket";
    private static final String SCOREBOARD =
            "https://site.web.api.espn.com/apis/personalized/v2/scoreboard/header?sport=cricket&region=in&lang=en";
    private static final String SUMMARY = "https://site.web.api.espn.com/apis/site/v2/sports/cricket/%s/summary?event=%s";
    private static final Set<String> IPL_TEAMS = new HashSet<>(Arrays.asList(
            "chennai super kings", "mumbai indians", "royal challengers bengaluru", "royal challengers bangalore",
            "kolkata knight riders", "delhi capitals", "punjab kings", "rajasthan royals",
            "sunrisers hyderabad", "gujarat titans", "lucknow super giants"));
    private static final long UPCOMING_WINDOW = 18 * 3600_000L;   // show a match starting within this
    private static final long RESULT_WINDOW = 20 * 3600_000L;     // keep a result while start was within this
    private static final Pattern OVERS = Pattern.compile("\\(([\\d.]+)(?:/[\\d.]+)?\\s*ov");

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final long TOMORROW_TTL = 3 * 3600_000L;       // re-read tomorrow's scoreboard after this
    private static final long PAUSED_REFRESH = 15 * 60_000L;      // live but play stopped: refresh this often
    private static final long MIN_SERVER_PAUSE = 30 * 60_000L;    // ESPN said 429/503: stay away at least this

    private static boolean fetching;   // shared by the home screen and the screensaver
    private static volatile long serverPause;   // set by download() on 429/503, applied after the fetch

    Cricket(Context c) {
        ctx = c.getApplicationContext();
        prefs = ctx.getSharedPreferences("cricket", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("espn", false))   // drop the old CricketData.org cache once
            prefs.edit().clear().putBoolean("espn", true).apply();
    }

    boolean enabled() { return Config.bool(ctx, R.bool.cricket_enabled); }

    Data cached() {
        String s = prefs.getString("card", null);
        if (s == null) return null;
        try { return fromJson(new JSONObject(s)); } catch (Exception e) { return null; }
    }

    long ageMs() {
        Data d = cached();
        return d == null ? Long.MAX_VALUE : System.currentTimeMillis() - d.time;
    }

    /** The last fetch failed (feed down, or its format changed). */
    boolean failing() { return prefs.getInt("fails", 0) > 0; }

    /** Time since the last fetch attempt, successful or not. */
    long sinceAttemptMs() { return System.currentTimeMillis() - prefs.getLong("attempt", 0); }

    void fetch(Listener l) {
        if (!enabled()) return;
        if (System.currentTimeMillis() < prefs.getLong("pauseUntil", 0)) return;   // ESPN asked us to back off
        synchronized (Cricket.class) {
            if (fetching) return;
            fetching = true;
        }
        boolean women = Config.bool(ctx, R.bool.cricket_india_women);
        long idle = Config.integer(ctx, R.integer.cricket_idle_refresh_minutes) * 60_000L;
        new Thread(() -> {
            long now = System.currentTimeMillis();
            Data d = null;
            try {
                d = load(now, women, idle);
            } catch (Throwable e) {
                Log.w(TAG, "fetch failed: " + e);
            }
            SharedPreferences.Editor e = prefs.edit().putLong("attempt", now);
            if (serverPause > 0) {
                e.putLong("pauseUntil", now + serverPause);
                serverPause = 0;
            }
            if (d != null) {
                d.time = now;
                try { e.putString("card", toJson(d).toString()); } catch (Exception ignored) { }
                e.putInt("fails", 0);
            } else {
                e.putInt("fails", Math.min(prefs.getInt("fails", 0) + 1, 10));
            }
            e.apply();
            synchronized (Cricket.class) { fetching = false; }
            final Data out = d != null ? d : cached();
            main.post(() -> l.onCricket(out));
        }, "cricket").start();
    }

    /**
     * How long after the last fetch attempt to fetch again: fast while live, and for an upcoming
     * match wake up right at its start. After failures: 1, 2, 4... minutes, capped at the slow interval.
     */
    long refreshMs(int liveMinutesRes) {
        long idle = Config.integer(ctx, R.integer.cricket_idle_refresh_minutes) * 60_000L;
        long live = Config.integer(ctx, liveMinutesRes) * 60_000L;
        int fails = prefs.getInt("fails", 0);
        if (fails > 0) return Math.min(idle, 60_000L << Math.min(fails - 1, 5));
        Data d = cached();
        if (d == null) return idle;
        if (d.state == LIVE) return d.paused ? Math.max(live, Math.min(idle, PAUSED_REFRESH)) : live;
        if (d.state == UPCOMING && d.start > 0) return Math.max(live, Math.min(idle, d.start + 60_000L - d.time));
        return idle;
    }

    // ------------------------------------------------------------------ fetching

    /** The card to show now, or null if the feed couldn't be read (the cached card then stays). */
    private Data load(long now, boolean women, long idle) throws Exception {
        Data old = cached();
        // 1) a live match we already know about: just its summary (1 request); an exhibition
        //    match is rescanned every time so the main India side takes over as soon as it plays
        if (old != null && old.state == LIVE && !prefs.getBoolean("minor", false)
                && now - prefs.getLong("scanTime", 0) < idle) {
            Data d = summary(old.id);
            if (d != null) return d;
        }
        // 2) scan the scoreboard: today's, plus tomorrow's for an early start when nothing is live
        JSONObject best = null;
        int bestRank = Integer.MAX_VALUE;
        JSONArray today = scoreboard(SCOREBOARD, women);
        if (today == null) return null;   // feed down: keep the cached card
        for (int i = 0; i < today.length(); i++) {
            int r = rank(today.getJSONObject(i), women, now);
            if (r < bestRank) { bestRank = r; best = today.getJSONObject(i); }
        }
        if (bestRank >= 10) {
            JSONArray tomorrow = tomorrow(now, women);
            for (int i = 0; i < tomorrow.length(); i++) {
                int r = rank(tomorrow.getJSONObject(i), women, now);
                if (r < bestRank) { bestRank = r; best = tomorrow.getJSONObject(i); }
            }
        }
        prefs.edit().putLong("scanTime", now).putBoolean("minor", best != null && best.optBoolean("minor")).apply();
        if (best == null) return new Data();
        int state = best.getInt("state");
        if (state == UPCOMING) return build(best, null);
        if (state == RESULT && old != null && old.state == RESULT && old.hasCard && best.getString("id").equals(old.id))
            return old;   // finished match already has its scorecard
        Data d = null;
        try { d = summary(best.getString("id")); } catch (Exception e) { Log.w(TAG, "summary failed: " + e); }
        return d != null ? d : build(best, null);   // the scoreboard's score, without the batters/bowler
    }

    /** India/IPL matches on one scoreboard, as common match objects; null if it couldn't be read. */
    private static JSONArray scoreboard(String url, boolean women) throws Exception {
        JSONObject j = get(url);
        if (j == null) return null;
        JSONArray out = new JSONArray();
        JSONArray leagues = j.getJSONArray("sports").getJSONObject(0).getJSONArray("leagues");
        for (int i = 0; i < leagues.length(); i++) {
            JSONObject lg = leagues.getJSONObject(i);
            JSONArray events = lg.optJSONArray("events");
            for (int k = 0; events != null && k < events.length(); k++) {
                JSONObject m = fromScoreboard(events.getJSONObject(k), lg);
                if (m != null && (india(m, women) || ipl(m))) out.put(m);
            }
        }
        return out;
    }

    /** Tomorrow's India/IPL matches; the scoreboard is read at most every TOMORROW_TTL. */
    private JSONArray tomorrow(long now, boolean women) {
        String day = ymd(now + 24 * 3600_000L);
        if (day.equals(prefs.getString("tomorrowDay", "")) && now - prefs.getLong("tomorrowTime", 0) < TOMORROW_TTL) {
            try { return new JSONArray(prefs.getString("tomorrow", "[]")); } catch (Exception ignored) { }
        }
        try {
            JSONArray out = scoreboard(SCOREBOARD + "&dates=" + day, women);
            if (out != null) {
                prefs.edit().putString("tomorrow", out.toString()).putString("tomorrowDay", day)
                        .putLong("tomorrowTime", now).apply();
                return out;
            }
        } catch (Exception e) {
            Log.w(TAG, "tomorrow's scoreboard: " + e);
        }
        return new JSONArray();
    }

    /** Full card for one match ("league/event" id), or null. */
    private Data summary(String id) throws Exception {
        int slash = id.indexOf('/');
        if (slash <= 0) return null;
        JSONObject j = get(String.format(Locale.ROOT, SUMMARY,
                URLEncoder.encode(id.substring(0, slash), "UTF-8"), URLEncoder.encode(id.substring(slash + 1), "UTF-8")));
        if (j == null) return null;
        return build(fromSummary(j, id), j);
    }

    private static JSONObject get(String url) throws Exception {
        byte[] b = download(url);
        return b == null ? null : new JSONObject(new String(b, "UTF-8"));
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setConnectTimeout(8000);
            con.setReadTimeout(15000);
            con.setRequestProperty("Accept", "application/json, image/*");
            con.setRequestProperty("Accept-Encoding", "gzip");   // explicit, so we unzip ourselves below
            int code = con.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "HTTP " + code + " " + url);
                if (code == 429 || code == 503) {   // too many requests / overloaded: back off for a while
                    long after = 0;
                    try { after = Long.parseLong(con.getHeaderField("Retry-After").trim()) * 1000L; } catch (Exception ignored) { }
                    serverPause = Math.max(MIN_SERVER_PAUSE, Math.min(after, 6 * 3600_000L));
                }
                return null;
            }
            InputStream in = con.getInputStream();
            if ("gzip".equalsIgnoreCase(con.getContentEncoding())) in = new GZIPInputStream(in);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int r; (r = in.read(buf)) > 0; ) bo.write(buf, 0, r);
            return bo.toByteArray();
        } finally {
            con.disconnect();
        }
    }

    // ------------------------------------------------------------------ one match, both feeds
    // Both the scoreboard and a summary are turned into the same small object:
    // {id, state, start, desc, league, status, batting, teams: [{id, name, abbr, score, logo}]}

    private static JSONObject fromScoreboard(JSONObject ev, JSONObject lg) throws Exception {
        JSONArray cs = ev.optJSONArray("competitors");
        if (cs == null || cs.length() < 2) return null;
        JSONObject fs = ev.optJSONObject("fullStatus");
        JSONObject m = new JSONObject()
                .put("id", str(lg, "id") + "/" + str(ev, "id"))
                .put("state", state(str(ev, "status")))
                .put("start", isoMs(str(ev, "date")))
                .put("desc", str(ev, "description"))
                .put("league", str(lg, "name"))
                .put("status", fs == null ? "" : str(fs, "summary"))
                .put("paused", fs != null && paused(fs.optJSONObject("type")))
                .put("batting", fs == null ? "" : str(fs, "battingTeamId"))
                .put("minor", minor(ev.optJSONObject("class")));
        JSONArray teams = new JSONArray();
        for (int i = 0; i < 2; i++) {
            JSONObject c = cs.getJSONObject(i);
            teams.put(new JSONObject().put("id", str(c, "id")).put("name", str(c, "displayName"))
                    .put("abbr", str(c, "abbreviation")).put("score", str(c, "score")).put("logo", str(c, "logo")));
        }
        return m.put("teams", teams);
    }

    private static JSONObject fromSummary(JSONObject j, String id) throws Exception {
        JSONObject h = j.getJSONObject("header");
        JSONObject c = h.getJSONArray("competitions").getJSONObject(0);
        JSONObject st = c.optJSONObject("status");
        JSONObject type = st == null ? null : st.optJSONObject("type");
        JSONObject lg = h.optJSONObject("league");
        String desc = str(c, "description");
        JSONObject m = new JSONObject()
                .put("id", id)
                .put("state", state(type == null ? "" : str(type, "state")))
                .put("start", isoMs(str(c, "date")))
                .put("desc", desc.isEmpty() ? str(h, "description") : desc)
                .put("league", lg == null ? "" : str(lg, "name"))
                .put("status", st == null ? "" : str(st, "summary"))
                .put("paused", paused(type))
                .put("batting", st == null ? "" : str(st, "battingTeamId"));
        JSONArray cs = c.getJSONArray("competitors"), teams = new JSONArray();
        for (int i = 0; i < 2; i++) {
            JSONObject t = cs.getJSONObject(i), team = t.getJSONObject("team");
            JSONArray logos = team.optJSONArray("logos");
            String logo = logos != null && logos.length() > 0 ? str(logos.getJSONObject(0), "href") : "";
            teams.put(new JSONObject().put("id", str(t, "id")).put("name", str(team, "displayName"))
                    .put("abbr", str(team, "abbreviation")).put("score", str(t, "score")).put("logo", logo));
        }
        return m.put("teams", teams);
    }

    /** ESPN files exhibition events (Hong Kong Sixes, tour games) as "Other ..." matches. */
    private static boolean minor(JSONObject cls) {
        if (cls == null) return false;
        return cls.optString("generalClassCard").trim().toLowerCase(Locale.ROOT).startsWith("other")
                || cls.optString("name").trim().toLowerCase(Locale.ROOT).startsWith("other");
    }

    /** Live but play has stopped: ESPN then says "Stumps", "Lunch", "Rain", "Innings break"... instead of "Live". */
    private static boolean paused(JSONObject type) {
        if (type == null || !"in".equals(type.optString("state"))) return false;
        String desc = type.optString("description").trim();
        return !desc.isEmpty() && !desc.equalsIgnoreCase("live") && !desc.equalsIgnoreCase("in progress");
    }

    private static int state(String espn) {
        switch (espn) {
            case "in": return LIVE;
            case "post": return RESULT;
            default: return UPCOMING;
        }
    }

    // ------------------------------------------------------------------ picking a match

    private static boolean india(JSONObject m, boolean women) {
        JSONArray t = m.optJSONArray("teams");
        for (int i = 0; t != null && i < t.length(); i++) {
            String n = t.optJSONObject(i).optString("name").trim().toLowerCase(Locale.ROOT);
            if (n.equals("india") || (women && n.equals("india women"))) return true;
        }
        return false;
    }

    private static boolean ipl(JSONObject m) {
        if (m.optString("league").toLowerCase(Locale.ROOT).contains("indian premier league")) return true;
        JSONArray t = m.optJSONArray("teams");
        if (t == null || t.length() < 2) return false;
        return IPL_TEAMS.contains(t.optJSONObject(0).optString("name").trim().toLowerCase(Locale.ROOT))
                && IPL_TEAMS.contains(t.optJSONObject(1).optString("name").trim().toLowerCase(Locale.ROOT));
    }

    /** Lower is better; MAX_VALUE = not India/IPL or out of the time window. */
    private static int rank(JSONObject m, boolean women, long now) {
        boolean india = india(m, women);
        if (!india && !ipl(m)) return Integer.MAX_VALUE;
        // the main India side first, then IPL, then India in an exhibition event (e.g. Hong Kong Sixes)
        int pref = !india ? 1 : m.optBoolean("minor") ? 2 : 0;
        long start = m.optLong("start");
        int state = m.optInt("state");
        if (state == LIVE) return pref;
        if (state == UPCOMING && start > 0 && start - now < UPCOMING_WINDOW && start - now > -3600_000L) return 10 + pref;
        if (state == RESULT && start > 0 && now - start < RESULT_WINDOW) return 20 + pref;
        return Integer.MAX_VALUE;
    }

    // ------------------------------------------------------------------ building the card

    /** m = the common match object; sum = the match summary for the batter/bowler lines, or null. */
    private Data build(JSONObject m, JSONObject sum) throws Exception {
        Data d = new Data();
        d.state = m.getInt("state");
        d.id = m.getString("id");
        d.start = m.optLong("start");
        d.paused = d.state == LIVE && m.optBoolean("paused");
        d.title = title(m.optString("desc"), m.optString("league"));
        String batting = m.optString("batting");
        JSONArray teams = m.getJSONArray("teams");
        for (int i = 0; i < 2; i++) {
            JSONObject t = teams.getJSONObject(i);
            String name = t.optString("name");
            Team tm = new Team();
            String abbr = t.optString("abbr");
            tm.code = !abbr.isEmpty() ? abbr.toUpperCase(Locale.ROOT)
                    : name.length() <= 4 ? name.toUpperCase(Locale.ROOT) : name.substring(0, 3).toUpperCase(Locale.ROOT);
            tm.flag = flag(name);
            if (tm.flag.isEmpty()) tm.logo = logo(t.optString("logo"));   // IPL teams, West Indies
            tm.batting = d.state == LIVE && !batting.isEmpty() && batting.equals(t.optString("id"));
            String s = t.optString("score");
            int p = s.indexOf(" (");
            tm.score = (p < 0 ? s : s.substring(0, p)).trim();
            Matcher om = OVERS.matcher(s);
            if (om.find()) tm.overs = om.group(1) + " ov";
            d.teams.add(tm);
        }

        if (d.state == UPCOMING) {
            Calendar c = Calendar.getInstance(), today = Calendar.getInstance();
            c.setTimeInMillis(d.start);
            String day = c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) ? "Today" : "Tomorrow";
            String time = DateFormat.format(DateFormat.is24HourFormat(ctx) ? "HH:mm" : "h:mm a", c).toString();
            String type = matchType(m.optString("desc"));
            d.status = "Starts " + day + " " + time + (type.isEmpty() ? "" : "  ·  " + type);
        } else {
            d.status = m.optString("status");
        }

        if (sum != null) {
            try {
                if (d.state == LIVE) liveLines(d, sum, batting);
                else if (d.state == RESULT) resultLines(d, sum);
            } catch (Exception e) {
                Log.w(TAG, "scorecard lines: " + e);   // keep the score without them
                d.lines.clear();
            }
            d.hasCard = !d.lines.isEmpty();
        }
        return d;
    }

    /** Batters at the crease (not out in the current innings) + the bowler of the latest ball. */
    private static void liveLines(Data d, JSONObject sum, String batting) throws Exception {
        if (batting.isEmpty()) return;
        JSONObject comp = sum.getJSONObject("header").getJSONArray("competitions").getJSONObject(0);
        int period = currentPeriod(comp, batting);
        JSONArray rosters = sum.optJSONArray("rosters");
        for (int r = 0; rosters != null && r < rosters.length(); r++) {
            JSONObject ro = rosters.getJSONObject(r);
            if (!batting.equals(ro.getJSONObject("team").optString("id"))) continue;
            JSONArray players = ro.getJSONArray("roster");
            for (int i = 0; i < players.length() && countTag(d, "BAT") < 2; i++) {
                JSONObject pl = players.getJSONObject(i);
                Map<String, String> s = stats(pl, period);
                if (!"1".equals(s.get("batted")) || !"not out".equalsIgnoreCase(s.get("dismissalCard"))) continue;
                d.lines.add(line("BAT", name(pl), num(s.get("runs")) + " (" + num(s.get("ballsFaced")) + ")"));
            }
        }
        // the latest ball's bowler, if that ball is from this innings
        Object cm = comp.opt("commentaries");
        JSONObject latest = null;
        if (cm instanceof JSONObject) {
            JSONObject all = (JSONObject) cm;
            for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                JSONObject e = all.optJSONObject(it.next());
                if (e != null && (latest == null || e.optDouble("sequence") > latest.optDouble("sequence"))) latest = e;
            }
        } else if (cm instanceof JSONArray) {
            JSONArray all = (JSONArray) cm;
            for (int i = 0; i < all.length(); i++) {
                JSONObject e = all.optJSONObject(i);
                if (e != null && (latest == null || e.optDouble("sequence") > latest.optDouble("sequence"))) latest = e;
            }
        }
        JSONObject bowler = latest == null || latest.optInt("period", period) != period ? null : latest.optJSONObject("bowler");
        if (bowler != null && bowler.optJSONObject("athlete") != null) {
            JSONObject team = bowler.optJSONObject("team");
            if (team == null || !batting.equals(team.optString("id")))
                d.lines.add(line("BOWL", str(bowler.getJSONObject("athlete"), "displayName"),
                        bowler.optInt("wickets") + "/" + bowler.optInt("conceded") + " (" + overs(bowler.optString("overs")) + ")"));
        }
    }

    /** Innings number the batting side is in now. */
    private static int currentPeriod(JSONObject comp, String batting) {
        int period = 1;
        JSONArray cs = comp.optJSONArray("competitors");
        for (int i = 0; cs != null && i < cs.length(); i++) {
            JSONObject c = cs.optJSONObject(i);
            if (c == null || !batting.equals(c.optString("id"))) continue;
            JSONArray ls = c.optJSONArray("linescores");
            for (int k = 0; ls != null && k < ls.length(); k++) {
                JSONObject l = ls.optJSONObject(k);
                if (l == null) continue;
                if (l.optInt("isCurrent") == 1) return l.optInt("period", period);
                period = Math.max(period, l.optInt("period", 1));
            }
        }
        return period;
    }

    /** Top scorer and best bowling across the match. */
    private static void resultLines(Data d, JSONObject sum) throws Exception {
        String topName = null, topFig = null, bestName = null, bestFig = null;
        int topRuns = -1, bestW = -1, bestR = Integer.MAX_VALUE;
        JSONArray rosters = sum.optJSONArray("rosters");
        for (int r = 0; rosters != null && r < rosters.length(); r++) {
            JSONArray players = rosters.getJSONObject(r).getJSONArray("roster");
            for (int i = 0; i < players.length(); i++) {
                JSONObject pl = players.getJSONObject(i);
                JSONArray ls = pl.optJSONArray("linescores");
                for (int k = 0; ls != null && k < ls.length(); k++) {
                    Map<String, String> s = stats(ls.getJSONObject(k));
                    if ("1".equals(s.get("batted")) && num(s.get("runs")) > topRuns) {
                        topRuns = num(s.get("runs"));
                        topName = name(pl);
                        topFig = topRuns + " (" + num(s.get("ballsFaced")) + ")";
                    }
                    if ("1".equals(s.get("bowled"))) {
                        int w = num(s.get("wickets")), c = num(s.get("conceded"));
                        if (w > bestW || (w == bestW && c < bestR)) {
                            bestW = w; bestR = c;
                            bestName = name(pl);
                            bestFig = w + "/" + c + " (" + overs(s.get("overs")) + ")";
                        }
                    }
                }
            }
        }
        if (topName != null) d.lines.add(line("TOP", topName, topFig));
        if (bestName != null) d.lines.add(line("BEST", bestName, bestFig));
    }

    /** Stats of one player in one innings (period), flattened to name -> displayValue. */
    private static Map<String, String> stats(JSONObject player, int period) {
        JSONArray ls = player.optJSONArray("linescores");
        for (int k = 0; ls != null && k < ls.length(); k++) {
            JSONObject l = ls.optJSONObject(k);
            if (l != null && l.optInt("period") == period) return stats(l);
        }
        return new HashMap<>();
    }

    private static Map<String, String> stats(JSONObject linescore) {
        Map<String, String> out = new HashMap<>();
        JSONObject st = linescore.optJSONObject("statistics");
        JSONArray cats = st == null ? null : st.optJSONArray("categories");
        for (int i = 0; cats != null && i < cats.length(); i++) {
            JSONArray s = cats.optJSONObject(i) == null ? null : cats.optJSONObject(i).optJSONArray("stats");
            for (int k = 0; s != null && k < s.length(); k++) {
                JSONObject o = s.optJSONObject(k);
                if (o != null) out.put(o.optString("name"), o.optString("displayValue"));
            }
        }
        return out;
    }

    private static String name(JSONObject player) {
        JSONObject a = player.optJSONObject("athlete");
        return a == null ? "" : str(a, "displayName");
    }

    private static int countTag(Data d, String tag) {
        int n = 0;
        for (Line l : d.lines) if (l.tag.equals(tag)) n++;
        return n;
    }

    private static Line line(String tag, String name, String figures) {
        Line l = new Line();
        l.tag = tag; l.name = name; l.figures = figures;
        return l;
    }

    /** "2nd T20I (N), India tour of New Zealand at ..." + league "India tour of New Zealand 2026/27"
     *  -> "2nd T20I  ·  India tour of New Zealand". */
    private static String title(String desc, String league) {
        String[] parts = desc.split(", ");
        String match = parts[0].replaceAll("\\s*\\([^)]*\\)", "").trim();
        String series = stripYear(league);
        if (series.isEmpty() && parts.length > 1) series = parts[1].replaceAll("\\s+at\\s+.*$", "").trim();
        if (match.isEmpty()) return series;
        return series.isEmpty() ? match : match + "  ·  " + series;
    }

    private static String matchType(String desc) {
        Matcher mt = Pattern.compile("\\b(T20I|ODI|Test|T20)\\b").matcher(desc.split(", ")[0]);
        return mt.find() ? mt.group(1) : "";
    }

    /** Country code of a national team's bundled flag ("India Women", "India A" too), or "". */
    private static String flag(String team) {
        String t = team.toLowerCase(Locale.ROOT).replaceAll("\\s+(women|a|u19)$", "").trim();
        switch (t) {
            case "india": return "in";
            case "pakistan": return "pk";
            case "australia": return "au";
            case "england": return "gb_eng";
            case "new zealand": return "nz";
            case "south africa": return "za";
            case "sri lanka": return "lk";
            case "bangladesh": return "bd";
            case "afghanistan": return "af";
            case "zimbabwe": return "zw";
            case "ireland": return "ie";
            case "netherlands": return "nl";
            case "scotland": return "gb_sct";
            case "nepal": return "np";
            case "united arab emirates": case "uae": return "ae";
            case "united states of america": case "usa": case "united states": return "us";
            case "oman": return "om";
            case "namibia": return "na";
            case "canada": return "ca";
            default: return "";
        }
    }

    /** Team logo, downloaded once into the app's files; returns the local path or "". */
    private String logo(String url) {
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

    // ------------------------------------------------------------------ helpers

    private static String str(JSONObject o, String k) { return o.isNull(k) ? "" : o.optString(k).trim(); }

    private static int num(String s) {
        try { return s == null || s.isEmpty() ? 0 : (int) Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    /** "1.0" -> "1", "3.4" -> "3.4". */
    private static String overs(String o) {
        if (o == null || o.isEmpty()) return "0";
        return o.endsWith(".0") ? o.substring(0, o.length() - 2) : o;
    }

    /** "India tour of New Zealand 2026/27" -> "India tour of New Zealand". */
    private static String stripYear(String s) { return s.replaceAll("\\s*\\d{4}(?:[-/]\\d{2,4})?$", "").trim(); }

    private static long isoMs(String s) {
        if (s.isEmpty()) return 0;
        for (String fmt : new String[] {"yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm'Z'"}) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(fmt, Locale.ROOT);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) { }
        }
        return 0;
    }

    private static String ymd(long t) { return new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date(t)); }

    // ------------------------------------------------------------------ cache (de)serialisation

    private static JSONObject toJson(Data d) throws Exception {
        JSONObject j = new JSONObject();
        j.put("state", d.state).put("id", d.id).put("title", d.title).put("status", d.status)
                .put("time", d.time).put("start", d.start).put("hasCard", d.hasCard)
                .put("paused", d.paused);
        JSONArray ts = new JSONArray();
        for (Team t : d.teams) ts.put(new JSONObject().put("code", t.code).put("score", t.score)
                .put("overs", t.overs).put("logo", t.logo).put("flag", t.flag).put("batting", t.batting));
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
        d.time = j.optLong("time"); d.start = j.optLong("start"); d.hasCard = j.optBoolean("hasCard");
        d.paused = j.optBoolean("paused");
        JSONArray ts = j.optJSONArray("teams");
        if (ts != null) for (int i = 0; i < ts.length(); i++) {
            JSONObject o = ts.optJSONObject(i);
            Team t = new Team();
            t.code = o.optString("code"); t.score = o.optString("score"); t.overs = o.optString("overs");
            t.logo = o.optString("logo"); t.flag = o.optString("flag"); t.batting = o.optBoolean("batting");
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
