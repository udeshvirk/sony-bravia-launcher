package com.udesh.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Maps the Red/Green/Yellow/Blue remote buttons to apps (see res/values/config.xml).
 * Only those four keycodes are ever consumed; every other key returns false and passes
 * through untouched. In Live TV / HbbTV the colour keys pass through as well.
 */
public class ColorKeyService extends AccessibilityService {
    private static final String TAG = "TvHomeKeys";
    private final String[] map = new String[4]; // red, green, yellow, blue
    private Set<String> passthrough = new HashSet<>();
    private String lastWindowPkg = "";
    private final boolean[] launchedOnDown = new boolean[4];

    @Override protected void onServiceConnected() {
        map[0] = Config.str(ColorKeyService.this, R.string.key_red).trim();
        map[1] = Config.str(ColorKeyService.this, R.string.key_green).trim();
        map[2] = Config.str(ColorKeyService.this, R.string.key_yellow).trim();
        map[3] = Config.str(ColorKeyService.this, R.string.key_blue).trim();
        passthrough = new HashSet<>(Arrays.asList(Config.array(ColorKeyService.this, R.array.color_key_passthrough)));
        Log.i(TAG, "connected; map=" + Arrays.toString(map));
        if (Config.bool(ColorKeyService.this, R.bool.apps_button_opens_grid)) startAppsWatcher();
        if (Config.bool(ColorKeyService.this, R.bool.soundbar_wake)) startSoundbarWake();
        if (Config.bool(ColorKeyService.this, R.bool.screensaver_overlay)) { saver = new ScreensaverOverlay(this); saver.start(); }
    }

    private ScreensaverOverlay saver;

    // ---- Soundbar wake -------------------------------------------------------------------------
    // The soundbar (HDMI ARC) goes to standby on its own after a while of silence, and the TV does
    // not wake it when playback starts. Sony's Home Theatre Control panel does wake it, so: when an
    // app comes to the front or media starts playing while the ARC output is missing, open that
    // panel, wait for ARC, close it, and return to where we were.
    private static final String HTC_PKG = "com.sony.dtv.HomeTheatreControl";
    private static final ComponentName HTC = new ComponentName(HTC_PKG, HTC_PKG + ".HomeTheatreControlActivity");
    private AudioManager am;
    private boolean waking;
    private long lastWake, failedUntil;
    private String fgPkg = "";
    private final HashMap<String, Boolean> isApp = new HashMap<>();

    private AudioManager.AudioPlaybackCallback playCb;
    private AudioDeviceCallback devCb;

