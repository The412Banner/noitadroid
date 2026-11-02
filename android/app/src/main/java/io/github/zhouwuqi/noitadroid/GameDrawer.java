package io.github.zhouwuqi.noitadroid;

import static io.github.zhouwuqi.noitadroid.Ui.ACCENT;
import static io.github.zhouwuqi.noitadroid.Ui.BAD;
import static io.github.zhouwuqi.noitadroid.Ui.DIM;
import static io.github.zhouwuqi.noitadroid.Ui.GOOD;
import static io.github.zhouwuqi.noitadroid.Ui.TEXT;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The in-game menu: a swipe from the screen's left edge pulls it out over the game, which stands still meanwhile
 * (Host.hold). It has the saves (export, import), swapped gamepad buttons (PadMap) and the FPS and temperature
 * display (StatsOverlay). Touch and gamepad: the D-pad or left stick moves between buttons, A presses, B goes back.
 */
final class GameDrawer {
    interface Host {
        /** Holds the game at a frame's end, sound off (or lets it go). */
        void hold(boolean on);

        void pick(Intent intent, int requestCode);

        /** Ends the game's process and starts it again (an import goes in before it starts). */
        void restart();

        /** Gives the keys back to the game. */
        void gameFocus();
    }

    static final int REQ_EXPORT = 7101, REQ_IMPORT = 7102;
    private static final int MAIN = 0, PAD = 1, PAD_TARGET = 2, IMPORT = 3;

    private final Activity a;
    private Context t;  // for strings: the app's language (Lang), which the menu itself switches
    private final Host host;
    private final PadMap pad;
    private final StatsOverlay stats;
    private final FrameLayout root;
    private final View scrim;
    private final ScrollView panel;
    private final LinearLayout content;
    private final int panelWidth, edge, slop;

    private boolean shown, tracking, dragging, busy;
    private float downX;
    private int page = MAIN, padSource;
    private CharSequence saveMessage;
    private int saveColor = DIM;
    private SaveTransfer.Staged staged;

