package io.github.zhouwuqi.noitadroid;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The performance settings, on the in-game menu's Performance page. For now: which cores the game's threads run on
 * (runtime/sched.c's NOITADROID_CORES). They reach the game as "--env NAME=VALUE" when it starts
 * (NoitaActivity.getArguments), and change while it runs too (NoitaActivity.nativeSetCores).
 */
final class Perf {
    /**
     * Android places the threads; the main thread gets the fastest core and the simulation threads the others; that,
     * and one more simulation thread uses the fastest core while the main thread waits. Measured on the Pocket S
     * (docs/ANDROID.zh.md): split 8-16% faster than system, boost 8-13% faster than split.
     */
    static final String SYSTEM = "system", SPLIT = "split", BOOST = "boost";
    static final String DEFAULT_CORES = BOOST;

    private Perf() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("perf", Context.MODE_PRIVATE);
    }

    static String cores(Context c) {
        List<String> modes = modes();
        String m = prefs(c).getString("cores", DEFAULT_CORES);
        return modes.contains(m) ? m : modes.contains(DEFAULT_CORES) ? DEFAULT_CORES : SYSTEM;
    }

    static void setCores(Context c, String mode) {
        prefs(c).edit().putString("cores", mode).apply();
    }

    /** What the game reads at its start. */
    static List<String> env(Context c) {
        return Collections.singletonList("NOITADROID_CORES=" + cores(c));
    }

    /**
     * The kinds of cores, fastest first, and how many of each: from each CPU's capacity, else its top frequency, as
     * runtime/sched.c tells them apart. One kind when they can't be told apart.
     */
    static List<Integer> coreKinds() {
        Map<Long, Integer> kinds = new TreeMap<>(Collections.reverseOrder());
        int n = 0;
        for (; ; n++) {
            File cpu = new File("/sys/devices/system/cpu/cpu" + n);
            if (!cpu.isDirectory()) break;
            long cap = readLong(new File(cpu, "cpu_capacity"));
            if (cap <= 0) cap = readLong(new File(cpu, "cpufreq/cpuinfo_max_freq"));
            if (cap <= 0) return Collections.singletonList(Math.max(1, Runtime.getRuntime().availableProcessors()));
            kinds.merge(cap, 1, Integer::sum);
        }
        return kinds.isEmpty() ? Collections.singletonList(Runtime.getRuntime().availableProcessors())
                : new ArrayList<>(kinds.values());
    }

    /** The modes for this device: split and boost need a fastest kind of core, and more than two kinds. */
    static List<String> modes() {
        List<String> m = new ArrayList<>(Collections.singletonList(SYSTEM));
        if (coreKinds().size() >= 3) {
            m.add(SPLIT);
            m.add(BOOST);
        }
        return m;
    }

    private static long readLong(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line = r.readLine();
            return line == null ? -1 : Long.parseLong(line.trim());
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }
}
