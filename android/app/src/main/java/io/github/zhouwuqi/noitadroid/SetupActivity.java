package io.github.zhouwuqi.noitadroid;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The launcher's activity. When the game can start (GameCheck), it goes straight to NoitaActivity and is never seen;
 * otherwise it shows what's missing, with what to do about each item, and checks again whenever it comes back.
 * Plain framework views: touch and a gamepad (A is the confirm key, B is back) both work.
 */
public class SetupActivity extends Activity {
    private static final int BG = 0xff0e0c12, CARD = 0xff1b1822, TEXT = 0xfff4efe6, DIM = 0xffb3adbf,
            ACCENT = 0xffffcf66, GOOD = 0xff7bd88f, BAD = 0xffff6b5a, WAIT = 0xff8a8594;

    private LinearLayout rows;
    private TextView status;
    private Button start, recheck;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (new GameCheck(this).ready()) {
            startGame();
            return;
        }
        setContentView(layout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rows != null) show();  // back from Android's settings, or from copying the game over USB
    }

    private void startGame() {
        startActivity(new Intent(this, NoitaActivity.class));
        finish();
        overridePendingTransition(0, 0);
    }

    private void show() {
        if (Environment.isExternalStorageManager()) {
            new GameCheck(this).gameDir.mkdirs();  // so it's there to copy into, over USB too
        }
        GameCheck check = new GameCheck(this);
        rows.removeAllViews();
        Button first = null;

        first = row(check.access, R.string.access_title, check.access == GameCheck.State.OK
                ? getString(R.string.access_ok, check.home.getPath())
                : getString(R.string.access_missing), check.access == GameCheck.State.OK ? 0 : R.string.access_action,
                v -> openAccessSettings(), first);

        String files;
        switch (check.files) {
            case WAITING: files = getString(R.string.files_waiting); break;
            case WRONG: files = getString(R.string.files_incomplete, Formatter.formatFileSize(this, check.wakSize),
                    Formatter.formatFileSize(this, check.wakExpected)); break;
            case MISSING: files = check.missing.size() == GameCheck.REQUIRED.length
                    ? getString(R.string.files_missing_all, check.gameDir.getPath())
                    : getString(R.string.files_missing_some, check.gameDir.getPath(), String.join(", ", check.missing));
                break;
            default: files = getString(R.string.files_ok, check.gameDir.getPath());
        }
        row(check.files, R.string.files_title, files, 0, null, null);

        row(check.version, R.string.version_title, check.version == GameCheck.State.WRONG
                ? getString(R.string.version_wrong, check.wrongFile, GameCheck.GOG_BUILD)
                : check.version == GameCheck.State.OK ? getString(R.string.version_ok, GameCheck.GOG_BUILD)
                : getString(R.string.version_waiting), 0, null, null);

        row(check.sound, R.string.sound_title, check.sound == GameCheck.State.OK
                ? getString(R.string.sound_ok, check.fmodVersion) : getString(R.string.sound_missing), 0, null, null);

        boolean ready = check.ready();
        status.setText(ready ? R.string.setup_ready : R.string.setup_not_ready);
        status.setTextColor(ready ? GOOD : BAD);
        start.setEnabled(ready);
        start.setAlpha(ready ? 1f : 0.4f);
        (ready ? start : first != null ? first : recheck).requestFocus();
    }

    private void openAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {  // some builds of Android only have the list of all apps
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // --- views

    private View layout() {
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(BG);
        root.setPadding(dp(32), dp(24), dp(32), dp(24));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        left.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        left.addView(text("noitadroid", 28, ACCENT, true), margins(0, dp(8), 0, 0));
        left.addView(text(getString(R.string.setup_subtitle), 15, TEXT, false));
        left.addView(text(getString(R.string.setup_intro, GameCheck.GOG_BUILD), 13, DIM, false),
                margins(0, dp(12), 0, 0));
        status = text("", 14, TEXT, true);
        left.addView(status, margins(0, dp(12), 0, 0));
        View spacer = new View(this);
        left.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout buttons = new LinearLayout(this);
        start = button(R.string.start, v -> startGame());
        recheck = button(R.string.recheck, v -> show());
        buttons.addView(start);
        buttons.addView(recheck, margins(dp(12), 0, 0, 0));
        left.addView(buttons);
        root.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2));

        ScrollView scroll = new ScrollView(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3);
        right.leftMargin = dp(28);
        root.addView(scroll, right);
        return root;
    }

    /** One checked item: its state, what it is, what to do; with an action button, which it returns. */
    private Button row(GameCheck.State state, int title, String detail, int action, View.OnClickListener onAction,
                       Button first) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(rounded(CARD, 0, 0));
        String mark;
        int color;
        switch (state) {
            case OK: mark = "✓"; color = GOOD; break;
            case MISSING: case WRONG: mark = "✕"; color = BAD; break;
            case INFO: mark = "i"; color = ACCENT; break;
            default: mark = "…"; color = WAIT;
        }
        TextView glyph = text(mark, 22, color, true);
        glyph.setGravity(Gravity.CENTER);
        row.addView(glyph, new LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(getString(title), 16, TEXT, true));
        texts.addView(text(detail, 13, DIM, false), margins(0, dp(2), 0, 0));
        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        grow.leftMargin = dp(8);
        row.addView(texts, grow);
        Button b = null;
        if (action != 0) {
            b = button(action, onAction);
            row.addView(b, margins(dp(12), 0, 0, 0));
        }
        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        full.bottomMargin = dp(10);
        rows.addView(row, full);
        return first != null ? first : b;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** A button whose focus shows plainly, for a gamepad. */
    private Button button(int label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setPadding(dp(20), dp(8), dp(20), dp(8));
        b.setMinHeight(dp(44));
        b.setStateListAnimator(null);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_pressed}, rounded(0xff4a4157, dp(2), ACCENT));
        bg.addState(new int[] {android.R.attr.state_focused}, rounded(0xff3a3346, dp(2), ACCENT));
        bg.addState(new int[] {}, rounded(0xff2a2533, 0, 0));
        b.setBackground(bg);
        b.setOnClickListener(onClick);
        return b;
    }

    private GradientDrawable rounded(int color, int stroke, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(10));
        if (stroke > 0) d.setStroke(stroke, strokeColor);
        return d;
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
