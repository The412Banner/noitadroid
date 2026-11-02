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
 * all-files access to shared storage, the game files in /sdcard/noitadroid/Noita, the build of the game this APK
 * is for, and (when the app builds it on the device: NativeBuild) the game's native code. The APK's build script
 * writes the game build's facts into it (assets/noitadroid.properties: the sha256 of noita.exe and msvcp120.dll,
 * data.wak's size, FMOD's version).
 *
 * Without the game files, it looks for GOG's offline installer in the usual places (Download, the storage's top,
 * memory cards), which the app can unpack itself.
 */
final class GameCheck {
    enum State { OK, MISSING, WRONG, WAITING, INFO }

    static final String GOG_BUILD = "20250125-1640";
    static final String[] REQUIRED = {"noita.exe", "msvcp120.dll", "data/data.wak"};
    static final String INSTALLER_PREFIX = "setup_noita_" + GOG_BUILD;

    final State access, files, version, sound, nativeCode;
    final File home = home();
    final File gameDir;
    final List<String> missing = new ArrayList<>();
    long wakSize = -1, wakExpected = -1;
    String wrongFile, wrongHash;
    final String fmodVersion;
    /** GOG's offline installer: when the game files are missing, to install from; when they're there, to delete. */
    File installer;
    /** FMOD's Android download, when there's no FMOD yet and there is one. */
    File fmodArchive;
    /** When the app builds the game's code on the device: why it has to, or NONE. */
    NativeBuild.Need need = NativeBuild.Need.NONE;
    final boolean buildsOnDevice;

    static File home() {
        return new File(Environment.getExternalStorageDirectory(), "noitadroid");
    }

    /** The game folder: <home>/Noita, or the one the user chose (a copy on a memory card, say). */
    static File gameDir(Context c) {
        String chosen = c.getSharedPreferences("game", Context.MODE_PRIVATE).getString("dir", null);
        return chosen != null ? new File(chosen) : new File(home(), "Noita");
    }

    /** Another game folder, or back to <home>/Noita (null). */
    static void setGameDir(Context c, File dir) {
        SharedPreferences.Editor e = c.getSharedPreferences("game", Context.MODE_PRIVATE).edit();
        (dir == null ? e.remove("dir") : e.putString("dir", dir.getPath())).apply();
    }

    GameCheck(Context context) {
        Properties build = NativeBuild.properties(context);
        buildsOnDevice = NativeBuild.onDevice(build);
        gameDir = gameDir(context);
        boolean fmodLibs = new File(context.getApplicationInfo().nativeLibraryDir, "libfmodstudio.so").exists();
        String imported = FmodImport.version(context);  // the user's own FMOD, imported into the app
        fmodVersion = fmodLibs ? build.getProperty("fmod", "") : imported != null ? imported : "";
        sound = fmodLibs || imported != null ? State.OK : State.INFO;

        access = Environment.isExternalStorageManager() ? State.OK : State.MISSING;
        if (access != State.OK) {
            files = version = State.WAITING;
            nativeCode = buildsOnDevice ? State.WAITING : State.OK;
            return;
        }
        if (sound != State.OK) fmodArchive = FmodImport.findArchive();
        for (String name : REQUIRED) {
            if (!new File(gameDir, name).isFile()) missing.add(name);
        }
        wakExpected = Long.parseLong(build.getProperty("data.wak.size", "-1"));
        wakSize = new File(gameDir, "data/data.wak").length();
        if (!missing.isEmpty()) {
            files = State.MISSING;
            installer = findInstaller(context);
        } else if (wakExpected >= 0 && wakSize != wakExpected) {
            files = State.WRONG;  // copied in part (or another build)
        } else {
            files = State.OK;
            if (buildsOnDevice) installer = findInstaller(context);  // done with: it can go
        }
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
        if (!buildsOnDevice) {
            nativeCode = State.OK;  // in the APK
        } else if (files != State.OK || version != State.OK) {
            nativeCode = State.WAITING;
        } else {
            need = NativeBuild.need(context, build);
            nativeCode = need == NativeBuild.Need.NONE ? State.OK : State.MISSING;
        }
    }

    /** Everything the game needs; sound is optional. */
    boolean ready() {
        return access == State.OK && files == State.OK && version == State.OK && nativeCode == State.OK;
    }

    /**
     * GOG's offline installer for this build (setup_noita_20250125-1640_...exe) in Download, at the top of shared
     * storage or of a memory card, or next to the game folder: the newest when there are several.
     */
    static File findInstaller(Context context) {
        List<File> dirs = new ArrayList<>();
        File top = Environment.getExternalStorageDirectory();
        dirs.add(new File(top, Environment.DIRECTORY_DOWNLOADS));
        dirs.add(top);
        dirs.add(home());
        for (File d : context.getExternalFilesDirs(null)) {  // .../<volume>/Android/data/<app>/files
            if (d == null) continue;
            File volume = d.getParentFile().getParentFile().getParentFile().getParentFile();
            if (volume.equals(top)) continue;
            dirs.add(volume);
            dirs.add(new File(volume, Environment.DIRECTORY_DOWNLOADS));
        }
        File best = null;
        for (File d : dirs) {
            File[] found = d.listFiles((dir, name) -> name.startsWith(INSTALLER_PREFIX)
                    && name.toLowerCase(java.util.Locale.ROOT).endsWith(".exe"));
            if (found == null) continue;
            for (File f : found) {
                if (f.isFile() && (best == null || f.lastModified() > best.lastModified())) best = f;
            }
        }
        return best;
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
