package io.github.zhouwuqi.noitadroid;

import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Saves in and out as zip files laid out like the game's folder on Windows (%USERPROFILE%\AppData\LocalLow\
 * Nolla_Games_Noita): save00 (the run, the world, the progress in persistent/ and stats/) and save_shared (the
 * settings). An import takes save00 from a zip of either platform; it's staged next to the saves and only swapped
 * in when the game isn't running (applyPending, before the next start), with the old save00 kept as a backup.
 */
final class SaveTransfer {
    private static final String TAG = "noitadroid";
    static final File SAVES = new File(Environment.getExternalStorageDirectory(),
            "noitadroid/save/AppData/LocalLow/Nolla_Games_Noita");
    private static final File STAGED = new File(SAVES, ".import"), PENDING = new File(SAVES, ".import-pending");

    private SaveTransfer() {}

    static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
    }

    /** Writes save00 and save_shared as a zip; returns the files' total size. */
    static long export(OutputStream out) throws IOException {
        long[] total = {0};
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String dir : new String[] {"save00", "save_shared"}) add(zip, new File(SAVES, dir), dir, total);
        }
        return total[0];
    }

    private static void add(ZipOutputStream zip, File f, String name, long[] total) throws IOException {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children == null) return;
            zip.putNextEntry(new ZipEntry(name + "/"));
            zip.closeEntry();
            for (File c : children) add(zip, c, name + "/" + c.getName(), total);
        } else if (f.isFile()) {
            ZipEntry e = new ZipEntry(name);
            e.setTime(f.lastModified());
            zip.putNextEntry(e);
            try (InputStream in = new FileInputStream(f)) {
                total[0] += copy(in, zip);
            }
            zip.closeEntry();
        }
    }

    /** What an import would bring in. */
    static final class Staged {
        boolean run, progress, stats;
        long bytes;
        int files;
    }

    /**
     * Unpacks a zip's save00 into the staging folder: entries under any ".../save00/" (a zip of the folder from
     * either platform), or at the top when the zip holds save00's contents themselves (world/, persistent/, ...).
     * Throws when there's no save in it.
     */
    static Staged stage(InputStream in) throws IOException {
        deleteTree(STAGED);
        File root = new File(STAGED, "save00");
        if (!root.mkdirs()) throw new IOException("can't create " + root);
        Staged s = new Staged();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName().replace('\\', '/');
                int at = name.startsWith("save00/") ? 0 : name.indexOf("/save00/") + 1;
                String rel;
                if (at > 0 || name.startsWith("save00/")) rel = name.substring(at + "save00/".length());
                else if (name.matches("(world|persistent|stats)/.*|player\\.xml|world_state\\.xml|"
                        + "session_numbers\\.salakieli|mod_config\\.xml|mod_settings\\.bin")) rel = name;
                else continue;  // save_shared/ (this device's settings stay), and anything else
                if (rel.isEmpty()) continue;
                if (rel.startsWith("/") || ("/" + rel + "/").contains("/../")) throw new IOException("bad path: " + name);
                File out = new File(root, rel);
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    s.bytes += copy(zip, o);
                }
                s.files++;
                if (rel.equals("player.xml")) s.run = true;
                if (rel.startsWith("persistent/")) s.progress = true;
                if (rel.startsWith("stats/")) s.stats = true;
            }
        }
        if (!s.run && !s.progress && !s.stats) {
            deleteTree(STAGED);
            throw new IOException("no Noita save (save00) in it");
        }
        return s;
    }

    /** The staged import goes in at the next start (the game must not be running: it writes save00 itself). */
    static void requestApply() throws IOException {
        if (!PENDING.createNewFile() && !PENDING.exists()) throw new IOException("can't create " + PENDING);
    }

    static void discardStaged() {
        deleteTree(STAGED);
    }

    /** Before the game starts: swaps a requested import in, keeping save00 as save00.backup-<time>. */
    static void applyPending() {
        if (!PENDING.exists()) return;
        File staged = new File(STAGED, "save00"), save = new File(SAVES, "save00");
        if (staged.isDirectory()) {
            File backup = new File(SAVES, "save00.backup-" + stamp());
            if (save.exists() && !save.renameTo(backup)) {
                Log.e(TAG, "import: can't move " + save + " to " + backup + "; the import waits");
                return;
            }
            if (staged.renameTo(save)) {
                Log.i(TAG, "import: save00 replaced; the old one is " + backup.getName());
            } else {
                Log.e(TAG, "import: can't move " + staged + " to " + save + "; the old save stays");
                backup.renameTo(save);
            }
        }
        PENDING.delete();
        deleteTree(STAGED);
    }

    private static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        long n = 0;
        for (int r; (r = in.read(buf)) > 0; n += r) out.write(buf, 0, r);
        return n;
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }
}
