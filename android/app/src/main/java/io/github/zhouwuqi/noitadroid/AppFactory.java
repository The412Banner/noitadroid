package io.github.zhouwuqi.noitadroid;

import android.app.AppComponentFactory;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dalvik.system.PathClassLoader;

/**
 * The app's class loader, with FMOD's Java side when the user imported FMOD (FmodImport: files/fmod/fmod-dex.jar).
 * libfmod.so's JNI_OnLoad finds org.fmod.* through the class loader of the code that loads it, and SDL (and so the
 * game) loads everything from the app's: so that one has to have them. The platform asks for it when the app's
 * process starts; after an import the app restarts.
 */
public class AppFactory extends AppComponentFactory {
    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo ai) {
        File dex = new File(ai.dataDir, "files/fmod/" + FmodImport.DEX);
        if (!dex.isFile()) return cl;
        try {
            dex.setReadOnly();  // Android 14 loads no code from a writable file
            List<String> path = new ArrayList<>(Collections.singletonList(ai.sourceDir));
            if (ai.splitSourceDirs != null) Collections.addAll(path, ai.splitSourceDirs);
            path.add(dex.getPath());
            return new PathClassLoader(String.join(File.pathSeparator, path), ai.nativeLibraryDir, cl.getParent());
        } catch (RuntimeException e) {
            Log.e("noitadroid", "FMOD's classes: keeping the default class loader", e);
            return cl;
        }
    }
}
