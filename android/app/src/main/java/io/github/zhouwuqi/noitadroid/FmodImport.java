package io.github.zhouwuqi.noitadroid;

import android.content.Context;
import android.os.Environment;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import dalvik.system.DexClassLoader;

/**
 * FMOD, the game's sound engine, from the user's own download: "FMOD Engine" 2.01.x for Android from fmod.com
 * (fmodstudioapi20123android.tar.gz). Its license doesn't allow the app to include it.
 *
 * The import takes FMOD's two arm64 libraries and its Java side (fmod.jar) out of the archive into files/fmod, and
 * turns fmod.jar into dex with D8 (from the toolchain). libfmod.so's JNI_OnLoad looks for those classes through the
 * class loader of whoever loads it: AppFactory puts the dex in the app's own class loader, from the next start on.
 */
final class FmodImport {
    static final String DEX = "fmod-dex.jar";
    private static final Pattern VERSION = Pattern.compile("#define\\s+FMOD_VERSION\\s+0x([0-9a-fA-F]+)");
    /** What the import takes from the archive (the path after its top folder) and its name in files/fmod. */
    private static final Map<String, String> WANTED = new HashMap<>();

    static {
        WANTED.put("api/core/lib/arm64-v8a/libfmod.so", "libfmod.so");
        WANTED.put("api/studio/lib/arm64-v8a/libfmodstudio.so", "libfmodstudio.so");
        WANTED.put("api/core/lib/fmod.jar", "fmod.jar");
        WANTED.put("api/core/inc/fmod_common.h", "fmod_common.h");
        WANTED.put("doc/LICENSE.TXT", "LICENSE.TXT");
    }

    private FmodImport() {
    }

    static File dir(Context c) {
        return new File(c.getFilesDir(), "fmod");
    }

    /** The imported FMOD's version ("2.01.23"), or null when there's none (or not all of it). */
    static String version(Context c) {
        File d = dir(c);
        for (String name : new String[] {"libfmod.so", "libfmodstudio.so", DEX, "version"}) {
            if (!new File(d, name).isFile()) return null;
        }
        try {
            return NativeBuild.read(new File(d, "version"));
        } catch (IOException e) {
            return null;
        }
    }

    /** True for a name FMOD's Android download has: fmodstudioapi<version>android.tar.gz. */
    static boolean isArchive(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("fmodstudioapi") && n.contains("android") && n.endsWith(".tar.gz");
    }

    /** FMOD's Android download in Download or at the top of shared storage: the newest 2.01 one, else any. */
    static File findArchive() {
        File top = Environment.getExternalStorageDirectory();
        File best = null;
        for (File d : new File[] {new File(top, Environment.DIRECTORY_DOWNLOADS), top}) {
            File[] found = d.listFiles((dir, name) -> isArchive(name));
            if (found == null) continue;
            for (File f : found) {
                boolean v201 = f.getName().startsWith("fmodstudioapi201");
                boolean bestV201 = best != null && best.getName().startsWith("fmodstudioapi201");
                if (best == null || (v201 && !bestV201)
                        || (v201 == bestV201 && f.lastModified() > best.lastModified())) {
                    best = f;
                }
            }
        }
        return best;
    }

    /** Imports FMOD from the archive's contents; its version. Replaces an earlier import only when it succeeds. */
    static String importFrom(Context c, InputStream archive) throws Exception {
        File tmp = new File(c.getFilesDir(), "fmod.tmp");
        NativeBuild.delete(tmp);
        if (!tmp.mkdirs()) throw new IOException("can't create " + tmp);
        int found = untar(new GZIPInputStream(archive, 1 << 16), tmp);
        for (String name : WANTED.values()) {
            if (!name.equals("LICENSE.TXT") && !new File(tmp, name).isFile()) {
                throw new IOException(c.getString(R.string.fmod_not_archive));
            }
        }
        Matcher m = VERSION.matcher(NativeBuild.read(new File(tmp, "fmod_common.h")));
        if (!m.find()) throw new IOException(c.getString(R.string.fmod_not_archive));
        int v = Integer.parseInt(m.group(1), 16);
        String version = String.format(Locale.ROOT, "%x.%02x.%02x", v >> 16, v >> 8 & 0xff, v & 0xff);
        if (v >> 8 != 0x201) throw new IOException(c.getString(R.string.fmod_wrong_version, version));

        Properties p = NativeBuild.properties(c);
        File toolchain = new File(c.getFilesDir(), "toolchain");
        NativeBuild.extract(c, "toolchain.zip", toolchain, p.getProperty("toolchain.version", "?"));
        File dex = new File(tmp, DEX);
        d8(c, new File(toolchain, "d8.jar"), new File(tmp, "fmod.jar"), dex);
        if (!dex.isFile()) throw new IOException("D8 wrote nothing");
        dex.setReadOnly();  // Android 14 loads no code from a writable file
        NativeBuild.write(new File(tmp, "version"), version);
        File d = dir(c);
        NativeBuild.delete(d);
        if (!tmp.renameTo(d)) throw new IOException("can't rename " + tmp);
        return version;
    }

