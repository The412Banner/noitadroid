package io.github.zhouwuqi.noitadroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.util.Arrays;
import java.util.List;

/**
 * What the on-screen controls are and how the player laid them out (the in-game menu's Touch controls page): each
 * one's place as a fraction of the screen, so a layout survives a change of resolution; its size; whether it's shown.
 * Each acts as a part of a gamepad (TouchOverlay), so what it does in the game is that part's default binding, which
 * the game's own settings can change.
 */
final class TouchControls {
    static final int AUTO = 0, ON = 1, OFF = 2;
    enum Kind { MOVE, AIM, BUTTON, FLY }

    private static final float MIN_SCALE = 0.6f, MAX_SCALE = 2f, SCALE_STEP = 0.1f;
    private static final int MIN_OPACITY = 20, MAX_OPACITY = 100, OPACITY_STEP = 10;

    static final class Control {
        final String id;
        final Kind kind;
        final int code;  // BUTTON: the Android key code of the gamepad button
        final int label;  // the string with its name
        final String[] icon;
        final float defaultX, defaultY;
        final int sizeDp;
        final boolean shownByDefault;
        float x, y, scale;
        boolean shown;

        Control(String id, Kind kind, int code, int label, String[] icon, float x, float y, int sizeDp, boolean shown) {
            this.id = id;
            this.kind = kind;
            this.code = code;
            this.label = label;
            this.icon = icon;
            this.defaultX = x;
            this.defaultY = y;
            this.sizeDp = sizeDp;
            this.shownByDefault = shown;
        }

        boolean isStick() {
            return kind == Kind.MOVE || kind == Kind.AIM;
        }
    }

    private static final int STICK = 124, BUTTON = 54, SMALL = 44;
    final List<Control> all = Arrays.asList(
            new Control("move", Kind.MOVE, 0, R.string.touch_move, null, 0.125f, 0.70f, STICK, true),
            new Control("aim", Kind.AIM, 0, R.string.touch_aim, TouchIcons.AIM, 0.875f, 0.70f, STICK, true),
            new Control("kick", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_B, R.string.touch_kick, TouchIcons.KICK, 0.715f, 0.86f, BUTTON, true),
            new Control("throw", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_Y, R.string.touch_throw, TouchIcons.THROW, 0.695f, 0.62f, BUTTON, true),
            new Control("interact", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_A, R.string.touch_interact, TouchIcons.INTERACT, 0.755f, 0.40f, BUTTON, true),
            new Control("inventory", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_SELECT, R.string.touch_inventory, TouchIcons.BAG, 0.845f, 0.31f, BUTTON, true),
            new Control("prev", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_L1, R.string.touch_prev, TouchIcons.PREV, 0.06f, 0.33f, SMALL, true),
            new Control("next", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_R1, R.string.touch_next, TouchIcons.NEXT, 0.135f, 0.33f, SMALL, true),
            new Control("pause", Kind.BUTTON, KeyEvent.KEYCODE_BUTTON_START, R.string.touch_pause, TouchIcons.PAUSE, 0.74f, 0.09f, SMALL, true),
            new Control("fly", Kind.FLY, 0, R.string.touch_fly, TouchIcons.FLY, 0.625f, 0.40f, BUTTON, false));

    private final SharedPreferences prefs;
    private int mode, opacity;
    private float size;

    TouchControls(Context c) {
        prefs = c.getSharedPreferences("touch", Context.MODE_PRIVATE);
        mode = prefs.getInt("mode", AUTO);
        opacity = prefs.getInt("opacity", 60);
        size = prefs.getFloat("size", 1);
        for (Control k : all) {
            k.x = prefs.getFloat(k.id + ".x", k.defaultX);
            k.y = prefs.getFloat(k.id + ".y", k.defaultY);
            k.scale = prefs.getFloat(k.id + ".scale", 1);
            k.shown = prefs.getBoolean(k.id + ".shown", k.shownByDefault);
        }
    }

    /** The one control still there while the inventory is open: it closes it. */
    boolean closesInventory(Control k) {
        return k.id.equals("inventory");
    }

    int mode() {
        return mode;
    }

    void setMode(int m) {
        mode = m;
        prefs.edit().putInt("mode", m).apply();
    }

    /** How opaque the controls are, in percent. */
    int opacity() {
        return opacity;
    }

    void changeOpacity(int steps) {
        opacity = Math.max(MIN_OPACITY, Math.min(MAX_OPACITY, opacity + steps * OPACITY_STEP));
        prefs.edit().putInt("opacity", opacity).apply();
    }

    /** All the controls' size, as a multiple of their own. */
    float size() {
        return size;
    }

    void changeSize(int steps) {
        size = stepped(size, steps);
        prefs.edit().putFloat("size", size).apply();
    }

    void changeScale(Control k, int steps) {
        k.scale = stepped(k.scale, steps);
        save(k);
    }

    private static float stepped(float v, int steps) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.round((v + steps * SCALE_STEP) * 10) / 10f));
    }

    void save(Control k) {
        prefs.edit().putFloat(k.id + ".x", k.x).putFloat(k.id + ".y", k.y).putFloat(k.id + ".scale", k.scale)
                .putBoolean(k.id + ".shown", k.shown).apply();
    }

    /** Back to the default places, sizes and set of controls; whether they're on and how opaque stays. */
    void resetLayout() {
        size = 1;
        prefs.edit().putFloat("size", 1).apply();
        for (Control k : all) {
            k.x = k.defaultX;
            k.y = k.defaultY;
            k.scale = 1;
            k.shown = k.shownByDefault;
            save(k);
        }
    }
}
