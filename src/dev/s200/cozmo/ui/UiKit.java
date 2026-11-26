package dev.s200.cozmo.ui;

import dev.s200.cozmo.R;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public final class UiKit {
    public static final int BG_APP = Color.parseColor("#0A1219");
    public static final int BG_BAR = Color.parseColor("#101B24");
    public static final int BG_CARD = Color.parseColor("#14232D");
    public static final int BORDER_CARD = Color.parseColor("#2B404C");
    public static final int BG_CHIP = Color.parseColor("#1C313C");
    public static final int BG_INPUT = Color.parseColor("#101D26");

    public static final int ACCENT = Color.parseColor("#83F4CB");
    public static final int ACCENT_DARK = Color.parseColor("#0C2B23");
    public static final int WARN = Color.parseColor("#FFD49A");
    public static final int WARN_BG = Color.parseColor("#382C1D");
    public static final int DANGER = Color.parseColor("#FF9B91");
    public static final int DANGER_BG = Color.parseColor("#3D2428");
    public static final int OK = Color.parseColor("#83F4CB");
    public static final int OK_BG = Color.parseColor("#15382F");

    public static final int TEXT_PRIMARY = Color.parseColor("#F2F7F6");
    public static final int TEXT_SECONDARY = Color.parseColor("#B5C9D1");
    public static final int TEXT_MUTED = Color.parseColor("#96AEB9");

    private UiKit() {}

    public static int dp(Context c, int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable box(Context c, int fillColor, int strokeColor, int radiusDp, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) {
            d.setStroke(dp(c, strokeDp), strokeColor);
        }
        return d;
    }

    public static LinearLayout card(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackground(box(c, BG_CARD, BORDER_CARD, 16, 1));
        int p = dp(c, 16);
        ll.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        ll.setLayoutParams(lp);
        return ll;
    }

    public static LinearLayout row(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_VERTICAL);
        ll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return ll;
    }

    public static TextView sectionTitle(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(TEXT_PRIMARY);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        tv.setLayoutParams(lp);
        return tv;
    }

    public static TextView subtitle(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(TEXT_SECONDARY);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        return tv;
    }

    public static TextView pill(Context c, String text, int bgColor, int textColor) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setBackground(box(c, bgColor, Color.TRANSPARENT, 999, 0));
        tv.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        return tv;
    }

    public static void stylePill(Context c, TextView tv, int bgColor, int textColor) {
        tv.setBackground(box(c, bgColor, Color.TRANSPARENT, 999, 0));
        tv.setTextColor(textColor);
    }

    public static TextView banner(Context c) {
        TextView tv = new TextView(c);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        tv.setBackground(box(c, WARN_BG, WARN, 12, 1));
        tv.setTextColor(WARN);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 8);
        tv.setLayoutParams(lp);
        return tv;
    }

    public static Button actionButton(Context c, String text, int bgColor, int textColor) {
        Button b = new Button(c);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setBackground(buttonSurface(c, bgColor, bgColor == BG_CHIP ? BORDER_CARD : bgColor));
        b.setMinimumHeight(dp(c, 48));
        b.setMinHeight(dp(c, 48));
        b.setPadding(dp(c, 9), dp(c, 6), dp(c, 9), dp(c, 6));
        return b;
    }

    public static RippleDrawable buttonSurface(Context c, int fill, int stroke) {
        return new RippleDrawable(ColorStateList.valueOf(0x407ECDB0), box(c, fill, stroke, 12, 1), null);
    }

    public static void styleChip(Context c, Button b, boolean active) {
        if (b.getTag(R.id.chip_state) != null && b.isSelected() == active) return;
        b.setTag(R.id.chip_state, Boolean.TRUE);
        b.setSelected(active);
        b.setBackground(buttonSurface(c, active ? OK_BG : BG_CHIP, active ? ACCENT : BORDER_CARD));
        b.setTextColor(active ? ACCENT : TEXT_SECONDARY);
    }

    public static void setEnabledAlpha(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1.0f : 0.42f);
    }

    public static EditText input(Context c, String hint) {
        EditText et = new EditText(c);
        et.setHint(hint);
        et.setHintTextColor(TEXT_MUTED);
        et.setTextColor(TEXT_PRIMARY);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setSingleLine(true);
        et.setMinHeight(dp(c, 48));
        et.setBackground(box(c, BG_INPUT, BORDER_CARD, 12, 1));
        et.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        return et;
    }

    public static ProgressBar horizontalBar(Context c, int max) {
        ProgressBar pb = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(max);
        pb.setProgressTintList(ColorStateList.valueOf(ACCENT));
        pb.setProgressBackgroundTintList(ColorStateList.valueOf(BG_CHIP));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 6));
        lp.topMargin = dp(c, 4);
        pb.setLayoutParams(lp);
        return pb;
    }

    public static View spacer(Context c, int widthDp, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, widthDp), dp(c, heightDp)));
        return v;
    }
}
