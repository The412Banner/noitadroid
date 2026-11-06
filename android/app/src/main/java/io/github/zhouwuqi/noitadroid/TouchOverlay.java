package io.github.zhouwuqi.noitadroid;

import static io.github.zhouwuqi.noitadroid.TouchControls.Control;
import static io.github.zhouwuqi.noitadroid.TouchControls.Kind;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLControllerManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The on-screen controls over the game. They are a gamepad to the game, added to SDL as any other while they're in
 * play: it aims exactly where the stick points, and a mouse couldn't, as its camera leans after the cursor and the
 * player moves on the screen. The left stick is the left stick, with the left trigger (flying) when it's pushed up;
 * the right stick is the right stick, with the right trigger (the wand) once it's past its inner ring. What's on the
 * screen decides what's there (GameState): the menus have nothing in their way, and the inventory only the control that
 * closes it.
 *
 * A finger anywhere else is the game's mouse, for the menus, the inventory and pointing at things, with or without the
 * controls. It moves the mouse first and presses a moment later (MOUSE_HOVER_MS), as a real mouse does: the game's
 * interface picks up what it was over the frame before, so a press in the frame of the move wouldn't drag a spell.
 *
 * NoitaActivity gives it the game's touches (onGameTouch) ahead of the views. In the layout editor (setEditing) the
 * game is held, and it takes touches as a view: one finger drags a control.
 */
final class TouchOverlay extends View {
    private static final int AXIS_LX = 0, AXIS_LY = 1, AXIS_RX = 2, AXIS_RY = 3, AXIS_LT = 4, AXIS_RT = 5;
    private static final int PAD = 0x4E440001;  // its device id: Android's are small numbers
    private static final int PAD_BUTTONS = 0x7FFF, PAD_AXES = 0x3F;  // all of SDL's standard ones
    private static final int PAD_VENDOR = 0x1209, PAD_PRODUCT = 0x4E44;
    private static final float MOVE_WALK = 0.25f, MOVE_FLY = 0.5f, MOVE_DOWN = 0.75f;  // of the stick's radius
    private static final float AIM_DEAD = 0.15f, AIM_FIRE = 0.55f;
    private static final float AIM_LEAST = 0.6f;  // aim: the stick's value just outside the dead zone, past the game's threshold
    private static final float HIT_MARGIN = 1.15f;  // a touch just outside a control still counts
    private static final int MAX_POINTERS = 32;
    private static final long MIN_PRESS_MS = 100;  // the game looks at the buttons once a frame: a tap must span one
    private static final long MOUSE_HOVER_MS = 70;
    private static final long POLL_MS = 100;

    // The app icon's embers.
    private static final int[] RAMP = {0xFFFFF4C4, 0xFFFFDD73, 0xFFFFB443, 0xFFFF8A3A, 0xFFF2603F};
    private static final int OUTLINE = 0xFF140B1E, FILL = 0xB01A1222, FILL_PRESSED = 0xF0FF8A3A, RING = 0xFFFFDD73,
            RING_DIM = 0xFFA08A5C, ICON_PRESSED = 0xFF2A1638, STICK_FILL = 0x661A1222;

    private final TouchControls controls;
    private final InputManager input;
    private final Paint paint = new Paint();
    private final Paint frame = new Paint();
    private final RectF rect = new RectF();
    private final Map<String, Bitmap> sprites = new HashMap<>();
    private final int cell, frameWidth;

    // Playing: which control each finger holds, and what's pressed.
    private final Control[] held = new Control[MAX_POINTERS];
    private final Map<Control, float[]> knobs = new HashMap<>();  // a held stick's knob, -1..1
    private final Map<Control, Long> pressedAt = new HashMap<>();
    private final Map<Control, Runnable> letGo = new HashMap<>();  // a tap's release, waiting out MIN_PRESS_MS
    private final GameState game = new GameState();
    private GameState.Screen screen = GameState.Screen.MENU;
    private final Runnable attach = this::attachPad;
    private final Runnable poll = this::poll;
    private final InputManager.InputDeviceListener devices;
    private boolean active, padAdded, stickFlies, buttonFlies;

    // The mouse: the first finger on no control.
    private final Runnable mousePress = this::mousePress, mouseRelease = this::mouseRelease;
    private int mouseFinger = -1;
    private boolean mouseDown, mousePending;
    private float mouseX, mouseY;
    private long mouseDownAt;