    private void startSoundbarWake() {
        am = getSystemService(AudioManager.class);
        am.registerAudioPlaybackCallback(playCb = new AudioManager.AudioPlaybackCallback() {
            @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                for (AudioPlaybackConfiguration c : configs) {
                    int u = c.getAudioAttributes().getUsage();
                    if (u == AudioAttributes.USAGE_MEDIA || u == AudioAttributes.USAGE_GAME) { maybeWake("playback"); return; }
                }
            }
        }, main);
        am.registerAudioDeviceCallback(devCb = new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                if (waking && arcUp()) Log.i(TAG, "soundbar connected");
            }
        }, main);
        Log.i(TAG, "soundbar wake on; arc=" + arcUp());
        waker = new SoundbarWaker(this);
        // First run: connect once so the TV shows its one-time "Allow USB debugging?" prompt now.
        if (!getSharedPreferences("adb", MODE_PRIVATE).getBoolean("paired", false)) {
            new Thread(() -> {
                try {
                    boolean ok = waker.wake(120_000);
                    getSharedPreferences("adb", MODE_PRIVATE).edit().putBoolean("paired", true).apply();
                    Log.i(TAG, "adb pairing done; setsam ok=" + ok);
                } catch (Exception e) {
                    Log.i(TAG, "adb pairing not done: " + e);
                }
            }, "adb-pair").start();
        }
    }

    private SoundbarWaker waker;

    private boolean arcUp() {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_HDMI_ARC || t == AudioDeviceInfo.TYPE_HDMI_EARC) return true;
        }
        return false;
    }

    private void onForeground(String pkg) {
        // Kids mode: anything not on the kids list goes straight back to the kids home screen.
        if (Kids.on(this) && !Kids.allowedForeground(this, pkg)) { kidsBlock(pkg); return; }
        if (pkg.equals(HTC_PKG) || pkg.equals("com.android.systemui") || pkg.equals(fgPkg)) return;
        fgPkg = pkg;
        if (am == null || pkg.equals(getPackageName())) return;
        Boolean app = isApp.get(pkg);
        if (app == null) {
            app = getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null;
            isApp.put(pkg, app);
        }
        if (app) maybeWake("open " + pkg);
    }

    private void maybeWake(String why) {
        long now = SystemClock.uptimeMillis();
        if (waking || arcUp() || now - lastWake < 30_000 || now < failedUntil) return;
        waking = true;
        lastWake = now;
        Log.i(TAG, "soundbar asleep; waking it (" + why + ")");
        // Invisible way first: HDMI-CEC "system audio on" via the TV's own ADB.
        new Thread(() -> {
            boolean ok = false;
            try { ok = waker.wake(3000); } catch (Exception e) { Log.i(TAG, "adb wake unavailable: " + e); }
            final boolean done = ok;
            main.post(() -> {
                if (done) {
                    Log.i(TAG, "wake sent via HDMI-CEC");
                    main.postDelayed(() -> {
                        waking = false;
                        Log.i(TAG, "soundbar " + (arcUp() ? "on" : "did not wake"));
                        if (!arcUp()) failedUntil = SystemClock.uptimeMillis() + 10 * 60_000L;
                    }, 15_000);
                } else {
                    wakeWithPanel();
                }
            });
        }, "soundbar-wake").start();
    }

    /** Fallback when ADB is off: Sony's Home Theatre Control panel, shown for ~0.5 s. */
    private void wakeWithPanel() {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).setComponent(HTC)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception e) {
            Log.w(TAG, "home theatre control", e);
            waking = false;
            return;
        }
        // The panel sends the wake-up request as soon as it opens, so close it right away
        // (it only blinks), then keep checking in the background for the soundbar.
        main.postDelayed(this::closePanel, 400);
        main.postDelayed(() -> {
            waking = false;
            boolean on = arcUp();
            if (!on) failedUntil = SystemClock.uptimeMillis() + 10 * 60_000L; // unplugged? stop for 10 min
            Log.i(TAG, "soundbar " + (on ? "on" : "did not wake"));
        }, 15_000);
    }

    /** Close the panel as soon as it is on screen (retries for ~3 s), then return to the app we were in. */
    private void closePanel() { closePanel(fgPkg, 0); }

    private void closePanel(String back, int attempt) {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        CharSequence p = r == null ? null : r.getPackageName();
        if (p == null || !HTC_PKG.contentEquals(p)) {
            if (attempt < 12) main.postDelayed(() -> closePanel(back, attempt + 1), 250);
            else Log.w(TAG, "panel not seen; nothing to close");
            return;
        }
        performGlobalAction(GLOBAL_ACTION_BACK);
        Log.i(TAG, "panel closed; back to " + back);
        if (back.equals(getPackageName())) {   // the panel's task sits above home: bring home back
            main.postDelayed(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_MAIN).setClass(this, HomeActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {}
            }, 250);
        }
    }

    // ---- Apps button -------------------------------------------------------------------------
    // The system turns the Apps key into an ALL_APPS request aimed at Settings (which ignores it),
    // before accessibility services see the key. We react to that request instead, by reading
    // only ActivityTaskManager lines from the log. The reader blocks; it does not poll.
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Process logProc;
    private long lastApps;

    private void startAppsWatcher() {
        if (checkSelfPermission(android.Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_LOGS not granted; Apps button stays unmapped");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                final long started = SystemClock.uptimeMillis();
                logProc = new ProcessBuilder("logcat", "-v", "brief", "-T", "1", "ActivityTaskManager:I", "*:S")
                        .redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(new InputStreamReader(logProc.getInputStream()));
                for (String line; (line = r.readLine()) != null; ) {
                    if (!line.contains("START") || !line.contains("act=android.intent.action.ALL_APPS")) continue;
                    long now = SystemClock.uptimeMillis();
                    if (now - started < 1500 || now - lastApps < 400) continue; // skip old line / double fire
                    lastApps = now;
                    main.post(this::openAppsGrid);
                }
            } catch (Exception e) {
                Log.i(TAG, "apps watcher stopped");   // normal when the service is turned off
            }
        }, "apps-button");
        t.setDaemon(true);
        t.start();
    }

    private void openAppsGrid() {
        Intent i = new Intent(HomeActivity.ACTION_SHOW_APPS).setClass(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); Log.i(TAG, "apps button -> home grid"); } catch (Exception e) { Log.w(TAG, "apps", e); }
    }

    @Override public void onDestroy() {
        Process p = logProc;
        if (p != null) p.destroy();
        // Release everything, so an old copy of the service can never act again after a reinstall.
        main.removeCallbacksAndMessages(null);
        if (saver != null) { saver.stop(); saver = null; }
        if (am != null) {
            if (playCb != null) am.unregisterAudioPlaybackCallback(playCb);
            if (devCb != null) am.unregisterAudioDeviceCallback(devCb);
        }
        am = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getPackageName() != null) {
            lastWindowPkg = e.getPackageName().toString();
            onForeground(lastWindowPkg);
        }
    }

    @Override public void onInterrupt() {}

    private long lastKidsBlock;

    private void kidsBlock(String pkg) {
        long now = SystemClock.uptimeMillis();
        Log.i(TAG, "kids mode: blocked " + pkg);
        if (now - lastKidsBlock < 300) return;   // one Home per burst of window events
        lastKidsBlock = now;
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    /** Buttons that lead out of the kids apps: Settings, Input, Assistant/search. */
    private static boolean kidsBlockedKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_BUTTON_2:      // Sony Settings button
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_TV_INPUT:
            case KeyEvent.KEYCODE_ASSIST:
            case KeyEvent.KEYCODE_VOICE_ASSIST:
            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_TV:
                return true;
        }
        return false;
    }

    @Override protected boolean onKeyEvent(KeyEvent ev) {
        if (kidsBlockedKey(ev.getKeyCode()) && Kids.on(this)) return true;
        if (ev.getKeyCode() == KeyEvent.KEYCODE_ALL_APPS && Config.bool(ColorKeyService.this, R.bool.apps_button_opens_grid)) {
            // The system sends this to Settings (the only system home left), which ignores it.
            if (ev.getAction() == KeyEvent.ACTION_UP) {
                Intent i = new Intent(HomeActivity.ACTION_SHOW_APPS).setClass(this, HomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(i); } catch (Exception ex) { Log.w(TAG, "apps key", ex); }
            }
            return true;
        }
        int slot;
        switch (ev.getKeyCode()) {
            case KeyEvent.KEYCODE_PROG_RED: slot = 0; break;
            case KeyEvent.KEYCODE_PROG_GREEN: slot = 1; break;
            case KeyEvent.KEYCODE_PROG_YELLOW: slot = 2; break;
            case KeyEvent.KEYCODE_PROG_BLUE: slot = 3; break;
            default: return false; // everything else: untouched
        }
        String target = map[slot];
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            if (ev.getRepeatCount() > 0) return launchedOnDown[slot]; // held key: same decision as first press
            launchedOnDown[slot] = false;
            if (Kids.on(this) && !Kids.allowedApp(this, target)) { launchedOnDown[slot] = true; return true; }
            boolean pass = target.isEmpty() || inPassthroughApp();
            if (pass) Log.i(TAG, "key " + slot + " passed through");
            if (pass) return false;
            Intent i = AppRepo.launchIntent(this, target);
            if (i == null) { Log.w(TAG, "no launch intent for " + target); return false; }
            try {
                startActivity(i);
                launchedOnDown[slot] = true;
                Log.i(TAG, "key " + slot + " -> " + target);
                return true;
            } catch (Exception ex) {
                Log.w(TAG, "launch failed " + target, ex);
                return false;
            }
        }
        // ACTION_UP: swallow only if we handled the matching down
        boolean handled = launchedOnDown[slot];
        launchedOnDown[slot] = false;
        return handled;
    }

    /** True if Live TV, an input, HbbTV or teletext is showing (any visible application window). */
    private boolean inPassthroughApp() {
        List<AccessibilityWindowInfo> ws;
        try {
            ws = getWindows();
        } catch (Exception e) {
            return passthrough.contains(lastWindowPkg);
        }
        try {
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence p = root.getPackageName();
                if (p != null && passthrough.contains(p.toString())) return true;
            }
        } catch (Exception ignored) {
        }
        return ws.isEmpty() && passthrough.contains(lastWindowPkg);
    }
}
