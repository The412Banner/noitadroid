package io.github.zhouwuqi.noitadroid;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.libsdl.app.SDLActivity;

/**
 * SDL's activity, running libmain.so's SDL_main (runtime/android_main.c). The game files, saves and log live in
 * shared storage (/sdcard/noitadroid), which needs all-files access; FMOD's Java side starts before the native engine.
 */
public class NoitaActivity extends SDLActivity {
    private static final String TAG = "noitadroid";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (!Environment.isExternalStorageManager()) {
            Toast.makeText(this, "noitadroid needs \"All files access\" to read the game from /sdcard/noitadroid",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        }
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

    /** runtime/android_main.c reads "--home" (shared storage) and "--fmod-dir" (FMOD's extracted libraries). */
    @Override
    protected String[] getArguments() {
        String home = Environment.getExternalStorageDirectory().getAbsolutePath() + "/noitadroid";
        return new String[] {"--home", home, "--fmod-dir", getApplicationInfo().nativeLibraryDir};
    }
}
