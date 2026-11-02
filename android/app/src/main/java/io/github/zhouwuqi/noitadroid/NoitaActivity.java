package io.github.zhouwuqi.noitadroid;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.util.Collections;

/**
 * SDL's activity, running libmain.so's SDL_main (runtime/android_main.c). The game files, saves and log live in
 * shared storage (/sdcard/noitadroid), which needs all-files access: SetupActivity, the launcher's, sees to that and
 * the game files before it starts this one. FMOD's Java side starts before the native engine.
 *
 * Over SDL's view of the game it puts the in-game menu (GameDrawer, from the left edge) and the FPS display
 * (StatsOverlay); gamepad keys pass through PadMap's swaps on their way to SDL.
 */
public class NoitaActivity extends SDLActivity implements GameDrawer.Host {
    private static final String TAG = "noitadroid";

    /** runtime/android_main.c: the frames the game has shown, and holding it at a frame's end (runtime/gles.c). */
    static native long nativeFrames();

    /** Holds the game (or lets it go), after it has shown afterFrames more frames. */
    static native void nativeHold(boolean on, int afterFrames);

    private PadMap pad;
    private GameDrawer drawer;
    private boolean held;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SaveTransfer.applyPending();  // an import from the menu: in before the game reads save00
        try {  // fmod.jar is the user's own download, so it may be absent (the silent FMOD backend then runs)
            Class.forName("org.fmod.FMOD").getMethod("init", android.content.Context.class).invoke(null, this);
        } catch (ReflectiveOperationException e) {
            Log.i(TAG, "no FMOD Java runtime: " + e);
        }
        super.onCreate(savedInstanceState);
        if (mBrokenLibraries || mLayout == null) return;  // SDL shows its error instead of the game

        FrameLayout overlays = new FrameLayout(this);  // over the game's surface; lets touches through
        mLayout.addView(overlays, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        pad = new PadMap(this);
        StatsOverlay stats = new StatsOverlay(this, overlays, NoitaActivity::nativeFrames);
        drawer = new GameDrawer(this, overlays, this, pad, stats);
        // Gesture navigation's back swipe starts at the same edge: keep it off the middle of the left edge (Android
        // grants an app at most 200 dp of each edge), so a swipe there opens the menu.
        mLayout.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int band = Math.min(b - t, Ui.dp(this, 200));
            int top = (b - t - band) / 2;  // in the layout's own coordinates
            v.setSystemGestureExclusionRects(Collections.singletonList(
                    new Rect(0, top, drawer.edgeWidth(), top + band)));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            Class.forName("org.fmod.FMOD").getMethod("close").invoke(null);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (drawer != null && drawer.interceptTouch(ev)) return true;
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (drawer != null && drawer.isShown()) {
            return drawer.onKey(event) || super.dispatchKeyEvent(event);  // to the menu's focused button
        }
        return super.dispatchKeyEvent(pad != null ? pad.apply(event) : event);
    }

    // The menu holds the game at a frame's end; while the app is in the background SDL pauses it its own way, in its
    // event loop, which the game must reach: the hold lifts meanwhile and comes back with the app.
    @Override
    protected void onPause() {
        if (held) nativeHold(false, 0);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (held) nativeHold(true, 4);  // a few frames to draw the game again on the new surface, behind the menu
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (drawer == null || !drawer.onResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    // --- GameDrawer.Host

    @Override
    public void hold(boolean on) {
        held = on;
        nativeHold(on, 0);
    }

    @Override
    public void pick(Intent intent, int requestCode) {
        startActivityForResult(intent, requestCode);
    }

    @Override
    public void restart() {
        startActivity(new Intent(this, RestartActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Process.killProcess(Process.myPid());  // held at a frame's end: the game isn't writing its save
    }

    @Override
    public void gameFocus() {
        if (mSurface != null) mSurface.requestFocus();
    }

    /**
     * FMOD's libraries, when the APK has them, load here rather than only by runtime/fmod.c's dlopen: libfmod.so's
     * JNI_OnLoad keeps the Java VM, which FMOD needs to ask org.fmod.FMOD about the audio output. The dlopen then
     * gets these same libraries.
     */
    @Override
    protected String[] getLibraries() {
        if (new File(getApplicationInfo().nativeLibraryDir, "libfmodstudio.so").exists()) {
            return new String[] {"SDL2", "fmod", "fmodstudio", "main"};
        }
        return new String[] {"SDL2", "main"};
    }

    /** runtime/android_main.c reads "--home" (shared storage) and "--fmod-dir" (FMOD's extracted libraries). */
    @Override
    protected String[] getArguments() {
        String home = Environment.getExternalStorageDirectory().getAbsolutePath() + "/noitadroid";
        return new String[] {"--home", home, "--fmod-dir", getApplicationInfo().nativeLibraryDir};
    }
}
