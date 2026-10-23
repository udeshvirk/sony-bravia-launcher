package com.udesh.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Parent PIN entry for leaving kids mode. Number buttons on the remote type directly; the D-pad
 * and OK work the on-screen keypad. Back cancels. Four digits are checked automatically.
 */
final class PinPad extends FrameLayout {
    interface Listener { void onPinOk(); void onPinCancel(); }

    private static final String[] KEYS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "Cancel", "0", "⌫"};
    private final float dp;
    private final Listener l;
    private final TextView[] dots = new TextView[4];
    private final TextView[] keys = new TextView[KEYS.length];
    private final TextView msg;
    private final LinearLayout card;
    private final StringBuilder entered = new StringBuilder();
    private int sel = 4;   // start on "5", the middle

    PinPad(Context c, Listener listener) {
        super(c);
        l = listener;
        dp = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xB3000000);
        setClickable(true);

        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(px(36), px(28), px(36), px(28));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2181820);
        bg.setStroke(Math.max(1, px(1)), 0x2EFFFFFF);
        bg.setCornerRadius(px(26));
        card.setBackground(bg);

        TextView title = text(24, 500, Color.WHITE);
        title.setText("Parent PIN");
        card.addView(title);
        TextView sub = text(15, 300, 0x99FFFFFF);
        sub.setText("Enter the PIN to leave kids mode");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = px(6);
        card.addView(sub, sl);

        LinearLayout dotRow = new LinearLayout(c);
        dotRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 4; i++) {
            dots[i] = text(30, 500, Color.WHITE);
            dots[i].setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(px(44), px(52));
            if (i > 0) dl.leftMargin = px(12);
            GradientDrawable db = new GradientDrawable();
            db.setColor(0x1AFFFFFF);
            db.setCornerRadius(px(12));
            dots[i].setBackground(db);
            dotRow.addView(dots[i], dl);
        }
        LinearLayout.LayoutParams drl = new LinearLayout.LayoutParams(-2, -2);
        drl.topMargin = px(20);
        card.addView(dotRow, drl);

        msg = text(15, 400, 0xFFFF8A80);
        msg.setText(" ");
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.topMargin = px(10);
        card.addView(msg, ml);

        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 3; col++) {
                int i = r * 3 + col;
                TextView k = text(i == 9 ? 16 : 24, 400, Color.WHITE);
                k.setText(KEYS[i]);
                k.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(px(92), px(56));
                if (col > 0) kl.leftMargin = px(10);
                row.addView(k, kl);
                keys[i] = k;
            }
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
            rl.topMargin = px(r == 0 ? 6 : 10);
            card.addView(row, rl);
        }
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2);
        cl.gravity = Gravity.CENTER;
        addView(card, cl);
        refresh();
        showLockout();
    }

    /** All keys go here while the pad is open. */
    boolean onKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int k = e.getKeyCode();
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) { digit((char) ('0' + k - KeyEvent.KEYCODE_0)); return true; }
        if (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_9) { digit((char) ('0' + k - KeyEvent.KEYCODE_NUMPAD_0)); return true; }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (sel % 3 > 0) sel--; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (sel % 3 < 2) sel++; break;
            case KeyEvent.KEYCODE_DPAD_UP: if (sel >= 3) sel -= 3; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (sel < 9) sel += 3; break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) press(KEYS[sel]);
                break;
            case KeyEvent.KEYCODE_DEL: backspace(); break;
            case KeyEvent.KEYCODE_BACK: l.onPinCancel(); return true;
            default: return true;   // swallow everything else while the pad is up
        }
        refresh();
        return true;
    }

    private void press(String key) {
        if (key.equals("Cancel")) { l.onPinCancel(); return; }
        if (key.equals("⌫")) { backspace(); return; }
        digit(key.charAt(0));
    }

    private void backspace() {
        if (entered.length() > 0) entered.setLength(entered.length() - 1);
        refresh();
    }

    private void digit(char d) {
        if (showLockout()) return;
        if (entered.length() >= 4) return;
        entered.append(d);
        refresh();
        if (entered.length() == 4) postDelayed(this::submit, 150);
    }

    private void submit() {
        String typed = entered.toString();
        if (Kids.check(getContext(), typed)) { l.onPinOk(); return; }
        entered.setLength(0);
        refresh();
        if (!showLockout()) msg.setText("Wrong PIN, try again");
        card.animate().translationX(px(12)).setDuration(60).withEndAction(() ->
                card.animate().translationX(-px(12)).setDuration(60).withEndAction(() ->
                        card.animate().translationX(0).setDuration(60).start()).start()).start();
    }

    /** Shows the wait message while locked out; returns true if locked. */
    private boolean showLockout() {
        long ms = Kids.lockedForMs(getContext());
        if (ms <= 0) return false;
        msg.setText(String.format(Locale.ROOT, "Too many tries. Wait %d s", (ms + 999) / 1000));
        postDelayed(() -> { if (Kids.lockedForMs(getContext()) <= 0) msg.setText(" "); else showLockout(); }, 1000);
        return true;
    }

    private void refresh() {
        for (int i = 0; i < 4; i++) dots[i].setText(i < entered.length() ? "•" : "");
        for (int i = 0; i < keys.length; i++) {
            GradientDrawable b = new GradientDrawable();
            b.setCornerRadius(px(14));
            b.setColor(i == sel ? 0xFFFFFFFF : 0x1FFFFFFF);
            keys[i].setBackground(b);
            keys[i].setTextColor(i == sel ? 0xFF111111 : Color.WHITE);
        }
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(getContext());
        t.setTextSize(sp);
        t.setTypeface(Fonts.outfit(getContext(), weight));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private int px(float v) { return Math.round(v * dp); }

    @Override public View focusSearch(View focused, int direction) { return null; }
}