    GameDrawer(Activity a, FrameLayout parent, Host host, PadMap pad, StatsOverlay stats) {
        this.a = a;
        t = Lang.wrap(a);
        this.host = host;
        this.pad = pad;
        this.stats = stats;
        int screen = a.getResources().getDisplayMetrics().widthPixels;
        panelWidth = Math.min(dp(380), screen * 9 / 20);
        edge = dp(28);
        slop = dp(10);

        root = new FrameLayout(a);
        root.setVisibility(View.GONE);
        root.setClickable(true);  // while it's out, touches stop here, not in the game
        scrim = new View(a);
        scrim.setBackgroundColor(0x99000000);
        scrim.setOnClickListener(v -> close());
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        panel = new ScrollView(a);
        panel.setBackgroundColor(Ui.PANEL);
        panel.setClickable(true);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(18), dp(22), dp(18));
        panel.addView(content);
        root.addView(panel, new FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START));
        parent.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    boolean isShown() {
        return shown;
    }

    /** The left edge, where a swipe pulls the menu out (the system's back gesture is kept off it there). */
    int edgeWidth() {
        return edge;
    }

    // --- opening and closing

    /**
     * A touch on its way to the game. A swipe from the left edge pulls the menu out, following the finger; its
     * touches don't reach the game. Returns true for the touches it takes.
     */
    boolean interceptTouch(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (tracking) {
            float dx = ev.getX() - downX;
            if (action == MotionEvent.ACTION_MOVE) {
                if (!dragging && dx > slop) {
                    dragging = true;
                    reveal();
                }
                if (dragging) offset(dx);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                tracking = false;
                if (dragging) {
                    if (action == MotionEvent.ACTION_UP && dx > panelWidth / 3f) open();
                    else close();
                }
            }
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN && !shown && ev.getX() <= edge) {
            tracking = true;
            dragging = false;
            downX = ev.getX();
            return true;
        }
        return false;
    }

    private void reveal() {
        if (shown) return;
        shown = true;
        page = MAIN;
        render();
        root.setVisibility(View.VISIBLE);
        offset(0);
        host.hold(true);
    }

    private void offset(float dx) {
        float t = Math.max(-panelWidth, Math.min(0, dx - panelWidth));
        panel.setTranslationX(t);
        scrim.setAlpha(1 + t / panelWidth);
    }

    void open() {
        reveal();
        panel.animate().translationX(0).setDuration(160).start();
        scrim.animate().alpha(1).setDuration(160).start();
        focusFirst();
    }

    void close() {
        if (!shown || busy) return;
        if (staged != null) {
            SaveTransfer.discardStaged();
            staged = null;
        }
        panel.animate().translationX(-panelWidth).setDuration(140).withEndAction(() -> {
            root.setVisibility(View.GONE);
            shown = false;
            host.hold(false);
            host.gameFocus();
        }).start();
        scrim.animate().alpha(0).setDuration(140).start();
    }

    /** B, Back or Escape: a page back, or closed. Other keys go to the focused button (A presses it). */
    boolean onKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (k != KeyEvent.KEYCODE_BACK && k != KeyEvent.KEYCODE_BUTTON_B && k != KeyEvent.KEYCODE_ESCAPE) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) back();
        return true;
    }

    private void back() {
        switch (page) {
            case PAD_TARGET: showPad(padSource); break;
            case PAD: show(MAIN); break;
            case IMPORT: cancelImport(); break;
            default: close();
        }
    }

    // --- pages

    private void show(int p) {
        page = p;
        render();
        focusFirst();
    }

    private void render() {
        content.removeAllViews();
        panel.post(() -> panel.scrollTo(0, 0));  // after the new page's layout
        switch (page) {
            case PAD: renderPad(); break;
            case PAD_TARGET: renderPadTarget(); break;
            case IMPORT: renderImport(); break;
            default: renderMain();
        }
    }

    private void renderMain() {
        content.addView(Ui.text(a, "noitadroid", 22, ACCENT, true));
        content.addView(Ui.text(a, t.getString(R.string.menu_paused), 13, DIM, false));

        section(R.string.menu_saves);
        hint(t.getString(R.string.menu_saves_hint));
        buttons(button(R.string.menu_export, v -> pickExport()), button(R.string.menu_import, v -> pickImport()));
        if (saveMessage != null) content.addView(Ui.text(a, saveMessage, 13, saveColor, false), Ui.full(0, dp(8), 0, 0));

        section(R.string.menu_pad);
        String swaps = pad.summary();
        hint(swaps.isEmpty() ? t.getString(R.string.menu_pad_default) : t.getString(R.string.menu_pad_swapped, swaps));
        buttons(button(R.string.menu_pad_edit, v -> showPad(0)));

        section(R.string.menu_display);
        buttons(button(stats.isOn() ? R.string.menu_overlay_on : R.string.menu_overlay_off, v -> {
            stats.setOn(!stats.isOn());
            ((Button) v).setText(t.getString(stats.isOn() ? R.string.menu_overlay_on : R.string.menu_overlay_off));
        }));
        Button lang = button(R.string.menu_language, v -> {
            Lang.set(a, Lang.other(a));
            t = Lang.wrap(a);
            render();
            content.post(() -> {
                View again = content.findViewWithTag("lang");
                if (again != null) again.requestFocus();
            });
        });
        lang.setTag("lang");
        buttons(lang);

        Button resume = button(R.string.menu_resume, v -> close());
        content.addView(resume, Ui.full(0, dp(22), 0, 0));
    }

    private void renderPad() {
        content.addView(Ui.text(a, t.getString(R.string.pad_title), 20, ACCENT, true));
        hint(t.getString(R.string.pad_hint));
        for (int i = 0; i < PadMap.KEYS.length; i++) {
            int src = i, t = pad.target(i);
            Button b = button(PadMap.NAMES[i] + "   →   " + PadMap.NAMES[t], v -> showPadTarget(src));
            if (t != i) b.setTextColor(ACCENT);
            b.setTag(i);
            content.addView(b, Ui.full(0, dp(6), 0, 0));
        }
        buttons(button(R.string.pad_reset, v -> {
            pad.reset();
            showPad(0);
        }), button(R.string.menu_back, v -> show(MAIN)));
    }

    private void showPad(int focus) {
        page = PAD;
        render();
        View v = content.findViewWithTag(focus);
        if (v != null) v.requestFocus();
        else focusFirst();
    }

    private void showPadTarget(int source) {
        padSource = source;
        show(PAD_TARGET);
    }

    private void renderPadTarget() {
        content.addView(Ui.text(a, t.getString(R.string.pad_choose, PadMap.NAMES[padSource]), 20, ACCENT, true));
        hint(t.getString(R.string.pad_choose_hint));
        LinearLayout row = null;
        for (int t = 0; t < PadMap.KEYS.length; t++) {
            if (t % 2 == 0) {
                row = new LinearLayout(a);
                content.addView(row, Ui.full(0, dp(6), 0, 0));
            }
            int target = t;
            Button b = button(PadMap.NAMES[t], v -> {
                pad.set(padSource, target);
                showPad(padSource);
            });
            if (t == pad.target(padSource)) b.setTextColor(ACCENT);
            LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (t % 2 == 1) half.leftMargin = dp(8);
            row.addView(b, half);
        }
        buttons(button(R.string.menu_back, v -> showPad(padSource)));
    }

    private void renderImport() {
        content.addView(Ui.text(a, t.getString(R.string.import_title), 20, ACCENT, true));
        List<String> what = new ArrayList<>();
        if (staged.run) what.add(t.getString(R.string.import_has_run));
        if (staged.progress) what.add(t.getString(R.string.import_has_progress));
        if (staged.stats) what.add(t.getString(R.string.import_has_stats));
        hint(t.getString(R.string.import_what, String.join(t.getString(R.string.list_sep), what), staged.files,
                Formatter.formatFileSize(a, staged.bytes)));
        content.addView(Ui.text(a, t.getString(R.string.import_warning), 13, TEXT, false), Ui.full(0, dp(10), 0, 0));
        buttons(button(R.string.import_go, v -> {
            try {
                SaveTransfer.requestApply();
                busy = true;
                host.restart();
            } catch (java.io.IOException e) {
                cancelImport();
                message(t.getString(R.string.import_failed, e.getMessage()), BAD);
            }
        }), button(R.string.cancel, v -> cancelImport()));
    }

    private void cancelImport() {
        SaveTransfer.discardStaged();
        staged = null;
        show(MAIN);
    }

    // --- saves

    private void pickExport() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "noita-save-" + SaveTransfer.stamp() + ".zip");
        host.pick(i, REQ_EXPORT);
    }

    private void pickImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed",
                        "application/octet-stream"});
        host.pick(i, REQ_IMPORT);
    }

    /** The file pickers' answers; true when it was one of the menu's. */
    boolean onResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_EXPORT && requestCode != REQ_IMPORT) return false;
        Uri uri = resultCode == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (uri == null) return true;
        busy = true;
        message(t.getString(requestCode == REQ_EXPORT ? R.string.export_running : R.string.import_reading), DIM);
        new Thread(() -> {
            if (requestCode == REQ_EXPORT) {
                try (OutputStream out = a.getContentResolver().openOutputStream(uri, "w")) {
                    long n = SaveTransfer.export(out);
                    done(() -> message(t.getString(R.string.export_done, Formatter.formatFileSize(a, n)), GOOD));
                } catch (Exception e) {
                    done(() -> message(t.getString(R.string.export_failed, e.getMessage()), BAD));
                }
            } else {
                try (InputStream in = a.getContentResolver().openInputStream(uri)) {
                    SaveTransfer.Staged s = SaveTransfer.stage(in);
                    done(() -> {
                        staged = s;
                        saveMessage = null;
                        show(IMPORT);
                    });
                } catch (Exception e) {
                    done(() -> message(t.getString(R.string.import_failed, e.getMessage()), BAD));
                }
            }
        }, "noitadroid-saves").start();
        return true;
    }

    private void done(Runnable r) {
        a.runOnUiThread(() -> {
            busy = false;
            r.run();
        });
    }

    private void message(CharSequence text, int color) {
        saveMessage = text;
        saveColor = color;
        if (shown && page == MAIN) {
            render();
            focusFirst();
        }
    }

    // --- views

    private void section(int title) {
        content.addView(Ui.text(a, t.getString(title), 15, TEXT, true), Ui.full(0, dp(18), 0, 0));
    }

    private void hint(String s) {
        content.addView(Ui.text(a, s, 13, DIM, false), Ui.full(0, dp(4), 0, 0));
    }

    private Button button(int label, View.OnClickListener l) {
        return Ui.button(a, t.getString(label), l);
    }

    private Button button(String label, View.OnClickListener l) {
        return Ui.button(a, label, l);
    }

    private void buttons(Button... bs) {
        LinearLayout row = new LinearLayout(a);
        for (int i = 0; i < bs.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) p.leftMargin = dp(8);
            row.addView(bs[i], p);
        }
        content.addView(row, Ui.full(0, dp(8), 0, 0));
    }

    private void focusFirst() {
        content.post(() -> {
            View first = firstButton(content);
            if (first != null) first.requestFocus();
        });
    }

    private static View firstButton(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof Button) return v;
            if (v instanceof ViewGroup) {
                View inner = firstButton((ViewGroup) v);
                if (inner != null) return inner;
            }
        }
        return null;
    }

    private int dp(float v) {
        return Ui.dp(a, v);
    }
}
