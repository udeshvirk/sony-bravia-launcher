package com.udesh.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Random;

/**
 * Clock + date (and the cricket score, only while an India/IPL match is live) drawn in the
 * bottom-left corner on top of whatever screensaver is running (Google Photos ambient mode).
 * Drawn as an accessibility overlay by ColorKeyService, so no extra permission is needed.
 * It drifts a little every few minutes so nothing sits in exactly the same place for hours.
 */
final class ScreensaverOverlay {
    private static final String TAG = "TvHomeSaver";
    private static final long DRIFT_MS = 4 * 60_000L;

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Cricket cricket;
    private final float dp;
    private final Random rnd = new Random();

    private FrameLayout root;
    private LinearLayout panel, score;
    private TextView clock, date, scoreA, scoreB, scoreStatus;
    private View shade;
    private boolean showing, registered;
    /** true: our own clock + date bottom-left; false: only the live score, bottom-right above ambient mode's clock. */
    private final boolean ownClock;

    ScreensaverOverlay(AccessibilityService s) {
        svc = s;
        wm = s.getSystemService(WindowManager.class);
        cricket = new Cricket(s);
        dp = s.getResources().getDisplayMetrics().density;
        ownClock = Config.bool(s, R.bool.screensaver_clock);
    }

    void start() {
        IntentFilter f = new IntentFilter(Intent.ACTION_DREAMING_STARTED);
        f.addAction(Intent.ACTION_DREAMING_STOPPED);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        svc.registerReceiver(rx, f);
        registered = true;
    }

