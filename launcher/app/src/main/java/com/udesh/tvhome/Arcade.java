package com.udesh.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * The arcade look (arcade_touches): stepped pixel frames and CRT scanlines. Drawn once; nothing
 * here animates.
 */
final class Arcade {
    static final int BLUE = MazeBorderView.COLOR;    // maze walls
    static final int YELLOW = 0xFFFFE500;            // the chomper
    static final int GREEN = 0xFF2FD66B;

    static boolean on(Context c) { return Config.bool(c, R.bool.arcade_touches); }

    /**
     * Stepped "pixel-rounded" frame: a border b thick whose corners step in by one block,
     * filled inside. fill or border may be null.
     */
    static void frame(Canvas c, RectF r, float b, Paint fill, Paint border) {
        float l = Math.round(r.left), t = Math.round(r.top), rt = Math.round(r.right), bt = Math.round(r.bottom);
        b = Math.max(1, Math.round(b));
        if (fill != null) {
            c.drawRect(l + 2 * b, t + b, rt - 2 * b, bt - b, fill);
            c.drawRect(l + b, t + 2 * b, l + 2 * b, bt - 2 * b, fill);
            c.drawRect(rt - 2 * b, t + 2 * b, rt - b, bt - 2 * b, fill);
        }
        if (border != null) {
            c.drawRect(l + 2 * b, t, rt - 2 * b, t + b, border);
            c.drawRect(l + 2 * b, bt - b, rt - 2 * b, bt, border);
            c.drawRect(l, t + 2 * b, l + b, bt - 2 * b, border);
            c.drawRect(rt - b, t + 2 * b, rt, bt - 2 * b, border);
            c.drawRect(l + b, t + b, l + 2 * b, t + 2 * b, border);
            c.drawRect(rt - 2 * b, t + b, rt - b, t + 2 * b, border);
            c.drawRect(l + b, bt - 2 * b, l + 2 * b, bt - b, border);
            c.drawRect(rt - 2 * b, bt - 2 * b, rt - b, bt - b, border);
        }
    }

    /** Background drawable: a stepped pixel frame. */
    static final class Frame extends Drawable {
        private final Paint fill = new Paint(), border = new Paint();
        private final float block;
        private final RectF r = new RectF();

        Frame(int fillColor, int borderColor, float blockPx) {
            fill.setColor(fillColor);
            border.setColor(borderColor);
            block = blockPx;
        }

        @Override public void draw(@NonNull Canvas c) {
            r.set(getBounds());
            frame(c, r, block, fill, border);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter f) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Faint CRT scanlines: one dark line every `every` px, as a single repeating shader. */
    static Drawable scanlines(Context c, int every) {
        Bitmap b = Bitmap.createBitmap(1, Math.max(2, every), Bitmap.Config.ARGB_8888);
        b.setPixel(0, b.getHeight() - 1, 0x38000000);
        BitmapDrawable d = new BitmapDrawable(c.getResources(), b);
        d.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        d.setFilterBitmap(false);
        return d;
    }

    private Arcade() {}
}
