package io.github.zhouwuqi.noitadroid;

import android.os.Bundle;
import android.os.Environment;
import android.util.Log;

import org.libsdl.app.SDLActivity;

import java.io.File;

/**
 * SDL's activity, running libmain.so's SDL_main (runtime/android_main.c). The game files, saves and log live in
 * shared storage (/sdcard/noitadroid), which needs all-files access: SetupActivity, the launcher's, sees to that and
 * the game files before it starts this one. FMOD's Java side starts before the native engine.
 */
public class NoitaActivity extends SDLActivity {
    private static final String TAG = "noitadroid";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {  // fmod.jar is the user's own download, so it may be absent (the silent FMOD backend then runs)
            Class.forName("org.fmod.FMOD").getMethod("init", android.content.Context.class).invoke(null, this);
        } catch (ReflectiveOperationException e) {
            Log.i(TAG, "no FMOD Java runtime: " + e);
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            Class.forName("org.fmod.FMOD").getMethod("close").invoke(null);
        } catch (ReflectiveOperationException ignored) {
        }
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
