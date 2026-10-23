package com.udesh.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Arcade touch along the top edge of the dock: a row of dots and a little chomper that runs to
 * the selected app, eating the dots it passes. It only animates while it is moving (a fraction of
 * a second per press); the rest of the time nothing redraws.
 */
final class ChomperView extends View {
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float dp;
    private float x = -1;            // chomper centre, px from this view's left
    private float mouth = 0.22f;     // 0 = closed, 1 = wide open
    private boolean right = true;
    private float laneStart, laneEnd;
    private ValueAnimator anim;

    ChomperView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        body.setColor(0xFFFFE500);
        body.setShadowLayer(6 * dp, 0, 0, 0x80FFE500);
        dot.setColor(0xFFFFD9B0);
    }

    /** The stretch the dots cover, px from this view's left. */
    void setLane(float start, float end) {
        laneStart = start;
        laneEnd = end;
        invalidate();
    }

    void moveTo(float target, boolean animate) {
        if (anim != null) anim.cancel();
        if (x < 0 || !animate) {
            x = target;
            mouth = 0.22f;
            invalidate();
            return;
        }
        if (Math.abs(target - x) < 1) return;
        right = target > x;
        float from = x;
        long ms = (long) Math.min(650, Math.max(240, Math.abs(target - from) / dp * 1.4f));
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(ms);
        anim.setInterpolator(new DecelerateInterpolator(1.4f));
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            x = from + (target - from) * t;
            float travelled = Math.abs(x - from) / (12 * dp);
            mouth = (float) Math.abs(Math.sin(travelled * Math.PI));
            invalidate();
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { mouth = 0.22f; invalidate(); }
        });
        anim.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas c) {
        if (x < 0) return;
        float cy = 9.5f * dp;   // above the selected banner's outline
        float r = 6 * dp;
        // dots still to eat: everything ahead (right) of the chomper; a power pellet at the end
        float step = 15 * dp, d = 1.6f * dp;
        float pellet = laneEnd - 4 * dp;
        for (float p = laneStart; p < pellet - step / 2; p += step) {
            if (p > x + r) c.drawRect(p - d, cy - d, p + d, cy + d, dot);
        }
        if (pellet > x + r) c.drawCircle(pellet, cy, 3.6f * dp, dot);
        // the chomper
        float open = 8 + 40 * mouth;   // half the mouth angle, degrees
        oval.set(x - r, cy - r, x + r, cy + r);
        float start = right ? open : 180 + open;
        c.drawArc(oval, start, 360 - 2 * open, true, body);
    }
}
