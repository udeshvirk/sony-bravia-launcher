package com.udesh.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Arcade touch: a glowing blue double-line "maze corridor" drawn around the dock bar.
 * Static: drawn once, never animates.
 */
final class MazeBorderView extends View {
    static final int COLOR = 0xFF3D4DFF;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float dp, bandRadius, outset;

    /** outset = how far outside the bar this view extends on each side. */
    MazeBorderView(Context c, float bandRadiusPx, float outsetPx) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        bandRadius = bandRadiusPx;
        outset = outsetPx;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * dp);
        line.setColor(COLOR);
        line.setShadowLayer(7 * dp, 0, 0, 0xCC3D4DFF);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        // inner line hugs the bar's edge, outer line 7 dp further out
        float in = outset - 1 * dp, out = outset - 8 * dp;
        r.set(in, in, w - in, h - in);
        c.drawRoundRect(r, bandRadius + 1 * dp, bandRadius + 1 * dp, line);
        r.set(out, out, w - out, h - out);
        c.drawRoundRect(r, bandRadius + 8 * dp, bandRadius + 8 * dp, line);
    }
}
