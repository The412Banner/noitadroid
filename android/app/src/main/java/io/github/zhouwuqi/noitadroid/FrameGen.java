package io.github.zhouwuqi.noitadroid;

import android.app.Activity;
import android.content.Context;
import android.graphics.PixelFormat;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Frame generation (Perf.frameGen: WinFG 2x or LSFG 2x): a surface over the game's own, where libnd_fg.so
 * (android/fg/ndfg.cpp) shows the game's frames with a generated one between each two. LSFG's shaders come from the
 * user's own Lossless.dll (Lossless Scaling), translated once into files/lsfg/lsfg.cache (importLsfg).
 * The surface sits above SDL's (a media overlay) but below the window, so the touch controls and the menu stay on
 * top; it comes first in the layout, so touches still go to SDL's view. Until it shows a frame it shows nothing, and the game's own surface is seen as usual.
 */
final class FrameGen implements SurfaceHolder.Callback {
    private static final String TAG = "noitadroid";

    static native void nativeStart(String mode, String lsfgCache, float refreshHz, String tuning);

    /** Every frame shown so far, generated ones too (StatsOverlay). */
    static native long nativePresents();

    static native String nativeBuildLsfgCache(String dll, String lsfgCache);

    static native boolean nativeLsfgCacheOk(String lsfgCache);

    static native void nativeSurface(Surface surface);

    static native void nativeSurfaceGone();

    static native boolean nativeActive();

    private FrameGen() {}

    private static Boolean loaded;

    static synchronized boolean load() {
        if (loaded == null) {
            try {
                System.loadLibrary("nd_fg");
                loaded = true;
            } catch (UnsatisfiedLinkError e) {
                Log.w(TAG, "no frame generation: " + e);
                loaded = false;
            }
        }
        return loaded;
    }

    static File lsfgDir(Context c) {
        return new File(c.getFilesDir(), "lsfg");
    }

    static File lsfgCache(Context c) {
        return new File(lsfgDir(c), "lsfg.cache");
    }

    /** LSFG's shaders are there (a Lossless.dll was imported). */
    static boolean lsfgReady(Context c) {
        return lsfgCache(c).isFile() && load() && nativeLsfgCacheOk(lsfgCache(c).getPath());
    }

    /**
     * The user's Lossless.dll into the app (files/lsfg), its shaders translated into the cache: null when it worked,
     * else why not. Slow (seconds): not on the UI thread.
     */
    static String importLsfg(Context c, InputStream dll) throws IOException {
        if (!load()) return "libnd_fg.so";
        File dir = lsfgDir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't make " + dir);
        File copy = new File(dir, "Lossless.dll"), tmp = new File(dir, "Lossless.dll.tmp");
        try (OutputStream out = Files.newOutputStream(tmp.toPath())) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = dll.read(buf)) > 0; ) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(copy)) throw new IOException("can't write " + copy);
        String status = nativeBuildLsfgCache(copy.getPath(), lsfgCache(c).getPath());
        return "Ok".equals(status) ? null : status;
    }

    /** In onCreate, after SDL made its layout; false when the library can't load (the game runs without it). */
    static boolean attach(Activity activity, ViewGroup layout, String mode) {
        if (!load()) return false;
        SurfaceView view = new SurfaceView(activity);
        view.setZOrderMediaOverlay(true);
        view.getHolder().setFormat(PixelFormat.OPAQUE);
        view.getHolder().addCallback(new FrameGen());
        layout.addView(view, 0, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        float refresh = activity.getDisplay() != null ? activity.getDisplay().getRefreshRate() : 60f;
        String tuning = "";
        File conf = new File(GameCheck.home(), "fg.conf");  // optional WinFG settings, for trying them on the device
        try {
            if (conf.isFile()) tuning = new String(Files.readAllBytes(conf.toPath()), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
        nativeStart(mode, lsfgCache(activity).getPath(), refresh, tuning);
        return true;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {}

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        nativeSurface(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        nativeSurfaceGone();  // returns once the generation thread let go of it
    }
}