    /** The WANTED files of a tar stream into dir; how many. A plain ustar reader: FMOD's archive needs no more. */
    private static int untar(InputStream gz, File dir) throws IOException {
        DataInputStream in = new DataInputStream(gz);
        byte[] header = new byte[512];
        byte[] buf = new byte[1 << 16];
        int found = 0;
        String longName = null;
        while (true) {
            try {
                in.readFully(header);
            } catch (EOFException e) {
                break;
            }
            if (header[0] == 0) break;  // the end: zero blocks
            String name = longName != null ? longName : field(header, 0, 100);
            longName = null;
            String prefix = field(header, 345, 155);
            if (!prefix.isEmpty() && header[257] == 'u') name = prefix + "/" + name;
            String sizeField = field(header, 124, 12).trim();
            long size = sizeField.isEmpty() ? 0 : Long.parseLong(sizeField, 8);
            if (name.startsWith("./")) name = name.substring(2);
            char type = (char) header[156];
            long padded = (size + 511) / 512 * 512;
            if (type == 'L') {  // a GNU long name: the next entry's
                byte[] n = new byte[(int) padded];
                in.readFully(n);
                longName = new String(n, 0, (int) size, StandardCharsets.UTF_8).replace("\0", "");
                continue;
            }
            String inArchive = name.contains("/") ? name.substring(name.indexOf('/') + 1) : name;
            String out = (type == '0' || type == 0) ? WANTED.get(inArchive) : null;
            long left = padded;
            if (out != null) {
                try (OutputStream o = new FileOutputStream(new File(dir, out))) {
                    long n = size;
                    while (n > 0) {
                        int r = in.read(buf, 0, (int) Math.min(buf.length, n));
                        if (r < 0) throw new EOFException();
                        o.write(buf, 0, r);
                        n -= r;
                    }
                }
                found++;
                left -= size;
            }
            while (left > 0) {
                long s = in.skip(left);
                if (s <= 0) {
                    if (in.read() < 0) throw new EOFException();
                    s = 1;
                }
                left -= s;
            }
        }
        return found;
    }

    private static String field(byte[] h, int at, int len) {
        int end = at;
        while (end < at + len && h[end] != 0) end++;
        return new String(h, at, end - at, StandardCharsets.UTF_8);
    }

    /** fmod.jar into dex (out: a jar with classes.dex), with D8 loaded from the toolchain. */
    private static void d8(Context c, File d8, File jar, File out) throws Exception {
        d8.setReadOnly();
        ClassLoader l = new DexClassLoader(d8.getPath(), c.getCodeCacheDir().getPath(), null,
                c.getClassLoader().getParent());
        Class<?> command = l.loadClass("com.android.tools.r8.D8Command");
        Class<?> mode = l.loadClass("com.android.tools.r8.OutputMode");
        Object b = command.getMethod("builder").invoke(null);
        Collection<Path> program = Collections.singletonList(jar.toPath());
        b.getClass().getMethod("addProgramFiles", Collection.class).invoke(b, program);
        b.getClass().getMethod("setMinApiLevel", int.class).invoke(b, 29);
        b.getClass().getMethod("setOutput", Path.class, mode).invoke(b, out.toPath(), mode.getField("DexIndexed").get(null));
        Object cmd = b.getClass().getMethod("build").invoke(b);
        l.loadClass("com.android.tools.r8.D8").getMethod("run", command).invoke(null, cmd);
    }
}
