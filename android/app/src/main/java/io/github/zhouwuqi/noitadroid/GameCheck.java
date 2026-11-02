package io.github.zhouwuqi.noitadroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * What the game needs before it can start, checked the way runtime/android_main.c and runtime/main.c will use it:
 * all-files access to shared storage, the game files in /sdcard/noitadroid/Noita, and the build of the game this APK
 * was recompiled from. tools/android/build_game.py writes that build's facts into the APK
 * (assets/noitadroid.properties: the sha256 of noita.exe and msvcp120.dll, data.wak's size, FMOD's version).
 */
final class GameCheck {
    enum State { OK, MISSING, WRONG, WAITING, INFO }

    static final String GOG_BUILD = "20250125-1640";
    static final String[] REQUIRED = {"noita.exe", "msvcp120.dll", "data/data.wak"};

    final State access, files, version, sound;
    final File home = new File(Environment.getExternalStorageDirectory(), "noitadroid");
    final File gameDir = new File(home, "Noita");
    final List<String> missing = new ArrayList<>();
    long wakSize = -1, wakExpected = -1;
    String wrongFile, wrongHash;
    final String fmodVersion;

    GameCheck(Context context) {
        Properties build = new Properties();
        try (InputStream in = context.getAssets().open("noitadroid.properties")) {
            build.load(in);
        } catch (IOException e) {  // a build without them (the smoke APK): only what's there is checked
        }
        fmodVersion = build.getProperty("fmod", "");
        boolean fmodLibs = new File(context.getApplicationInfo().nativeLibraryDir, "libfmodstudio.so").exists();
        sound = fmodLibs ? State.OK : State.INFO;

        access = Environment.isExternalStorageManager() ? State.OK : State.MISSING;
        if (access != State.OK) {
            files = version = State.WAITING;
            return;
        }
        for (String name : REQUIRED) {
            if (!new File(gameDir, name).isFile()) missing.add(name);
        }
        wakExpected = Long.parseLong(build.getProperty("data.wak.size", "-1"));
        wakSize = new File(gameDir, "data/data.wak").length();
        if (!missing.isEmpty()) files = State.MISSING;
        else if (wakExpected >= 0 && wakSize != wakExpected) files = State.WRONG;  // copied in part (or another build)
        else files = State.OK;
        if (!missing.contains("noita.exe") && !missing.contains("msvcp120.dll")) {
            SharedPreferences cache = context.getSharedPreferences("hashes", Context.MODE_PRIVATE);
            for (String name : new String[] {"noita.exe", "msvcp120.dll"}) {
                String want = build.getProperty(name + ".sha256");
                String have = sha256(new File(gameDir, name), cache);
                if (want != null && !want.equalsIgnoreCase(have)) {
                    wrongFile = name;
                    wrongHash = have;
                    break;
                }
            }
            version = wrongFile == null ? State.OK : State.WRONG;
        } else {
            version = State.WAITING;
        }
    }

    /** Everything the game needs; sound is optional. */
    boolean ready() {
        return access == State.OK && files == State.OK && version == State.OK;
    }

    /** noita.exe is 15 MB: its hash is kept for the file's size and time, so later starts don't read it again. */
    private static String sha256(File file, SharedPreferences cache) {
        String key = file.getPath() + ":" + file.length() + ":" + file.lastModified();
        String hex = cache.getString(key, null);
        if (hex != null) return hex;
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 20];
            for (int n; (n = in.read(buf)) > 0; ) digest.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format("%02x", b));
            hex = sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return "";
        }
        cache.edit().putString(key, hex).apply();
        return hex;
    }
}
