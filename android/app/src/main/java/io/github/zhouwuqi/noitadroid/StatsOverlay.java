package io.github.zhouwuqi.noitadroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The frame rate and temperatures over the game, top centre, when the in-game menu turns it on: frames from the
 * game's buffer swaps (runtime/gles.c), temperatures from the thermal zones apps may read (the hottest CPU core, the
 * hottest GPU zone, the battery). It lets touches through.
 */
final class StatsOverlay {
    private final TextView view;
    private final SharedPreferences prefs;
    private final LongSupplier frames;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<File> cpu = new ArrayList<>(), gpu = new ArrayList<>();
    private File battery;
    private long lastFrames = -1, lastTime;
    private final Runnable tick = this::tick;

    StatsOverlay(Context c, FrameLayout parent, LongSupplier frames) {
        this.frames = frames;
        prefs = c.getSharedPreferences("overlay", Context.MODE_PRIVATE);
        view = Ui.text(c, "", 13, Ui.TEXT, true);
        view.setPadding(Ui.dp(c, 10), Ui.dp(c, 3), Ui.dp(c, 10), Ui.dp(c, 3));
        view.setBackground(Ui.rounded(c, 0x99000000, 0, 0));
        view.setFocusable(false);
        view.setClickable(false);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        p.topMargin = Ui.dp(c, 6);
        parent.addView(view, p);
        File[] zones = new File("/sys/class/thermal").listFiles((d, n) -> n.startsWith("thermal_zone"));
        if (zones != null) {
            for (File z : zones) {
                String type = read(new File(z, "type"));
                File temp = new File(z, "temp");
                if (type == null || read(temp) == null) continue;  // some zones can't be read (EINVAL, EACCES)
                if (type.startsWith("cpu-") || type.startsWith("cpuss-")) cpu.add(temp);
                else if (type.startsWith("gpuss-") || type.startsWith("gpu")) gpu.add(temp);
                else if (type.equals("battery")) battery = temp;
            }
        }
        show(isOn());
    }

    boolean isOn() {
        return prefs.getBoolean("on", false);
    }

    void setOn(boolean on) {
        prefs.edit().putBoolean("on", on).apply();
        show(on);
    }

    private void show(boolean on) {
        handler.removeCallbacks(tick);
        view.setVisibility(on ? View.VISIBLE : View.GONE);
        lastFrames = -1;
        if (on) {
            view.setText("…");
            tick();
        }
    }

    private void tick() {
        long now = SystemClock.elapsedRealtime(), f = frames.getAsLong();
        StringBuilder sb = new StringBuilder();
        if (lastFrames >= 0 && now > lastTime) {  // over the second since the last tick
            sb.append(Math.round((f - lastFrames) * 1000.0 / (now - lastTime))).append(" FPS");
        }
        lastFrames = f;
        lastTime = now;
        appendTemp(sb, "CPU", hottest(cpu));
        appendTemp(sb, "GPU", hottest(gpu));
        appendTemp(sb, "🔋", battery == null ? Integer.MIN_VALUE : milli(battery));
        if (sb.length() > 0) view.setText(sb);
        handler.postDelayed(tick, 1000);
    }

    private static void appendTemp(StringBuilder sb, String what, int milli) {
        if (milli == Integer.MIN_VALUE) return;
        if (sb.length() > 0) sb.append("   ");
        sb.append(what).append(' ').append(Math.round(milli / 1000.0)).append("°C");
    }

    private static int hottest(List<File> temps) {
        int max = Integer.MIN_VALUE;
        for (File t : temps) max = Math.max(max, milli(t));
        return max;
    }

    private static int milli(File temp) {
        String s = read(temp);
        try {
            return s == null ? Integer.MIN_VALUE : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static String read(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }
}