    void stop() {
        if (registered) { try { svc.unregisterReceiver(rx); } catch (Exception ignored) {} registered = false; }
        hide();
    }

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_DREAMING_STARTED.equals(a)) show();
            else if (Intent.ACTION_DREAMING_STOPPED.equals(a) || Intent.ACTION_SCREEN_OFF.equals(a)) hide();
            else if (showing) updateClock();
        }
    };

    // ------------------------------------------------------------------ show / hide

    private void show() {
        if (showing) return;
        if (root == null) build();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        try {
            wm.addView(root, lp);
            showing = true;
        } catch (Exception e) {
            Log.w(TAG, "overlay", e);
            return;
        }
        updateClock();
        renderScore(cricket.cached());
        drift.run();
        if (cricket.enabled()) scoreTick.run();
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(1200).start();
        Log.i(TAG, "shown");
    }

    private void hide() {
        h.removeCallbacksAndMessages(null);
        if (!showing) return;
        showing = false;
        try { wm.removeViewImmediate(root); } catch (Exception ignored) {}
        Log.i(TAG, "hidden");
    }

    // ------------------------------------------------------------------ view

    private void build() {
        root = new FrameLayout(svc);
        // soft shade in the corner so white text reads on bright photos
        shade = new View(svc);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{0x99000000, 0x4D000000, 0x00000000});
        g.setGradientCenter(0f, 1f);
        g.setGradientRadius(px(620));
        shade.setBackground(g);
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        shade.setVisibility(ownClock ? View.VISIBLE : View.GONE);

        panel = new LinearLayout(svc);
        panel.setOrientation(LinearLayout.VERTICAL);
        clock = text(84, 200, Color.WHITE);
        clock.setLetterSpacing(-0.02f);
        panel.addView(clock);
        clock.setVisibility(ownClock ? View.VISIBLE : View.GONE);
        date = text(20, 300, 0xD9FFFFFF);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.topMargin = px(4);
        dl.leftMargin = px(4);
        panel.addView(date, dl);
        date.setVisibility(ownClock ? View.VISIBLE : View.GONE);

        // live score: two lines under the date, only during a live match
        score = new LinearLayout(svc);
        score.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(svc);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text(11, 600, Color.WHITE);
        badge.setText("LIVE");
        badge.setLetterSpacing(0.1f);
        badge.setPadding(px(7), px(3), px(7), px(3));
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(0xFFE5484D);
        bb.setCornerRadius(px(5));
        badge.setBackground(bb);
        badge.setShadowLayer(0, 0, 0, 0);
        row.addView(badge);
        scoreA = text(20, 500, Color.WHITE);
        scoreA.setEllipsize(null);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.leftMargin = px(10);
        row.addView(scoreA, sl);
        View dot = new View(svc);
        GradientDrawable dd = new GradientDrawable();
        dd.setShape(GradientDrawable.OVAL);
        dd.setColor(0x80FFFFFF);
        dot.setBackground(dd);
        LinearLayout.LayoutParams dl2 = new LinearLayout.LayoutParams(px(4), px(4));
        dl2.leftMargin = px(12);
        dl2.rightMargin = px(12);
        row.addView(dot, dl2);
        scoreB = text(20, 400, 0xB3FFFFFF);
        scoreB.setEllipsize(null);
        row.addView(scoreB);
        score.addView(row, new LinearLayout.LayoutParams(-2, -2));
        scoreStatus = text(16, 400, 0xF2FFD27A);
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(-2, -2);
        stl.topMargin = px(6);
        score.addView(scoreStatus, stl);
        score.setVisibility(View.GONE);
        LinearLayout.LayoutParams scl = new LinearLayout.LayoutParams(-2, -2);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, -2);
        if (ownClock) {
            scl.topMargin = px(18);
            scl.leftMargin = px(4);
            pl.gravity = Gravity.BOTTOM | Gravity.START;
            pl.leftMargin = px(64);
            pl.bottomMargin = px(56);
        } else {
            // small frosted card, right-aligned just above ambient mode's clock + weather
            score.setGravity(Gravity.END);
            score.setPadding(px(16), px(12), px(16), px(12));
            GradientDrawable cb = new GradientDrawable();
            cb.setColor(0xB3101014);
            cb.setStroke(Math.max(1, px(1)), 0x24FFFFFF);
            cb.setCornerRadius(px(16));
            score.setBackground(cb);
            pl.gravity = Gravity.BOTTOM | Gravity.END;
            pl.rightMargin = px(20);
            pl.bottomMargin = px(84);
        }
        panel.addView(score, scl);
        root.addView(panel, pl);
    }

    private void updateClock() {
        if (clock == null) return;
        Calendar c = Calendar.getInstance();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(svc) ? "HH:mm" : "h:mm", c));
        date.setText(DateFormat.format("EEEE, d MMMM", c));
    }

    private void renderScore(Cricket.Data d) {
        if (score == null) return;
        boolean live = d != null && d.state == Cricket.LIVE && d.teams.size() == 2
                && System.currentTimeMillis() - d.time < 30 * 60_000L;
        if (!ownClock) root.setVisibility(live ? View.VISIBLE : View.GONE);
        if (!live) { score.setVisibility(View.GONE); return; }
        // batting side first, bright; the other side dimmer
        Cricket.Team a = d.teams.get(0), b = d.teams.get(1);
        if (b.batting) { Cricket.Team t = a; a = b; b = t; }
        scoreA.setText(teamText(a, true));
        scoreB.setText(teamText(b, false));
        scoreStatus.setText(d.status);
        scoreStatus.setVisibility(d.status.isEmpty() ? View.GONE : View.VISIBLE);
        score.setVisibility(View.VISIBLE);
    }

    private static String teamText(Cricket.Team t, boolean withOvers) {
        String s = t.code;
        if (!t.score.isEmpty()) {
            s += "  " + t.score;
            if (withOvers && t.batting && !t.overs.isEmpty()) s += "  (" + t.overs.replace(" ov", "") + ")";
        }
        return s;
    }

    // ------------------------------------------------------------------ timers

    /** Nudge the panel to a nearby spot every few minutes. */
    private final Runnable drift = new Runnable() {
        @Override public void run() {
            if (!showing) return;
            float dx = ownClock ? px(rnd.nextInt(48)) : -px(rnd.nextInt(24));
            panel.animate().translationX(dx).translationY(-px(rnd.nextInt(ownClock ? 40 : 16)))
                    .setDuration(3000).start();
            h.postDelayed(this, DRIFT_MS);
        }
    };

    private final Runnable scoreTick = new Runnable() {
        @Override public void run() {
            if (!showing) return;
            long wait = interval() - cricket.sinceAttemptMs();
            if (wait <= 0) {
                cricket.fetch(d -> { if (showing) renderScore(d); });
                wait = interval();
            }
            h.postDelayed(this, Math.max(30_000L, wait));
        }
    };

    private long interval() {
        return cricket.refreshMs(R.integer.screensaver_cricket_refresh_minutes);
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(svc);
        t.setTextSize(sp);
        t.setTypeface(Fonts.outfit(svc, weight));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setShadowLayer(px(8), 0, px(1), 0x80000000);
        return t;
    }

    private int px(float v) { return Math.round(v * dp); }
}
