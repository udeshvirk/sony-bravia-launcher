package com.udesh.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class HomeActivity extends Activity {
    private static final String TAG = "TvHome";
    static final String ACTION_SHOW_APPS = "com.udesh.tvhome.SHOW_APPS";
    private static final int TOP = 0, DOCK = 1, GRID = 2, MENU = 3;
    private static final int COLS = 4;
    private static final float TILE_SCALE = 1.1f, PILL_SCALE = 1.06f;

    private float dp;
    private int sw, sh;
    // layout metrics (px)
    private int tileW, tileH, gap, bandPadX, bandPadY, bandW, bandH, bandX, bandY;
    private int cellW, rowH, gridX, gridTop, gridAnchor, titleH;

    private final AppRepo repo = new AppRepo();
    private Weather weather;
    private SharedPreferences prefs;
    private final Handler h = new Handler(Looper.getMainLooper());

    private FrameLayout root, page, band;
    private ImageView wallpaper;
    private View scrim, glow, wallShade;
    private GradientDrawable glowDrawable;
    private int glowColor = 0xFF8FA8FF;
    private ValueAnimator glowAnim;
    private HighlightView hl;
    private TextView greeting, clock, date, title, wxTemp, wxRange, wifiText;
    private ImageView wxIcon;
    private LinearLayout wxRow, menu;
    private final View[] pills = new View[3];
    private final List<View> dockCards = new ArrayList<>();
    private RecyclerView grid;
    private GridAdapter adapter;
    private final List<InputItem> inputs = new ArrayList<>();

    private int zone = DOCK, topIdx = 0, dockIdx = 0, gridIdx = 0, menuIdx = 0;
    private View scaled;
    private boolean centerDown, resumed, visible;
    private long wallpaperStamp = -1;
    private ConnectivityManager.NetworkCallback netCb;

    // long-press on a dock/grid tile picks it up; D-pad then swaps it with its neighbour.
    private static final long LONG_PRESS_MS = 500;
    private boolean reordering, longPressFired;
    private final Runnable longPressRunnable = this::onLongPressCenter;

    // double-tap Home while already on the home screen relaunches the last app you opened.
    private static final long HOME_DOUBLE_MS = 650;
    private long lastHomeMs;
    private String lastApp;

    static final class InputItem {
        String label, port, inputId;
        boolean live;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        Rect bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
        sw = bounds.width();
        sh = bounds.height();
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        lastApp = prefs.getString("lastApp", null);
        weather = new Weather(this);
        metrics();
        buildUi();
        loadApps();
        restoreFocus();
        if (ACTION_SHOW_APPS.equals(getIntent().getAction()) && !repo.grid.isEmpty()) { zone = GRID; gridIdx = 0; }

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
        getExternalFilesDir(null); // creates the wallpaper folder
    }

    @Override protected void onDestroy() {
        unregisterReceiver(pkgReceiver);
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (ACTION_SHOW_APPS.equals(intent.getAction())) {
            hideMenu();
            if (visible && zone == GRID) zone = repo.dock.isEmpty() ? TOP : DOCK; // pressed again: back
            else if (!repo.grid.isEmpty()) { zone = GRID; gridIdx = 0; }
            applyFocus(visible);
            return;
        }
        if (reordering) { reordering = false; hl.setReorderMode(false); }

        // Home pressed twice quickly while already on the home screen: jump back into the last
        // app you opened, instead of just re-focusing the dock.
        long now = SystemClock.elapsedRealtime();
        boolean doubleTap = visible && lastApp != null && (now - lastHomeMs) < HOME_DOUBLE_MS;
        lastHomeMs = now;
        if (doubleTap) {
            lastHomeMs = 0;
            launch(lastApp);
            return;
        }

        // Home pressed while already on the home screen: back to the dock.
        // (Android pauses us before onNewIntent, so "visible" = not stopped.)
        if (visible) {
            hideMenu();
            zone = repo.dock.isEmpty() ? TOP : DOCK;
            applyFocus(true);
        }
    }

    @Override protected void onStart() { super.onStart(); visible = true; }
    @Override protected void onStop() { visible = false; super.onStop(); }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, f);
        updateClock();
        loadWallpaper();
        startWifiUpdates();
        // weather: fetch if the cache is old, then every N minutes while visible
        renderWeather(weather.cached());
        long refresh = getResources().getInteger(R.integer.weather_refresh_minutes) * 60_000L;
        long age = weather.ageMs();
        if (age >= refresh) {
            weather.fetch(this::renderWeather);
            h.postDelayed(weatherTick, refresh);
        } else {
            h.postDelayed(weatherTick, refresh - age);
        }
        page.post(() -> applyFocus(false));
    }

    @Override protected void onPause() {
        resumed = false;
        if (reordering) { reordering = false; hl.setReorderMode(false); }
        unregisterReceiver(timeReceiver);
        h.removeCallbacks(weatherTick);
        stopWifiUpdates();
        super.onPause();
    }

    private final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            weather.fetch(HomeActivity.this::renderWeather);
            h.postDelayed(this, getResources().getInteger(R.integer.weather_refresh_minutes) * 60_000L);
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            h.removeCallbacks(reload);
            h.postDelayed(reload, 600);
        }
    };
    private final Runnable reload = () -> { loadApps(); page.post(() -> applyFocus(false)); };

    // ------------------------------------------------------------------ layout

    private int px(float v) { return Math.round(v * dp); }

    private void metrics() {
        tileW = px(184); tileH = px(104); gap = px(24);
        bandPadX = px(28); bandPadY = px(26);
        bandW = COLS * tileW + (COLS - 1) * gap + 2 * bandPadX;
        bandH = tileH + 2 * bandPadY;
        bandX = (sw - bandW) / 2;
        bandY = sh - px(28) - bandH;
        cellW = tileW + gap;
        rowH = tileH + px(54);
        gridX = bandX + bandPadX - gap / 2;
        gridTop = sh + px(24);
        gridAnchor = px(196);
        titleH = px(70);
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTypeface(Fonts.outfit(this, weight));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaper.setVisibility(View.GONE);
        root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));
        // readability shade over photos: dark at the top-left (clock) and bottom (dock), clear in between
        wallShade = new View(this);
        wallShade.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xBF000000, 0x59000000, 0x26000000, 0x8C000000, 0xD9000000}),
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x8C000000, 0x1A000000, 0x00000000})}));
        wallShade.setVisibility(View.GONE);
        root.addView(wallShade, new FrameLayout.LayoutParams(-1, -1));
        scrim = new View(this);
        scrim.setBackgroundColor(Color.BLACK);
        scrim.setAlpha(0f);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        page = new FrameLayout(this);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        root.addView(page, new FrameLayout.LayoutParams(-1, sh));

        // ambient glow behind the big title (radial gradient, squashed into an ellipse; no blur)
        glowDrawable = new GradientDrawable();
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(px(270));
        glow = new View(this);
        glow.setBackground(glowDrawable);
        glow.setScaleY(0.5f);
        int glowSize = px(540);
        page.addView(glow, at(bandX - px(150), bandY - px(14) - titleH / 2 - glowSize / 2, glowSize, glowSize));

        // greeting, clock, date, weather (top left)
        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.VERTICAL);
        greeting = text(20, 300, 0xB8FFFFFF);
        clock = text(92, 140, Color.WHITE);
        clock.setLetterSpacing(-0.02f);
        date = text(20, 300, 0x9EFFFFFF);
        tl.addView(greeting);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = px(4);
        clp.leftMargin = -px(4);
        tl.addView(clock, clp);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = px(2);
        tl.addView(date, dlp);

        wxRow = new LinearLayout(this);
        wxRow.setOrientation(LinearLayout.HORIZONTAL);
        wxRow.setGravity(Gravity.CENTER_VERTICAL);
        wxIcon = new ImageView(this);
        wxRow.addView(wxIcon, new LinearLayout.LayoutParams(px(24), px(24)));
        wxTemp = text(20, 400, Color.WHITE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = px(8);
        wxRow.addView(wxTemp, tlp);
        wxRange = text(17, 300, 0x9EFFFFFF);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.leftMargin = px(14);
        wxRow.addView(wxRange, rlp);
        wxRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -2);
        wlp.topMargin = px(12);
        tl.addView(wxRow, wlp);
        page.addView(tl, at(bandX + px(4), px(34), -2, -2));

        // pills (top right)
        LinearLayout pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pillRow.setClipChildren(false);
        pills[0] = pill(R.drawable.ic_input, "Inputs");
        pills[1] = pill(R.drawable.ic_wifi, "Wi-Fi");
        wifiText = (TextView) ((ViewGroup) pills[1]).getChildAt(1);
        pills[2] = pill(R.drawable.ic_settings, "Settings");
        for (int i = 0; i < 3; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(40));
            if (i > 0) lp.leftMargin = px(12);
            pillRow.addView(pills[i], lp);
        }
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2);
        plp.gravity = Gravity.TOP | Gravity.END;
        plp.topMargin = px(36);
        plp.rightMargin = sw - (bandX + bandW);
        page.addView(pillRow, plp);

        // big title of the focused dock app
        title = text(46, 300, 0xF2FFFFFF);
        title.setGravity(Gravity.BOTTOM | Gravity.START);
        page.addView(title, at(bandX + px(6), bandY - px(14) - titleH, bandW, titleH));

        // dock band
        band = new FrameLayout(this);
        band.setClipChildren(false);
        GradientDrawable bandBg = new GradientDrawable();
        bandBg.setColor(0x17FFFFFF);
        bandBg.setStroke(Math.max(1, px(1)), 0x12FFFFFF);
        bandBg.setCornerRadius(px(30));
        band.setBackground(bandBg);
        page.addView(band, at(bandX, bandY, bandW, bandH));

        // app grid below the screen
        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, COLS) {
            @Override public boolean canScrollVertically() { return false; }
        });
        grid.setItemAnimator(null);
        grid.setFocusable(false);
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new GridAdapter();
        grid.setAdapter(adapter);
        page.addView(grid, at(gridX, gridTop, COLS * cellW, rowH));

        // inputs dropdown
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable mb = new GradientDrawable();
        mb.setColor(0xEB1C1C20);
        mb.setStroke(Math.max(1, px(1)), 0x2EFFFFFF);
        mb.setCornerRadius(px(18));
        menu.setBackground(mb);
        menu.setPadding(px(8), px(8), px(8), px(8));
        menu.setVisibility(View.GONE);
        page.addView(menu, at(0, 0, px(250), -2));

        hl = new HighlightView(this);
        page.addView(hl, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    private View pill(int icon, String label) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(px(16), 0, px(18), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x24FFFFFF);
        bg.setStroke(Math.max(1, px(1)), 0x2EFFFFFF);
        bg.setCornerRadius(px(20));
        p.setBackground(bg);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setAlpha(0.9f);
        p.addView(iv, new LinearLayout.LayoutParams(px(18), px(18)));
        TextView t = text(15, 400, 0xEBFFFFFF);
        t.setText(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = px(8);
        p.addView(t, lp);
        return p;
    }

    private View card(AppRepo.App a) {
        FrameLayout c = new FrameLayout(this);
        final float r = px(12);
        c.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        c.setClipToOutline(true);
        if (a.banner != null) {
            c.setBackgroundColor(0xFF1E1E22);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(a.banner);
            c.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            c.setBackgroundColor(0xFF2A2A30);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(a.icon);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(px(44), px(44));
            ilp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            ilp.topMargin = px(16);
            c.addView(iv, ilp);
            TextView t = text(13, 400, 0xDDFFFFFF);
            t.setText(a.label);
            t.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, -2);
            tlp.gravity = Gravity.BOTTOM;
            tlp.bottomMargin = px(12);
            tlp.leftMargin = tlp.rightMargin = px(8);
            c.addView(t, tlp);
        }
        return c;
    }

    // ------------------------------------------------------------------ apps

    private void loadApps() {
        repo.load(this);
        rebuildDockBand();
        int rows = Math.max(1, (repo.grid.size() + COLS - 1) / COLS);
        int gh = rows * rowH;
        grid.getLayoutParams().height = gh;
        page.getLayoutParams().height = gridTop + gh + px(80);
        adapter.notifyDataSetChanged();
        page.requestLayout();
        dockIdx = clamp(dockIdx, 0, repo.dock.size() - 1);
        gridIdx = clamp(gridIdx, 0, repo.grid.size() - 1);
        if (zone == DOCK && repo.dock.isEmpty()) zone = TOP;
        if (zone == GRID && repo.grid.isEmpty()) zone = repo.dock.isEmpty() ? TOP : DOCK;
    }

    private static int clamp(int v, int lo, int hi) { return hi < lo ? lo : Math.max(lo, Math.min(hi, v)); }

    private void rebuildDockBand() {
        band.removeAllViews();
        dockCards.clear();
        for (int i = 0; i < repo.dock.size(); i++) {
            View c = card(repo.dock.get(i));
            band.addView(c, at(bandPadX + i * (tileW + gap), bandPadY, tileW, tileH));
            dockCards.add(c);
        }
    }

    // ------------------------------------------------------------------ reorder

    private void onLongPressCenter() {
        longPressFired = true;
        if (zone != DOCK && zone != GRID) return;
        if ((zone == DOCK && repo.dock.size() < 2) || (zone == GRID && repo.grid.size() < 2)) return;
        reordering = true;
        hl.setReorderMode(true);
    }

    private void swapDock(int i, int j) {
        java.util.Collections.swap(repo.dock, i, j);
        dockIdx = j;
        rebuildDockBand();
        saveOrder("dockOrder", repo.dock);
    }

    private void swapGrid(int i, int j) {
        java.util.Collections.swap(repo.grid, i, j);
        gridIdx = j;
        adapter.notifyItemChanged(i);
        adapter.notifyItemChanged(j);
        saveOrder("gridOrder", repo.grid);
    }

    private void saveOrder(String key, List<AppRepo.App> list) {
        StringBuilder sb = new StringBuilder();
        for (AppRepo.App a : list) { if (sb.length() > 0) sb.append(','); sb.append(a.pkg); }
        prefs.edit().putString(key, sb.toString()).apply();
    }

    private final class GridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout cell = new FrameLayout(HomeActivity.this);
            cell.setClipChildren(false);
            cell.setLayoutParams(new RecyclerView.LayoutParams(cellW, rowH));
            return new RecyclerView.ViewHolder(cell) {};
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
            FrameLayout cell = (FrameLayout) vh.itemView;
            cell.removeAllViews();
            AppRepo.App a = repo.grid.get(pos);
            View c = card(a);
            cell.addView(c, at(gap / 2, 0, tileW, tileH));
            TextView label = text(15, 400, 0xE6FFFFFF);
            label.setText(a.label);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            label.setAlpha(zone == GRID && pos == gridIdx ? 1f : 0f);
            cell.addView(label, at(0, tileH + px(16), cellW, -2));
            boolean f = zone == GRID && pos == gridIdx;
            c.setScaleX(f ? TILE_SCALE : 1f);
            c.setScaleY(f ? TILE_SCALE : 1f);
            if (f) scaled = c;
        }
        @Override public int getItemCount() { return repo.grid.size(); }
    }

    // ------------------------------------------------------------------ focus

    private void restoreFocus() {
        int z = prefs.getInt("zone", DOCK);
        String pkg = prefs.getString("pkg", "");
        topIdx = prefs.getInt("top", 0);
        zone = repo.dock.isEmpty() ? TOP : DOCK;
        for (int i = 0; i < repo.dock.size(); i++) if (repo.dock.get(i).pkg.equals(pkg)) { dockIdx = i; zone = DOCK; }
        if (z == GRID) for (int i = 0; i < repo.grid.size(); i++) if (repo.grid.get(i).pkg.equals(pkg)) { gridIdx = i; zone = GRID; }
        dockIdx = prefs.getInt("dockIdx", dockIdx);
        dockIdx = clamp(dockIdx, 0, repo.dock.size() - 1);
    }

    private void saveFocus() {
        String pkg = "";
        if (zone == DOCK && dockIdx < repo.dock.size()) pkg = repo.dock.get(dockIdx).pkg;
        if (zone == GRID && gridIdx < repo.grid.size()) pkg = repo.grid.get(gridIdx).pkg;
        prefs.edit().putInt("zone", zone == GRID ? GRID : DOCK).putString("pkg", pkg)
                .putInt("top", topIdx).putInt("dockIdx", dockIdx).apply();
    }

    private View gridCell(int pos) {
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        return lm == null ? null : lm.findViewByPosition(pos);
    }

    private RectF rectInPage(View v) {
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        page.offsetDescendantRectToMyCoords(v, r);
        return new RectF(r);
    }

    private static RectF scaled(RectF r, float s, float pad) {
        float cx = r.centerX(), cy = r.centerY();
        float hw = r.width() * s / 2 + pad, hh = r.height() * s / 2 + pad;
        return new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    private void setScaled(View v, float s) {
        if (scaled == v) return;
        if (scaled != null) {
            scaled.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
            View p = (View) scaled.getParent();
            if (p != null && p.getParent() == grid) ((ViewGroup) p).getChildAt(1).animate().alpha(0f).setDuration(150).start();
        }
        scaled = v;
        if (v != null && s != 1f) {
            v.animate().scaleX(s).scaleY(s).setDuration(320).setInterpolator(new OvershootInterpolator(1.6f)).start();
        }
    }

    private void applyFocus(boolean animate) {
        View target = null;
        RectF rect = null;
        float radius = px(16);
        switch (zone) {
            case TOP: {
                target = pills[topIdx];
                setScaled(target, PILL_SCALE);
                rect = scaled(rectInPage(target), PILL_SCALE, px(3));
                radius = rect.height() / 2;
                break;
            }
            case DOCK: {
                if (dockIdx >= dockCards.size()) break;
                target = dockCards.get(dockIdx);
                setScaled(target, TILE_SCALE);
                rect = scaled(rectInPage(target), TILE_SCALE, px(5));
                break;
            }
            case GRID: {
                View cell = gridCell(gridIdx);
                if (cell == null) { page.post(() -> applyFocus(animate)); return; }
                target = ((ViewGroup) cell).getChildAt(0);
                setScaled(target, TILE_SCALE);
                ((ViewGroup) cell).getChildAt(1).animate().alpha(1f).setDuration(200).start();
                rect = scaled(rectInPage(target), TILE_SCALE, px(5));
                break;
            }
            case MENU: {
                target = menu.getChildAt(menuIdx);
                setScaled(null, 1f);
                rect = rectInPage(target);
                radius = px(12);
                break;
            }
        }
        if (rect != null) hl.moveTo(rect, radius, animate);

        // page position: hero at rest, or scrolled so the focused grid row sits at the anchor
        float ty = zone == GRID ? -(gridTop + (gridIdx / COLS) * rowH - gridAnchor) : 0f;
        if (animate) {
            page.animate().translationY(ty).setDuration(420).setInterpolator(new DecelerateInterpolator(2f)).start();
            scrim.animate().alpha(zone == GRID ? 0.75f : 0f).setDuration(420).start();
        } else {
            page.setTranslationY(ty);
            scrim.setAlpha(zone == GRID ? 0.75f : 0f);
        }

        // big title + glow follow the dock selection
        if (!repo.dock.isEmpty()) {
            AppRepo.App a = repo.dock.get(clamp(dockIdx, 0, repo.dock.size() - 1));
            if (!a.label.contentEquals(title.getText())) {
                title.setText(a.label);
                if (animate) { title.setAlpha(0.3f); title.animate().alpha(1f).setDuration(260).start(); }
            }
            setGlow(a.glow, animate);
        } else {
            title.setText("");
        }
        saveFocus();
    }

    private void setGlow(int color, boolean animate) {
        if (glowAnim != null) glowAnim.cancel();
        if (!animate) { paintGlow(color); return; }
        glowAnim = ValueAnimator.ofArgb(glowColor, color);
        glowAnim.setDuration(380);
        glowAnim.addUpdateListener(a -> paintGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void paintGlow(int c) {
        glowColor = c;
        int rgb = c & 0x00FFFFFF;
        glowDrawable.setColors(new int[]{0x4D000000 | rgb, 0x1A000000 | rgb, 0x00000000 | rgb});
        title.setShadowLayer(px(22), 0, 0, 0x99000000 | rgb);
    }

    // ------------------------------------------------------------------ keys

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(k);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        centerDown = true;
                        longPressFired = false;
                        h.postDelayed(longPressRunnable, LONG_PRESS_MS);
                    }
                } else {
                    h.removeCallbacks(longPressRunnable);
                    if (centerDown && !longPressFired) {
                        centerDown = false;
                        if (reordering) { reordering = false; hl.setReorderMode(false); }
                        else activate();
                    } else centerDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) {
                    if (reordering) { reordering = false; hl.setReorderMode(false); }
                    else back();
                }
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void move(int k) {
        boolean L = k == KeyEvent.KEYCODE_DPAD_LEFT, R = k == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean U = k == KeyEvent.KEYCODE_DPAD_UP, D = k == KeyEvent.KEYCODE_DPAD_DOWN;
        int nd = repo.dock.size(), ng = repo.grid.size();
        switch (zone) {
            case TOP:
                if (L) { if (topIdx == 0) return; topIdx--; }          // ends of the top bar stop here
                else if (R) { if (topIdx == pills.length - 1) return; topIdx++; }
                else if (D) { if (nd > 0) zone = DOCK; else if (ng > 0) zone = GRID; else return; }
                else return;
                break;
            case DOCK:
                if (reordering) {
                    if (L && dockIdx > 0) swapDock(dockIdx, dockIdx - 1);
                    else if (R && dockIdx < nd - 1) swapDock(dockIdx, dockIdx + 1);
                    else return;
                    break;
                }
                if (L) { if (dockIdx == 0) return; dockIdx--; }
                else if (R) { if (dockIdx >= nd - 1) return; dockIdx++; }
                else if (U) zone = TOP;
                else if (D) { if (ng == 0) return; zone = GRID; gridIdx = Math.min(dockIdx, ng - 1); }
                break;
            case GRID: {
                int col = gridIdx % COLS, row = gridIdx / COLS;
                if (reordering) {
                    int target = -1;
                    if (L && col > 0) target = gridIdx - 1;
                    else if (R && col < COLS - 1 && gridIdx + 1 < ng) target = gridIdx + 1;
                    else if (U && row > 0) target = gridIdx - COLS;
                    else if (D && (row + 1) * COLS < ng) target = gridIdx + COLS;
                    if (target < 0) return;
                    swapGrid(gridIdx, target);
                    break;
                }
                if (L) { if (col == 0) return; gridIdx--; }
                else if (R) { if (col == COLS - 1 || gridIdx + 1 >= ng) return; gridIdx++; }
                else if (U) {
                    if (row == 0) { if (nd > 0) { zone = DOCK; dockIdx = Math.min(col, nd - 1); } else zone = TOP; }
                    else gridIdx -= COLS;                       // exactly one row
                } else if (D) {
                    if ((row + 1) * COLS >= ng) return;
                    gridIdx = Math.min(gridIdx + COLS, ng - 1); // exactly one row, clamped to its last tile
                }
                break;
            }
            case MENU:
                if (U) { if (menuIdx == 0) { hideMenu(); zone = TOP; } else menuIdx--; }
                else if (D) { if (menuIdx >= menu.getChildCount() - 1) return; menuIdx++; }
                else return;
                break;
        }
        applyFocus(true);
    }

    private void back() {
        if (zone == MENU) { hideMenu(); zone = TOP; applyFocus(true); }
        else if (zone == GRID || zone == TOP) { zone = repo.dock.isEmpty() ? TOP : DOCK; applyFocus(true); }
    }

    private void activate() {
        switch (zone) {
            case TOP:
                if (topIdx == 0) showMenu();
                else if (topIdx == 1) start(new Intent(Settings.ACTION_WIFI_SETTINGS));
                else start(new Intent(Settings.ACTION_SETTINGS));
                break;
            case DOCK:
                launch(repo.dock.get(dockIdx).pkg);
                break;
            case GRID:
                launch(repo.grid.get(gridIdx).pkg);
                break;
            case MENU:
                InputItem it = inputs.get(menuIdx);
                hideMenu();
                zone = TOP;
                applyFocus(false);
                switchInput(it);
                break;
        }
    }

    private void launch(String pkg) {
        Intent i = AppRepo.launchIntent(this, pkg);
        if (i == null) return;
        start(i);
        if (!pkg.equals(lastApp)) {
            lastApp = pkg;
            prefs.edit().putString("lastApp", pkg).apply();
        }
    }

    private void start(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "start failed: " + i, e);
        }
    }

    // ------------------------------------------------------------------ inputs

    private void loadInputs() {
        inputs.clear();
        TvInputManager tim = getSystemService(TvInputManager.class);
        if (tim == null) return;
        List<InputItem> hdmi = new ArrayList<>(), av = new ArrayList<>();
        String tuner = null;
        for (TvInputInfo i : tim.getTvInputList()) {
            if (i.getParentId() != null || i.isHidden(this)) continue;
            CharSequence custom = i.loadCustomLabel(this);
            String port = String.valueOf(i.loadLabel(this));
            int t = i.getType();
            InputItem it = new InputItem();
            it.inputId = i.getId();
            if (custom != null && custom.length() > 0 && !port.contentEquals(custom)) {
                it.label = custom.toString();
                it.port = port;      // e.g. "Game" with "HDMI 1" shown dimmed
            } else {
                it.label = port;
            }
            if (t == TvInputInfo.TYPE_HDMI) hdmi.add(it);
            else if (t == TvInputInfo.TYPE_COMPOSITE || t == TvInputInfo.TYPE_COMPONENT || t == TvInputInfo.TYPE_SCART
                    || t == TvInputInfo.TYPE_SVIDEO || t == TvInputInfo.TYPE_VGA || t == TvInputInfo.TYPE_DVI) av.add(it);
            else if (t == TvInputInfo.TYPE_TUNER && i.getServiceInfo().packageName.equals("com.sony.dtv.tvinput.tuner")
                    && tuner == null) tuner = i.getId();
        }
        hdmi.sort((a, b) -> a.inputId.compareTo(b.inputId)); // hardware order = port order
        inputs.addAll(hdmi);
        inputs.addAll(av);
        InputItem live = new InputItem();
        live.label = "Live TV";
        live.inputId = tuner;
        live.live = true;
        inputs.add(live);
    }

    private void showMenu() {
        loadInputs();
        menu.removeAllViews();
        for (InputItem it : inputs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(16), 0, px(16), 0);
            TextView t = text(16, 400, 0xEBFFFFFF);
            t.setText(it.label);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            if (it.port != null) {
                TextView p = text(13, 400, 0x80FFFFFF);
                p.setText(it.port);
                row.addView(p, new LinearLayout.LayoutParams(-2, -2));
            }
            menu.addView(row, new LinearLayout.LayoutParams(-1, px(42)));
        }
        RectF p = rectInPage(pills[0]);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menu.getLayoutParams();
        lp.leftMargin = (int) Math.min(p.left, sw - (sw - bandX - bandW) - px(250));
        lp.topMargin = (int) p.bottom + px(12);
        menu.setLayoutParams(lp);
        menu.setVisibility(View.VISIBLE);
        menu.setAlpha(0f);
        menu.setTranslationY(-px(8));
        menu.animate().alpha(1f).translationY(0).setDuration(200).start();
        zone = MENU;
        menuIdx = 0;
        menu.post(() -> applyFocus(true));
    }

    private void hideMenu() {
        if (menu.getVisibility() == View.VISIBLE) menu.setVisibility(View.GONE);
    }

    private void switchInput(InputItem it) {
        if (it.live) {
            Intent i = AppRepo.launchIntent(this, "com.sony.dtv.tvx");
            if (it.inputId != null) {
                Intent v = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(TvContract.buildChannelsUriForInput(it.inputId), "vnd.android.cursor.dir/channel");
                if (v.resolveActivity(getPackageManager()) != null) i = v;
            }
            if (i != null) start(i);
            return;
        }
        start(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(it.inputId)));
    }

    // ------------------------------------------------------------------ clock, weather, wifi, wallpaper

    private void updateClock() {
        Calendar c = Calendar.getInstance();
        int hr = c.get(Calendar.HOUR_OF_DAY);
        greeting.setText(hr >= 5 && hr < 12 ? "Good morning" : hr >= 12 && hr < 17 ? "Good afternoon" : "Good evening");
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", c));
        date.setText(DateFormat.format("EEEE, d MMMM", c));
        renderWeather(weather.cached());
    }

    private void renderWeather(Weather.Data d) {
        if (d == null) { wxRow.setVisibility(View.GONE); return; }
        wxRow.setVisibility(View.VISIBLE);
        wxIcon.setImageResource(Weather.icon(d.code, d.day));
        wxTemp.setText(String.format(Locale.ROOT, "%d°", Math.round(d.temp)));
        wxRange.setText(String.format(Locale.ROOT, "H %d°   L %d°", Math.round(d.hi), Math.round(d.lo)));
        long stale = getResources().getInteger(R.integer.weather_stale_minutes) * 60_000L;
        wxRow.setAlpha(System.currentTimeMillis() - d.time > stale ? 0.4f : 1f);
    }

    private void startWifiUpdates() {
        updateWifi();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities c) { h.post(HomeActivity.this::updateWifi); }
            @Override public void onLost(@NonNull Network n) { h.post(HomeActivity.this::updateWifi); }
        };
        try { cm.registerDefaultNetworkCallback(netCb); } catch (Exception e) { netCb = null; }
    }

    private void stopWifiUpdates() {
        if (netCb == null) return;
        try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCb); } catch (Exception ignored) {}
        netCb = null;
    }

    @SuppressWarnings("deprecation")
    private void updateWifi() {
        String label = "Offline";
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) label = "Ethernet";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                label = "Wi-Fi";
                WifiInfo wi = getSystemService(WifiManager.class).getConnectionInfo();
                String ssid = wi == null ? null : wi.getSSID();
                if (ssid != null && !ssid.contains("unknown ssid")) label = ssid.replace("\"", "");
            }
        }
        if (!label.contentEquals(wifiText.getText())) {
            wifiText.setText(label);
            if (zone == TOP || zone == MENU) pills[1].post(() -> applyFocus(false));
        }
    }

    /** Picks the wallpaper: rotates through files/wallpapers/ every N hours, else files/wallpaper.jpg. */
    private File pickWallpaper() {
        File dir = new File(getExternalFilesDir(null), getString(R.string.wallpaper_dir));
        if (!dir.exists()) dir.mkdirs();
        File[] fs = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|webp)$"));
        if (fs == null || fs.length == 0) {
            File single = new File(getExternalFilesDir(null), getString(R.string.wallpaper_file));
            return single.exists() ? single : null;
        }
        java.util.Arrays.sort(fs);
        long now = System.currentTimeMillis();
        long every = getResources().getInteger(R.integer.wallpaper_rotate_hours) * 3_600_000L;
        int idx = prefs.getInt("wallIdx", -1);
        if (idx < 0 || idx >= fs.length || now - prefs.getLong("wallTime", 0) >= every) {
            idx = (idx + 1) % fs.length;
            prefs.edit().putInt("wallIdx", idx).putLong("wallTime", now).apply();
        }
        return fs[idx];
    }

    private void loadWallpaper() {
        File f = pickWallpaper();
        long stamp = f == null ? 0 : f.lastModified() ^ f.getPath().hashCode();
        if (stamp == wallpaperStamp) return;
        wallpaperStamp = stamp;
        Bitmap bmp = null;
        if (f != null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= sw && o.outHeight / (sample * 2) >= sh) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            bmp = BitmapFactory.decodeFile(f.getPath(), o);
        }
        wallpaper.setImageBitmap(bmp); // previous bitmap becomes garbage
        int vis = bmp == null ? View.GONE : View.VISIBLE;
        wallpaper.setVisibility(vis);
        wallShade.setVisibility(vis);
    }
}
