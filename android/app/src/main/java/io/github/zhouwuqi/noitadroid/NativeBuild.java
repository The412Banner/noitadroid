package io.github.zhouwuqi.noitadroid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The game's native code when the app builds it on the device (an APK from tools/android/build_app.py, whose
 * noitadroid.properties says native=device): where it goes, whether the one there fits, and the build's progress,
 * which BuildService reports here and SetupActivity shows.
 *
 * In the app's own storage:
 *   files/game/libmain.so, libmain.json   the game's code and what it was built from (tools/android/device_build.py)
 *   files/toolchain/                       assets/toolchain.zip: clang's headers, the sysroot, Python's library
 *   files/build/                           assets/kit.zip, and the build's work, kept until it succeeds
 * The toolchain's programs are in the native library directory, the only place Android lets an app run them from.
 */
final class NativeBuild {
    /** Why the game's code has to be built: nothing there, built for another app version, or for another game. */
    enum Need { NONE, MISSING, OLD, OTHER_GAME }

    private NativeBuild() {
    }

    static Properties properties(Context c) {
        Properties p = new Properties();
        try (InputStream in = c.getAssets().open("noitadroid.properties")) {
            p.load(in);
        } catch (IOException e) {  // a build without them: nothing to check against
        }
        return p;
    }

    /** True for an APK that builds the game's code on the device, rather than having it inside. */
    static boolean onDevice(Properties p) {
        return "device".equals(p.getProperty("native"));
    }

    static File gameLib(Context c) {
        return new File(c.getFilesDir(), "game/libmain.so");
    }

    static File gameInfo(Context c) {
        return new File(c.getFilesDir(), "game/libmain.json");
    }

    static Need need(Context c, Properties p) {
        File lib = gameLib(c), info = gameInfo(c);
        if (!lib.isFile() || !info.isFile()) return Need.MISSING;
        try {
            JSONObject j = new JSONObject(new String(Files.readAllBytes(info.toPath()), StandardCharsets.UTF_8));
            if (j.optInt("abi", 0) != Integer.parseInt(p.getProperty("native.abi", "0"))) return Need.OLD;
            if (!j.optString("noita.exe").equalsIgnoreCase(p.getProperty("noita.exe.sha256", ""))) {
                return Need.OTHER_GAME;
            }
        } catch (IOException | JSONException | NumberFormatException e) {
            return Need.MISSING;
        }
        return Need.NONE;
    }

    /**
     * assets/<zip> in dir, unless the version there is this one already. A different version (or a half-done
     * extraction) is deleted first.
     */
    static void extract(Context c, String zip, File dir, String version) throws IOException {
        File stamp = new File(dir, ".version");
        if (stamp.isFile() && version.equals(read(stamp))) return;
        delete(dir);
        if (!dir.mkdirs()) throw new IOException("can't create " + dir);
        String root = dir.getCanonicalPath() + File.separator;
        byte[] buf = new byte[1 << 16];
        try (ZipInputStream in = new ZipInputStream(c.getAssets().open(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                File out = new File(dir, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) throw new IOException("bad entry " + e.getName());
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                }
            }
        }
        write(stamp, version);
    }

    static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
    }

    static void write(File f, String s) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    static void delete(File f) {
        if (!f.exists()) return;
        File[] kids = f.isDirectory() && !Files.isSymbolicLink(f.toPath()) ? f.listFiles() : null;
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }

    // --- the build's progress, for the UI (main thread)

    /** What BuildService is doing, or did last. Read on the main thread. */
    static final class Status {
        boolean running, done, failed, unpacking;
        String stage = "";
        int permille;  // the whole job: unpacking (when there is) and building
        long startedAt;
        String error;
    }

    interface Listener {
        void onStatus(Status s);
    }

    static final Status STATUS = new Status();
    private static final List<Listener> listeners = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    static void listen(Listener l) {
        listeners.add(l);
        l.onStatus(STATUS);
    }

    static void unlisten(Listener l) {
        listeners.remove(l);
    }

    /** Changes the status on the main thread, then tells the listeners. */
    static void post(java.util.function.Consumer<Status> change) {
        main.post(() -> {
            change.accept(STATUS);
            for (Listener l : new ArrayList<>(listeners)) l.onStatus(STATUS);
        });
    }
}
