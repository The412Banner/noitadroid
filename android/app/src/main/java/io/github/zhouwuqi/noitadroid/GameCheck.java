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
 * all-files access to shared storage, the game files in /sdcard/noitadroid/Noita, a build of the game this APK
 * takes (GOG's 20250125-1640 or the Steam build of the same day: tools/game_build.py), and (when the app builds it on
 * the device: NativeBuild) the game's native code. The APK's build script writes the game builds' facts into it
 * (assets/noitadroid.properties: the sha256s of noita.exe with the builds' names, msvcp120.dll's, data.wak's size,
 * FMOD's version).
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
    /** noita.exe's sha256, and which of the builds it is ("gog", "steam"), once version is OK. */
    String exeHash, game;
    final String fmodVersion;
    /** GOG's offline installer: when the game files are missing, to install from; when they're there, to delete. */
    File installer;
    /**
     * Other complete game folders of a build this app takes, where copies usually are (findCopies), and their
     * builds ("gog", "steam"): when the game folder has no game, or another build, the user picks one.
     */
    final List<File> copies = new ArrayList<>();
    final List<String> copyGames = new ArrayList<>();
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
                int match = want == null ? 0 : java.util.Arrays.asList(want.toLowerCase(java.util.Locale.ROOT).split(","))
                        .indexOf(have);
                if (match < 0) {
                    wrongFile = name;
                    wrongHash = have;
                    break;
                }
                if (name.equals("noita.exe")) {
                    exeHash = have;
                    String[] games = build.getProperty("noita.exe.games", "gog").split(",");
                    game = match < games.length ? games[match] : "gog";
                }
            }
            version = wrongFile == null ? State.OK : State.WRONG;
        } else {
            version = State.WAITING;
        }
        if (buildsOnDevice && (files == State.MISSING || version == State.WRONG)) findCopies(context, build);
        if (!buildsOnDevice) {
            nativeCode = State.OK;  // in the APK
        } else if (files != State.OK || version != State.OK) {
            nativeCode = State.WAITING;
        } else {
            need = NativeBuild.need(context, build, exeHash);
            nativeCode = need == NativeBuild.Need.NONE ? State.OK : State.MISSING;
        }
    }

    /**
     * This app can import FMOD but hasn't yet, so the game would be silent. The setup screen stays up for it, once:
     * until the user starts the game anyway (setSilent).
     */
    static boolean canAddSound(Context c) {
        return NativeBuild.onDevice(NativeBuild.properties(c)) && FmodImport.version(c) == null
                && !new File(c.getApplicationInfo().nativeLibraryDir, "libfmodstudio.so").exists();
    }

    /** Whether the user has chosen to play without sound. Written at once: the caller may be about to end the process. */
    static void setSilent(Context c, boolean silent) {
        c.getSharedPreferences("game", Context.MODE_PRIVATE).edit().putBoolean("silent", silent).commit();
    }

    /** The launcher can go straight into the game: it can start, and there's nothing to ask about sound. */
    boolean startsAtOnce(Context c) {
        return ready() && (!canAddSound(c) || c.getSharedPreferences("game", Context.MODE_PRIVATE).getBoolean("silent", false));
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

    /**
     * Complete game folders (noita.exe, msvcp120.dll and data/data.wak) of a build this app takes, other than the game
     * folder, one level down where copies usually are: Download, the top of the storage, Winlator's games folder,
     * Games, on the internal storage and on memory cards.
     */
    private void findCopies(Context context, Properties build) {
        List<File> volumes = new ArrayList<>();
        volumes.add(Environment.getExternalStorageDirectory());
        for (File d : context.getExternalFilesDirs(null)) {  // .../<volume>/Android/data/<app>/files
            if (d == null) continue;
            File volume = d.getParentFile().getParentFile().getParentFile().getParentFile();
            if (!volumes.contains(volume)) volumes.add(volume);
        }
        List<String> hashes = java.util.Arrays.asList(build.getProperty("noita.exe.sha256", "").toLowerCase(java.util.Locale.ROOT)
                .split(","));
        String[] games = build.getProperty("noita.exe.games", "gog").split(",");
        SharedPreferences cache = context.getSharedPreferences("hashes", Context.MODE_PRIVATE);
        for (File volume : volumes) {
            for (File parent : new File[] {new File(volume, Environment.DIRECTORY_DOWNLOADS), volume,
                    new File(volume, "Winlator/games"), new File(volume, "Games"), new File(volume, "noitadroid")}) {
                File[] dirs = parent.listFiles(File::isDirectory);
                if (dirs == null) continue;
                for (File d : dirs) {
                    if (d.equals(gameDir) || copies.contains(d)) continue;
                    boolean complete = true;
                    for (String name : REQUIRED) complete &= new File(d, name).isFile();
                    if (!complete) continue;
                    int match = hashes.indexOf(sha256(new File(d, "noita.exe"), cache));
                    if (match < 0) continue;
                    copies.add(d);
                    copyGames.add(match < games.length ? games[match] : "gog");
                }
            }
        }
    }

    /** The sha256 of noita.exe in gameDir (from the cache when the file hasn't changed). */
    static String exeHash(Context context, File gameDir) {
        return sha256(new File(gameDir, "noita.exe"), context.getSharedPreferences("hashes", Context.MODE_PRIVATE));
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
