package com.udesh.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Frosted mini scorecard for the home screen (top right). Display only; never takes focus. */
final class CricketCard extends LinearLayout {
    private static final int LIVE_RED = 0xFFE5484D;

    private final float dp;
    private final TextView badge, title, status;
    private final LinearLayout teams, lines;
    private final View divider;

    CricketCard(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setFocusable(false);
        setPadding(px(20), px(14), px(20), px(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB8141418);
        bg.setStroke(Math.max(1, px(1)), 0x24FFFFFF);
        bg.setCornerRadius(px(20));
        setBackground(bg);

        // header: badge + match title
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        badge = text(11, 600, Color.WHITE);
        badge.setLetterSpacing(0.1f);
        badge.setPadding(px(7), px(3), px(7), px(3));
        head.addView(badge);
        title = text(13, 400, 0x99FFFFFF);
        LayoutParams tlp = new LayoutParams(0, -2, 1);
        tlp.leftMargin = px(10);
        head.addView(title, tlp);
        addView(head, new LayoutParams(-1, -2));

        teams = new LinearLayout(c);
        teams.setOrientation(VERTICAL);
        LayoutParams tl = new LayoutParams(-1, -2);
        tl.topMargin = px(10);
        addView(teams, tl);

        status = text(14, 400, 0xE6FFD27A);
        LayoutParams sl = new LayoutParams(-1, -2);
        sl.topMargin = px(8);
        addView(status, sl);

        divider = new View(c);
        divider.setBackgroundColor(0x1FFFFFFF);
        LayoutParams dl = new LayoutParams(-1, Math.max(1, px(1)));
        dl.topMargin = px(10);
        dl.bottomMargin = px(6);
        addView(divider, dl);

        lines = new LinearLayout(c);
        lines.setOrientation(VERTICAL);
        addView(lines, new LayoutParams(-1, -2));
    }

    void bind(Cricket.Data d) {
        GradientDrawable b = new GradientDrawable();
        b.setCornerRadius(px(5));
        if (d.state == Cricket.LIVE) { badge.setText("LIVE"); b.setColor(LIVE_RED); }
        else if (d.state == Cricket.UPCOMING) { badge.setText("UP NEXT"); b.setColor(0x33FFFFFF); }
        else { badge.setText("RESULT"); b.setColor(0x33FFFFFF); }
        badge.setBackground(b);
        title.setText(d.title);

        teams.removeAllViews();
        boolean anyBatting = false;
        for (Cricket.Team t : d.teams) anyBatting |= t.batting;
        for (int i = 0; i < d.teams.size(); i++) {
            Cricket.Team t = d.teams.get(i);
            boolean dim = anyBatting && !t.batting;
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(logo(t), new LayoutParams(px(24), px(24)));
            TextView code = text(18, 500, dim ? 0x99FFFFFF : Color.WHITE);
            code.setText(t.code);
            LayoutParams cl = new LayoutParams(0, -2, 1);
            cl.leftMargin = px(10);
            row.addView(code, cl);
            TextView score = text(19, 500, dim ? 0x99FFFFFF : Color.WHITE);
            score.setText(t.score.isEmpty() ? "–" : t.score);
            row.addView(score);
            TextView ov = text(13, 300, 0x80FFFFFF);
            ov.setText(t.overs);
            ov.setGravity(Gravity.END);
            LayoutParams ol = new LayoutParams(px(58), -2);
            ol.leftMargin = px(10);
            row.addView(ov, ol);
            LayoutParams rl = new LayoutParams(-1, px(28));
            if (i > 0) rl.topMargin = px(2);
            teams.addView(row, rl);
        }

        status.setText(d.status);
        status.setTextColor(d.state == Cricket.LIVE ? 0xE6FFD27A : 0xCCFFFFFF);
        status.setVisibility(d.status.isEmpty() ? GONE : VISIBLE);

        lines.removeAllViews();
        for (Cricket.Line l : d.lines) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView tag = text(10, 500, 0x73FFFFFF);
            tag.setLetterSpacing(0.12f);
            tag.setText(l.tag);
            row.addView(tag, new LayoutParams(px(42), -2));
            TextView name = text(14, 400, 0xE6FFFFFF);
            name.setText(l.name);
            row.addView(name, new LayoutParams(0, -2, 1));
            TextView fig = text(14, 500, Color.WHITE);
            fig.setText(l.figures);
            LayoutParams fl = new LayoutParams(-2, -2);
            fl.leftMargin = px(12);
            row.addView(fig, fl);
            LayoutParams rl = new LayoutParams(-1, px(22));
            lines.addView(row, rl);
        }
        boolean hasLines = !d.lines.isEmpty();
        divider.setVisibility(hasLines ? VISIBLE : GONE);
        lines.setVisibility(hasLines ? VISIBLE : GONE);
    }

    /** Round team logo, or a circle with the first letter when there's none. */
    private View logo(Cricket.Team t) {
        Bitmap bm = t.logo.isEmpty() ? null : BitmapFactory.decodeFile(t.logo);
        if (bm != null) {
            ImageView iv = new ImageView(getContext());
            iv.setImageBitmap(bm);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
            });
            iv.setClipToOutline(true);
            return iv;
        }
        TextView tv = text(11, 600, Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setText(t.code.isEmpty() ? "?" : t.code.substring(0, 1));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x33FFFFFF);
        tv.setBackground(g);
        return tv;
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
}
