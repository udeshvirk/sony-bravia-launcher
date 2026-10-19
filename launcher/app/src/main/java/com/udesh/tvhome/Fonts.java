package com.udesh.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.util.SparseArray;

/** Outfit (SIL OFL 1.1) variable font, one Typeface per weight. */
final class Fonts {
    private static final SparseArray<Typeface> cache = new SparseArray<>();

    static Typeface outfit(Context c, int weight) {
        Typeface t = cache.get(weight);
        if (t == null) {
            try {
                t = new Typeface.Builder(c.getAssets(), "fonts/Outfit.ttf")
                        .setFontVariationSettings("'wght' " + weight).build();
            } catch (Exception e) {
                t = Typeface.create("sans-serif-light", Typeface.NORMAL);
            }
            cache.put(weight, t);
        }
        return t;
    }
    private Fonts() {}
}
