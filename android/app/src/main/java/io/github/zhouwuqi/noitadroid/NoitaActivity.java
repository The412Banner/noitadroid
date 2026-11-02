package io.github.zhouwuqi.noitadroid;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

    /** Moves the game's threads to the cores of a Perf mode (runtime/sched.c). */
    static native void nativeSetCores(String mode);

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

    @Override
    public void setCores(String mode) {
        try {
            nativeSetCores(mode);
        } catch (UnsatisfiedLinkError e) {  // game code built before the setting existed: at the next start
            Log.w(TAG, "nativeSetCores: " + e);
        }
    }

    /**
     * FMOD's libraries, when the APK has them, load here rather than only by runtime/fmod.c's dlopen: libfmod.so's
     * JNI_OnLoad keeps the Java VM, which FMOD needs to ask org.fmod.FMOD about the audio output. The dlopen then
     * gets these same libraries.
     */
    @Override
    protected String[] getLibraries() {
        List<String> libs = new ArrayList<>(Collections.singletonList("SDL2"));
        if (new File(getApplicationInfo().nativeLibraryDir, "libfmodstudio.so").exists()) {
            libs.add("fmod");
            libs.add("fmodstudio");
        }
        if (!localGame(this).exists()) libs.add("main");
        return libs.toArray(new String[0]);
    }

    /**
     * The game's code built on this device (NativeBuild) lives in the app's own storage. When it's there it runs
     * instead of an APK's libmain.so; an APK without one needs it.
     */
    static File localGame(android.content.Context c) {
        return NativeBuild.gameLib(c);
    }

    /**
     * Loads FMOD the user imported (FmodImport) and the game's code built on this device, from the app's own storage.
     * Imported FMOD loads only when its classes are in this class loader (AppFactory): without them its JNI_OnLoad
     * would crash the app, and the game runs silent instead.
     */
    @Override
    public void loadLibraries() {
        super.loadLibraries();
        if (importedFmod() != null) {
            File fmod = FmodImport.dir(this);
            System.load(new File(fmod, "libfmod.so").getPath());
            System.load(new File(fmod, "libfmodstudio.so").getPath());
        }
        File local = localGame(this);
        if (local.exists()) {
            local.setReadOnly();  // Android 14 refuses to load code from a writable file
            System.load(local.getPath());
        }
    }

    /** FMOD's folder when it was imported (not in the APK) and its classes are here; else null. */
    private File importedFmod() {
        if (new File(getApplicationInfo().nativeLibraryDir, "libfmodstudio.so").exists()) return null;
        if (FmodImport.version(this) == null) return null;
        try {
            Class.forName("org.fmod.AudioDevice", false, getClassLoader());
            return FmodImport.dir(this);
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "FMOD is imported, but its classes aren't in the class loader: silent");
            return null;
        }
    }

    /** SDL's thread finds SDL_main in this library. */
    @Override
    protected String getMainSharedObject() {
        File local = localGame(this);
        return local.exists() ? local.getPath() : super.getMainSharedObject();
    }

    /**
     * runtime/android_main.c reads "--home" (shared storage: saves, the log), "--game" (the game folder, GameCheck),
     * "--fmod-dir" (FMOD's libraries: the APK's, or the imported ones) and "--env NAME=VALUE" (the Perf settings).
     * A debuggable build also takes more from its intent, for benchmarks over adb (tools/android/perfbench.sh):
     * "noitadroid.env" (NAME=VALUE ..., after the settings, so it wins) and "noitadroid.args" (the game's own).
     */
    @Override
    protected String[] getArguments() {
        File fmod = importedFmod();
        List<String> args = new ArrayList<>(Arrays.asList("--home", GameCheck.home().getPath(),
                "--game", GameCheck.gameDir(this).getPath(),
                "--fmod-dir", fmod != null ? fmod.getPath() : getApplicationInfo().nativeLibraryDir));
        for (String e : Perf.env(this)) args.addAll(Arrays.asList("--env", e));
        Intent in = getIntent();
        boolean debug = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (debug && in != null) {
            for (String e : words(in.getStringExtra("noitadroid.env"))) args.addAll(Arrays.asList("--env", e));
            args.addAll(words(in.getStringExtra("noitadroid.args")));
        }
        return args.toArray(new String[0]);
    }

    private static List<String> words(String s) {
        List<String> w = new ArrayList<>();
        if (s != null) for (String x : s.trim().split("\\s+")) if (!x.isEmpty()) w.add(x);
        return w;
    }
}
