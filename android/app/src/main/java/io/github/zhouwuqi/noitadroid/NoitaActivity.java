package io.github.zhouwuqi.noitadroid;

import android.os.Bundle;
import android.util.Log;

import org.libsdl.app.SDLActivity;

/** SDL's activity, running libmain.so's SDL_main. FMOD's Java side must be initialised before the native engine starts. */
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

    @Override
    protected String[] getLibraries() {
        return new String[] {"SDL2", "main"};
    }

    /** runtime/android_main.c takes "--fmod-dir": FMOD's libraries, when the APK has them, are extracted there. */
    @Override
    protected String[] getArguments() {
        return new String[] {"--fmod-dir", getApplicationInfo().nativeLibraryDir};
    }
}
