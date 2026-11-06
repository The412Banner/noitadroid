package io.github.zhouwuqi.noitadroid;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;

/**
 * Frame generation (WinFG 2x, Perf.frameGen): a surface over the game's own, where libnd_fg.so (android/fg/ndfg.cpp)
 * shows the game's frames with a generated one between each two. It sits above SDL's surface (a media overlay) but
 * below the window, so the touch controls and the menu stay on top; it comes first in the layout, so touches still go
 * to SDL's view. Until it shows a frame it shows nothing, and the game's own surface is seen as usual.
 */
final class FrameGen implements SurfaceHolder.Callback {
    private static final String TAG = "noitadroid";

    static native void nativeStart();

    static native void nativeSurface(Surface surface);

    static native void nativeSurfaceGone();

    static native boolean nativeActive();

    private FrameGen() {}

    /** In onCreate, after SDL made its layout; false when the library can't load (the game runs without it). */
    static boolean attach(Activity activity, ViewGroup layout) {
        try {
            System.loadLibrary("nd_fg");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "no frame generation: " + e);
            return false;
        }
        SurfaceView view = new SurfaceView(activity);
        view.setZOrderMediaOverlay(true);
        view.getHolder().setFormat(PixelFormat.OPAQUE);
        view.getHolder().addCallback(new FrameGen());
        layout.addView(view, 0, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        nativeStart();
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
