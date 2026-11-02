package io.github.zhouwuqi.noitadroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Swapped gamepad buttons, for the game only (the in-game menu sets them; NoitaActivity applies them to key events
 * before SDL sees them). Always a permutation: giving A what B did gives B what A did. The sticks, the D-pad and
 * the triggers are axes, not keys, so they stay as they are; the game's own settings remap its actions.
 */
final class PadMap {
    static final int[] KEYS = {KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_START};
    static final String[] NAMES = {"A", "B", "X", "Y", "LB", "RB", "L3", "R3", "Back", "Start"};

    private final SharedPreferences prefs;
    private final int[] to = new int[KEYS.length];  // button i acts as button to[i]

    PadMap(Context context) {
        prefs = context.getSharedPreferences("pad", Context.MODE_PRIVATE);
        for (int i = 0; i < KEYS.length; i++) to[i] = i;
        String saved = prefs.getString("map", "");
        String[] parts = saved.split(",");
        if (parts.length == KEYS.length) {
            int[] read = new int[KEYS.length];
            boolean[] seen = new boolean[KEYS.length];
            try {
                for (int i = 0; i < KEYS.length; i++) {
                    read[i] = Integer.parseInt(parts[i]);
                    if (read[i] < 0 || read[i] >= KEYS.length || seen[read[i]]) throw new NumberFormatException();
                    seen[read[i]] = true;
                }
                System.arraycopy(read, 0, to, 0, KEYS.length);
            } catch (NumberFormatException ignored) {  // not a permutation: the default
            }
        }
    }

    /** The key event the game gets for a gamepad's: another button's when swapped. */
    KeyEvent apply(KeyEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) return e;
        int i = index(e.getKeyCode());
        if (i < 0 || to[i] == i) return e;
        return new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), KEYS[to[i]], e.getRepeatCount(),
                e.getMetaState(), e.getDeviceId(), e.getScanCode(), e.getFlags(), e.getSource());
    }

    int target(int i) {
        return to[i];
    }

    /** Button i now acts as button t; the one that acted as t takes i's old role. */
    void set(int i, int t) {
        for (int j = 0; j < KEYS.length; j++) {
            if (to[j] == t) {
                to[j] = to[i];
                break;
            }
        }
        to[i] = t;
        save();
    }

    void reset() {
        for (int i = 0; i < KEYS.length; i++) to[i] = i;
        save();
    }

    /** "A↔B, X→Y→LB→X", or "" when nothing's swapped. */
    String summary() {
        List<String> parts = new ArrayList<>();
        boolean[] done = new boolean[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            if (done[i] || to[i] == i) continue;
            StringBuilder cycle = new StringBuilder(NAMES[i]);
            int n = 1;
            for (int j = to[i]; j != i; j = to[j], n++) {
                cycle.append("→").append(NAMES[j]);
                done[j] = true;
            }
            done[i] = true;
            parts.add(n == 2 ? NAMES[i] + "↔" + NAMES[to[i]] : cycle.append("→").append(NAMES[i]).toString());
        }
        return String.join(", ", parts);
    }

    private static int index(int keyCode) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode) return i;
        return -1;
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < KEYS.length; i++) sb.append(i > 0 ? "," : "").append(to[i]);
        prefs.edit().putString("map", sb.toString()).apply();
    }
}
