package io.github.zhouwuqi.noitadroid;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The look the app's own screens share (SetupActivity, the in-game menu): dark like the game, its N's yellow. */
final class Ui {
    static final int BG = 0xff0e0c12, PANEL = 0xf2141119, CARD = 0xff1b1822, TEXT = 0xfff4efe6, DIM = 0xffb3adbf,
            ACCENT = 0xffffcf66, GOOD = 0xff7bd88f, BAD = 0xffff6b5a, WAIT = 0xff8a8594;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, CharSequence s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** A button whose focus shows plainly, for a gamepad. */
    static Button button(Context c, CharSequence label, android.view.View.OnClickListener onClick) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setPadding(dp(c, 18), dp(c, 8), dp(c, 18), dp(c, 8));
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setStateListAnimator(null);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_pressed}, rounded(c, 0xff4a4157, dp(c, 2), ACCENT));
        bg.addState(new int[] {android.R.attr.state_focused}, rounded(c, 0xff3a3346, dp(c, 2), ACCENT));
        bg.addState(new int[] {}, rounded(c, 0xff2a2533, 0, 0));
        b.setBackground(bg);
        b.setOnClickListener(onClick);
        return b;
    }

    static GradientDrawable rounded(Context c, int color, int stroke, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, 10));
        if (stroke > 0) d.setStroke(stroke, strokeColor);
        return d;
    }

    static LinearLayout.LayoutParams wrap(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    static LinearLayout.LayoutParams full(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }
}
