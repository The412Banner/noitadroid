package io.github.zhouwuqi.noitadroid;

import static io.github.zhouwuqi.noitadroid.TouchControls.Control;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The bar over the screen while the on-screen controls are being laid out (the menu's Edit layout): the control last
 * touched, its size, hiding it, and Done. It keeps off the part of the screen that control is in.
 */
final class TouchEditor {
    private final Activity a;
    private final TouchControls controls;
    private final TouchOverlay touch;
    private final LinearLayout bar;
    private final TextView name;
    private final Button less, more, hide, done;
    private Context t;

    TouchEditor(Activity a, FrameLayout parent, TouchControls controls, TouchOverlay touch, Runnable onDone) {
        this.a = a;
        this.controls = controls;
        this.touch = touch;
        t = a;
        bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(a, 8);
        bar.setPadding(pad * 2, pad, pad, pad);
        bar.setBackground(Ui.rounded(a, Ui.PANEL, 0, 0));
        bar.setClickable(true);
        bar.setVisibility(View.GONE);
        name = Ui.text(a, "", 14, Ui.TEXT, true);
        less = Ui.button(a, "−", v -> resize(-1));
        more = Ui.button(a, "+", v -> resize(1));
        hide = Ui.button(a, "", v -> hideSelected());
        done = Ui.button(a, "", v -> onDone.run());
        bar.addView(name, Ui.wrap(0, 0, pad, 0));
        for (Button b : new Button[] {less, more, hide, done}) bar.addView(b, Ui.wrap(pad / 2, 0, 0, 0));
        parent.addView(bar, placed(Gravity.TOP));
    }

    private FrameLayout.LayoutParams placed(int vertical) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, vertical | Gravity.CENTER_HORIZONTAL);
        p.topMargin = p.bottomMargin = Ui.dp(a, 6);
        return p;
    }

    void show(Context language) {
        t = language;
        hide.setText(t.getString(R.string.touch_edit_hide));
        done.setText(t.getString(R.string.touch_edit_done));
        touch.setEditing(true, this::selected);
        selected(null);
        bar.setVisibility(View.VISIBLE);
        done.requestFocus();
    }

    void hide() {
        bar.setVisibility(View.GONE);
        touch.setEditing(false, null);
    }

    private void selected(Control k) {
        boolean any = k != null;
        name.setText(any ? t.getString(R.string.touch_edit_selected, t.getString(k.label), Math.round(k.scale * 100))
                : t.getString(R.string.touch_edit_hint));
        less.setVisibility(any ? View.VISIBLE : View.GONE);
        more.setVisibility(any ? View.VISIBLE : View.GONE);
        hide.setVisibility(any ? View.VISIBLE : View.GONE);
        if (any) {
            View parent = (View) bar.getParent();
            bar.setLayoutParams(placed(k.y * parent.getHeight() < parent.getHeight() / 3f ? Gravity.BOTTOM : Gravity.TOP));
        }
    }

    private void resize(int steps) {
        Control k = touch.selected();
        if (k == null) return;
        controls.changeScale(k, steps);
        touch.keepOnScreen(k);
        controls.save(k);
        touch.invalidate();
        selected(k);
    }

    private void hideSelected() {
        Control k = touch.selected();
        if (k == null) return;
        k.shown = false;
        controls.save(k);
        touch.invalidate();
        touch.setEditing(true, this::selected);
        selected(null);
    }
}