    // The layout editor.
    private boolean editing;
    private Control selected;
    private float dragX, dragY;
    private Consumer<Control> onSelect;

    TouchOverlay(Context c, TouchControls controls) {
        super(c);
        this.controls = controls;
        input = (InputManager) c.getSystemService(Context.INPUT_SERVICE);
        devices = new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int id) {
                refresh();
            }

            @Override
            public void onInputDeviceRemoved(int id) {
                refresh();
            }

            @Override
            public void onInputDeviceChanged(int id) {
                refresh();
            }
        };
        input.registerInputDeviceListener(devices, new Handler(Looper.getMainLooper()));
        cell = Math.max(2, Ui.dp(c, 1.25f));
        frameWidth = Ui.dp(c, 2);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(frameWidth);
        frame.setColor(Ui.ACCENT);
        setFocusable(false);
        refresh();
    }

    /** True while the controls are in play: on, or on auto and no gamepad's connected. */
    boolean isActive() {
        return active;
    }

    private static boolean gamepadConnected() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;
        }
        return false;
    }

    /** After a change of settings or of the gamepads connected. */
    void refresh() {
        int mode = controls.mode();
        active = mode == TouchControls.ON || (mode == TouchControls.AUTO && !gamepadConnected());
        removeCallbacks(poll);
        if (active) {
            attachPad();
            poll();
        } else {
            for (int id = 0; id < MAX_POINTERS; id++) {  // the mouse goes on without the controls
                if (held[id] != null) {
                    release(held[id]);
                    held[id] = null;
                }
            }
            detachPad();
        }
        setVisibility(active || editing ? VISIBLE : INVISIBLE);  // laid out either way: the mouse goes by its size
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        input.unregisterInputDeviceListener(devices);
        removeCallbacks(poll);
        removeCallbacks(attach);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) refresh();  // polls again
        else removeCallbacks(poll);
    }

    private void poll() {
        GameState.Screen now = game.read();
        if (now != screen) {
            screen = now;
            invalidate();
        }
        postDelayed(poll, POLL_MS);
    }

    /** Whether this control is there now: all of them in the editor, which can move those the game hides now. */
    private boolean present(Control k) {
        if (!k.shown) return false;
        return editing || screen == GameState.Screen.PLAY || (screen == GameState.Screen.INVENTORY && controls.closesInventory(k));
    }

    /** Adds the gamepad once the game has its SDL up and running, and so can open it. */
    private void attachPad() {
        removeCallbacks(attach);
        if (padAdded || !active) return;
        if (NoitaActivity.nativeFrames() == 0) {
            postDelayed(attach, 500);
            return;
        }
        SDLControllerManager.nativeAddJoystick(PAD, "noitadroid touch controls", "noitadroid-touch", PAD_VENDOR, PAD_PRODUCT,
                false, PAD_BUTTONS, 6, PAD_AXES, 0, 0);
        padAdded = true;
    }

    private void detachPad() {
        removeCallbacks(attach);
        if (!padAdded) return;
        SDLControllerManager.nativeRemoveJoystick(PAD);
        padAdded = false;
    }

    // --- playing

    /** A touch on its way to the game; true when it was handled here. A real mouse's stays SDL's. */
    boolean onGameTouch(MotionEvent ev) {
        if (!ev.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                releaseAll();  // no finger is down: nothing should be held
                down(ev, 0);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                down(ev, ev.getActionIndex());
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < ev.getPointerCount(); i++) move(ev, i);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                up(ev.getPointerId(ev.getActionIndex()));
                break;
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < ev.getPointerCount(); i++) up(ev.getPointerId(i));
                break;
            default:
        }
        return true;
    }

    private void down(MotionEvent ev, int i) {
        int id = ev.getPointerId(i);
        if (id >= MAX_POINTERS) return;
        float x = ev.getX(i), y = ev.getY(i);
        Control k = active ? hit(x, y) : null;
        if (k != null) {
            held[id] = k;
            press(k);
            if (k.isStick()) moveStick(k, x, y);
            invalidate();
        } else if (mouseFinger < 0) {
            endMouse();  // the last tap's, if it's still under way
            mouseFinger = id;
            moveMouse(x, y);
            mousePending = true;
            postDelayed(mousePress, MOUSE_HOVER_MS);
        }
    }

    private void move(MotionEvent ev, int i) {
        int id = ev.getPointerId(i);
        if (id >= MAX_POINTERS) return;
        if (id == mouseFinger) moveMouse(ev.getX(i), ev.getY(i));
        else if (held[id] != null && held[id].isStick()) moveStick(held[id], ev.getX(i), ev.getY(i));
    }

    private void up(int id) {
        if (id >= MAX_POINTERS) return;
        if (id == mouseFinger) {
            mouseFinger = -1;
            if (mouseDown) postDelayed(mouseRelease, Math.max(0, mouseDownAt + MIN_PRESS_MS - SystemClock.uptimeMillis()));
            // else the press to come sees that the finger has gone
        } else if (held[id] != null) {
            release(held[id]);
            held[id] = null;
            invalidate();
        }
    }

    private void moveMouse(float x, float y) {
        mouseX = x;
        mouseY = y;
        SDLActivity.onNativeMouse(mouseDown ? MotionEvent.BUTTON_PRIMARY : 0,
                mouseDown ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE, x, y, false);
    }

    private void mousePress() {
        mousePending = false;
        mouseDown = true;
        mouseDownAt = SystemClock.uptimeMillis();
        SDLActivity.onNativeMouse(MotionEvent.BUTTON_PRIMARY, MotionEvent.ACTION_DOWN, mouseX, mouseY, false);
        if (mouseFinger < 0) postDelayed(mouseRelease, MIN_PRESS_MS);
    }

    private void mouseRelease() {
        mouseDown = false;
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, mouseX, mouseY, false);
    }

    /** Finishes at once what the last finger left under way. */
    private void endMouse() {
        removeCallbacks(mousePress);
        removeCallbacks(mouseRelease);
        if (mousePending) mousePress();
        removeCallbacks(mouseRelease);
        if (mouseDown) mouseRelease();
    }

    private void press(Control k) {
        if (k.isStick()) return;  // they act when they move
        Runnable pending = letGo.remove(k);
        if (pending != null) {
            removeCallbacks(pending);
            pending.run();
        }
        pressedAt.put(k, SystemClock.uptimeMillis());
        if (k.kind == Kind.FLY) setFlying(stickFlies, true);
        else SDLControllerManager.onNativePadDown(PAD, k.code);
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void release(Control k) {
        switch (k.kind) {
            case MOVE:
                axis(AXIS_LX, 0);
                axis(AXIS_LY, 0);
                setFlying(false, buttonFlies);
                knobs.remove(k);
                break;
            case AIM:
                axis(AXIS_RX, 0);
                axis(AXIS_RY, 0);
                trigger(AXIS_RT, false);
                knobs.remove(k);
                break;
            default:
                Runnable up = () -> {
                    letGo.remove(k);
                    if (k.kind == Kind.FLY) setFlying(stickFlies, false);
                    else SDLControllerManager.onNativePadUp(PAD, k.code);
                };
                long wait = pressedAt.get(k) + MIN_PRESS_MS - SystemClock.uptimeMillis();
                if (wait <= 0) {
                    up.run();
                } else {
                    letGo.put(k, up);
                    postDelayed(up, wait);
                }
        }
    }

    private void moveStick(Control k, float x, float y) {
        float r = sizePx(k) / 2;
        float dx = (x - k.x * getWidth()) / r, dy = (y - k.y * getHeight()) / r;
        float m = (float) Math.hypot(dx, dy);
        if (m > 1) {
            dx /= m;
            dy /= m;
            m = 1;
        }
        knobs.put(k, new float[] {dx, dy});
        if (k.kind == Kind.MOVE) {
            axis(AXIS_LX, Math.abs(dx) < MOVE_WALK ? 0 : Math.signum(dx));  // the game walks past half way: at a touch here
            axis(AXIS_LY, dy > 0 && dy < MOVE_DOWN ? 0 : dy);  // down also drinks: not by a stick pushed along the side
            setFlying(dy < -MOVE_FLY, buttonFlies);
        } else if (m >= AIM_DEAD) {
            float v = AIM_LEAST + (1 - AIM_LEAST) * m;  // the direction counts, however little it's pushed
            axis(AXIS_RX, dx / m * v);
            axis(AXIS_RY, dy / m * v);
            trigger(AXIS_RT, m >= AIM_FIRE);
        } else {
            axis(AXIS_RX, 0);
            axis(AXIS_RY, 0);
            trigger(AXIS_RT, false);
        }
        invalidate();
    }

    /** Flying is the left trigger, which the left stick and the fly button share. */
    private void setFlying(boolean byStick, boolean byButton) {
        stickFlies = byStick;
        buttonFlies = byButton;
        trigger(AXIS_LT, byStick || byButton);
    }

    private static void axis(int axis, float value) {
        SDLControllerManager.onNativeJoy(PAD, axis, value);
    }

    /** A trigger's axis, from -1 at rest (SDL's standard mapping, as Android's values reach it from a real pad). */
    private static void trigger(int axis, boolean on) {
        axis(axis, on ? 1 : -1);
    }

    /** Lets go of everything: the app is leaving the foreground, or the controls are going away. */
    void releaseAll() {
        for (int id = 0; id < MAX_POINTERS; id++) {
            if (held[id] != null) {
                release(held[id]);
                held[id] = null;
            }
        }
        for (Runnable up : new ArrayList<>(letGo.values())) {
            removeCallbacks(up);
            up.run();
        }
        mouseFinger = -1;
        endMouse();
        invalidate();
    }

    // --- the layout editor

    /** Editing starts, with this told of the control selected, or ends. */
    void setEditing(boolean on, Consumer<Control> onSelect) {
        editing = on;
        this.onSelect = onSelect;
        selected = null;
        refresh();
    }

    Control selected() {
        return selected;
    }

    /** After a change to a control's size: it stays on the screen. */
    void keepOnScreen(Control k) {
        float r = sizePx(k) / 2;
        k.x = clampFraction(k.x * getWidth(), r, getWidth());
        k.y = clampFraction(k.y * getHeight(), r, getHeight());
    }

    private static float clampFraction(float pos, float r, int total) {
        return Math.max(r, Math.min(total - r, pos)) / Math.max(total, 1);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!editing) return false;
        float x = ev.getX(), y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                selected = hit(x, y);
                if (selected != null) {
                    dragX = selected.x * getWidth() - x;
                    dragY = selected.y * getHeight() - y;
                }
                onSelect.accept(selected);
                invalidate();
                break;
            case MotionEvent.ACTION_MOVE:
                if (selected != null) {
                    selected.x = (x + dragX) / getWidth();
                    selected.y = (y + dragY) / getHeight();
                    keepOnScreen(selected);
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (selected != null) {
                    controls.save(selected);
                    onSelect.accept(selected);
                }
                break;
            default:
        }
        return true;
    }

    // --- places

    private float sizePx(Control k) {
        return Ui.dp(getContext(), k.sizeDp) * k.scale * controls.size();
    }

    private Control hit(float x, float y) {
        Control best = null;
        float nearest = HIT_MARGIN;
        for (Control k : controls.all) {
            if (!present(k)) continue;
            float d = (float) Math.hypot(x - k.x * getWidth(), y - k.y * getHeight()) / (sizePx(k) / 2);
            if (d < nearest) {
                nearest = d;
                best = k;
            }
        }
        return best;
    }

    // --- drawing: pixel art, each sprite made on a coarse grid and drawn with hard edges

    @Override
    protected void onDraw(Canvas canvas) {
        paint.setFilterBitmap(false);
        int alpha = controls.opacity() * 255 / 100;
        for (Control k : controls.all) {
            if (!present(k)) continue;
            boolean down = !editing && isHeld(k);
            paint.setAlpha(down ? Math.min(255, alpha + 60) : alpha);
            int n = gridSize(k);
            float cx = k.x * getWidth(), cy = k.y * getHeight();
            draw(canvas, sprite(k, n, down), cx, cy, n);
            float[] knob = knobs.get(k);
            if (k.isStick()) {
                int kn = n * 2 / 5 | 1;
                float r = n * cell / 2f;
                draw(canvas, knobSprite(k, kn, down), cx + (knob != null ? knob[0] * r * 0.7f : 0),
                        cy + (knob != null ? knob[1] * r * 0.7f : 0), kn);
            }
            if (editing && k == selected) {
                float r = n * cell / 2f + frameWidth;
                rect.set(cx - r, cy - r, cx + r, cy + r);
                canvas.drawRect(rect, frame);
            }
        }
    }

    private boolean isHeld(Control k) {
        for (Control h : held) if (h == k) return true;
        return false;
    }

    private int gridSize(Control k) {
        return Math.max(9, Math.round(sizePx(k) / cell));
    }

    private void draw(Canvas canvas, Bitmap b, float cx, float cy, int n) {
        float half = n * cell / 2f;
        rect.set(Math.round(cx - half), Math.round(cy - half), Math.round(cx - half) + n * cell,
                Math.round(cy - half) + n * cell);
        canvas.drawBitmap(b, null, rect, paint);
    }

    private Bitmap sprite(Control k, int n, boolean down) {
        return sprites.computeIfAbsent(k.id + "/" + n + "/" + down, key -> makeSprite(k, n, down));
    }

    private Bitmap knobSprite(Control k, int n, boolean down) {
        return sprites.computeIfAbsent(k.id + "/knob/" + n + "/" + down, key -> makeKnob(k, n, down));
    }

    private static Bitmap makeSprite(Control k, int n, boolean down) {
        int[] px = new int[n * n];
        boolean stick = k.isStick();
        int fill = stick ? STICK_FILL : down ? FILL_PRESSED : FILL;
        disc(px, n, n / 2f, stick && down ? RING : stick ? RING_DIM : down ? RAMP[0] : RING, fill);
        if (k.kind == Kind.MOVE) {
            chevrons(px, n);
        } else if (k.kind == Kind.AIM) {
            dashedRing(px, n, n / 2f * AIM_FIRE, down ? RAMP[4] : RING_DIM);
        } else if (k.icon != null) {
            icon(px, n, k.icon, down ? ICON_PRESSED : 0);
        }
        return bitmap(px, n);
    }

    private static Bitmap makeKnob(Control k, int n, boolean down) {
        int[] px = new int[n * n];
        disc(px, n, n / 2f, down ? RAMP[0] : RING, down ? FILL_PRESSED : FILL);
        if (k.icon != null) icon(px, n, k.icon, down ? ICON_PRESSED : 0);
        return bitmap(px, n);
    }

    private static Bitmap bitmap(int[] px, int n) {
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, n, 0, 0, n, n);
        return b;
    }

    /** A filled circle with a dark outline and a bright ring inside it. */
    private static void disc(int[] px, int n, float r, int ring, int fill) {
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float d = (float) Math.hypot(x + 0.5f - n / 2f, y + 0.5f - n / 2f);
                if (d > r) continue;
                px[y * n + x] = d > r - 1 ? OUTLINE : d > r - 2 ? ring : fill;
            }
        }
    }

    private static void dashedRing(int[] px, int n, float r, int color) {
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float dx = x + 0.5f - n / 2f, dy = y + 0.5f - n / 2f;
                if (Math.abs(Math.hypot(dx, dy) - r) > 0.5f) continue;
                double a = Math.atan2(dy, dx) + Math.PI;
                if (((int) (a / (Math.PI / 12))) % 2 == 0) px[y * n + x] = color;
            }
        }
    }

    /** The move stick's four arrows. */
    private static void chevrons(int[] px, int n) {
        int size = Math.max(2, n / 14), at = n / 2 - 3 - size;
        for (int a = 0; a < 4; a++) {
            for (int i = 0; i < size * 2; i++) {
                int along = i - size, across = -at + Math.abs(along);
                for (int t = 0; t < 2; t++) {
                    int ax = a == 0 ? along : a == 1 ? -across - t : a == 2 ? along : across + t;
                    int ay = a == 0 ? across + t : a == 1 ? along : a == 2 ? -across - t : along;
                    int x = n / 2 + ax, y = n / 2 + ay;
                    if (x >= 0 && x < n && y >= 0 && y < n) px[y * n + x] = RAMP[1];
                }
            }
        }
    }

    /** An icon, at the whole multiple of its pixels that fits, in the given colour (0: the embers, top down). */
    private static void icon(int[] px, int n, String[] art, int color) {
        int h = art.length, w = art[0].length();
        int scale = Math.max(1, Math.round(n * 0.62f / Math.max(w, h)));
        int x0 = (n - w * scale) / 2, y0 = (n - h * scale) / 2;
        for (int r = 0; r < h; r++) {
            int c = color != 0 ? color : RAMP[Math.min(r * (RAMP.length - 1) / h, RAMP.length - 1)];
            for (int col = 0; col < w; col++) {
                if (art[r].charAt(col) != '#') continue;
                for (int sy = 0; sy < scale; sy++) {
                    for (int sx = 0; sx < scale; sx++) {
                        int x = x0 + col * scale + sx, y = y0 + r * scale + sy;
                        if (x >= 0 && x < n && y >= 0 && y < n) px[y * n + x] = c;
                    }
                }
            }
        }
    }
}
