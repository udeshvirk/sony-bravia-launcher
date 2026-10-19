package com.udesh.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/** The single translucent "glass" highlight that springs between focus targets. */
final class HighlightView extends View {
    private final RectF cur = new RectF(), from = new RectF(), to = new RectF();
    private float curR, fromR, toR;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator anim;
    private boolean placed;
    private final float dp;

    HighlightView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        fill.setColor(0x1FFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * dp);
        stroke.setColor(0x66FFFFFF);
        setWillNotDraw(false);
    }

    /** Tints the highlight gold while a tile is being dragged into a new position. */
    void setReorderMode(boolean on) {
        fill.setColor(on ? 0x332FD66B : 0x1FFFFFFF);
        stroke.setColor(on ? 0xCC2FD66B : 0x66FFFFFF);
        stroke.setStrokeWidth((on ? 2.5f : 1.5f) * dp);
        invalidate();
    }

    void moveTo(RectF target, float radius, boolean animate) {
        to.set(target);
        toR = radius;
        if (anim != null) anim.cancel();
        if (!animate || !placed) {
            cur.set(to); curR = toR; placed = true;
            invalidate();
            return;
        }
        from.set(cur); fromR = curR;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(340);
        anim.setInterpolator(new OvershootInterpolator(1.4f));
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            cur.set(lerp(from.left, to.left, t), lerp(from.top, to.top, t),
                    lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t));
            curR = lerp(fromR, toR, Math.min(1f, t));
            invalidate();
        });
        anim.start();
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    @Override protected void onDraw(Canvas c) {
        if (!placed) return;
        c.drawRoundRect(cur, curR, curR, fill);
        sheen.setShader(new LinearGradient(0, cur.top, 0, cur.top + cur.height() * 0.55f,
                0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(cur, curR, curR, sheen);
        c.drawRoundRect(cur, curR, curR, stroke);
    }
}
