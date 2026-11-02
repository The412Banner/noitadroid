package io.github.zhouwuqi.noitadroid;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.StatFs;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the game's native code on the device (NativeBuild), as a foreground service with a wake lock, so it goes on
 * while the screen is off or another app is in front. With an installer, it first unpacks GOG's offline installer into
 * the game folder (innoextract).
 *
 * The build is tools/android/device_build.py, run by the app's Python with the app's toolchain. It keeps what it
 * finished: a build cancelled or killed goes on where it stopped the next time. Its output goes to
 * /sdcard/noitadroid/build.log.
 */
public class BuildService extends Service {
    private static final String TAG = "noitadroid";
    static final String EXTRA_INSTALLER = "installer";
    private static final String CHANNEL = "build";
    private static final int NOTIFICATION = 1;
    private static final int UNPACK_SHARE = 180;  // of 1000: unpacking takes about 3 of 15 minutes
    private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)%");

    private static volatile boolean running, cancelled;
    private static volatile Process process;
    private long lastNotified;

    static void start(Context c, File installer) {
        Intent i = new Intent(c, BuildService.class);
        if (installer != null) i.putExtra(EXTRA_INSTALLER, installer.getPath());
        c.startForegroundService(i);
    }

    static boolean running() {
        return running;
    }

    /** Stops the build (and whatever it started); what it finished is kept. */
    static void cancel() {
        cancelled = true;
        Process p = process;
        if (p != null) p.destroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Context t = Lang.wrap(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, t.getString(R.string.build_channel),
                NotificationManager.IMPORTANCE_LOW));
        Notification n = notification(t, t.getString(R.string.build_stage_prepare), 0, true);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION, n);
        }
        if (running) return START_NOT_STICKY;
        running = true;
        cancelled = false;
        String installer = intent == null ? null : intent.getStringExtra(EXTRA_INSTALLER);
        new Thread(() -> work(installer), "build").start();
        return START_NOT_STICKY;
    }

    private void work(String installer) {
        long started = System.currentTimeMillis();
        NativeBuild.post(s -> {
            s.running = true;
            s.done = s.failed = false;
            s.error = null;
            s.startedAt = started;
            s.permille = 0;
            s.stage = installer != null ? "unpack" : "prepare";
            s.unpacking = installer != null;
        });
        PowerManager.WakeLock wake = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noitadroid:build");
        wake.acquire(3 * 60 * 60 * 1000L);
        String error = null;
        try (PrintStream log = openLog()) {
            log.println("noitadroid build, " + DateFormat.getDateTimeInstance().format(new Date()) + ", "
                    + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE);
            try {
                File game = GameCheck.gameDir(this);
                int share = 0;
                if (installer != null) {
                    game = unpack(new File(installer), log);
                    share = UNPACK_SHARE;
                }
                build(game, log, share);
            } catch (Exception e) {
                error = cancelled ? null : (e.getMessage() != null ? e.getMessage() : e.toString());
                log.println(cancelled ? "cancelled" : "failed: " + e);
                Log.e(TAG, "build", e);
            }
        } catch (IOException e) {
            error = e.toString();
        }
        wake.release();
        String failure = error;
        boolean stopped = cancelled;
        long took = System.currentTimeMillis() - started;
        NativeBuild.post(s -> {
            s.running = false;
            s.done = failure == null && !stopped;
            s.failed = failure != null;
            s.error = failure;
            if (s.done) s.permille = 1000;
        });
        running = false;
        process = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (!stopped) {  // what happened, when the app isn't in front
            Context t = Lang.wrap(this);
            String text = failure == null
                    ? t.getString(R.string.build_done, took / 60000, took / 1000 % 60)
                    : t.getString(R.string.build_failed, failure);
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(t, text, 0, false));
        }
        stopSelf();
    }

    // --- the steps

    /**
     * The installer into <home>/Noita, or, when the internal storage hasn't the room and the installer is on a memory
     * card, into noitadroid/Noita on that card. That becomes the game folder; it returns it.
     */
    private File unpack(File installer, PrintStream log) throws IOException, InterruptedException {
        if (!installer.isFile()) throw new IOException(installer + " isn't there");
        long need = 2L << 30;
        File home = GameCheck.home(), game = new File(home, "Noita");
        home.mkdirs();
        File card = volumeOf(installer);
        if (new StatFs(home.getPath()).getAvailableBytes() < need && card != null
                && new StatFs(card.getPath()).getAvailableBytes() >= need) {
            game = new File(card, "noitadroid/Noita");
        }
        File parent = game.getParentFile();
        parent.mkdirs();
        needSpace(parent, need);
        String lib = getApplicationInfo().nativeLibraryDir;
        List<String> cmd = Arrays.asList(lib + "/libnd_innoextract.so", "--gog", "--progress=1", "--color=0", "-q",
                "-d", game.getPath(), installer.getPath());
        int status = run(cmd, parent, null, log, line -> {
            Matcher m = PERCENT.matcher(line);
            if (m.find()) {
                int permille = (int) (Float.parseFloat(m.group(1)) * UNPACK_SHARE / 100);
                progress("unpack", Math.min(permille, UNPACK_SHARE));
            }
        });
        if (status != 0) throw new IOException(getString(R.string.build_unpack_failed, status));
        for (String name : GameCheck.REQUIRED) {
            if (!new File(game, name).isFile()) throw new IOException(getString(R.string.build_unpack_missing, name));
        }
        GameCheck.setGameDir(this, game.equals(new File(home, "Noita")) ? null : game);
        return game;
    }

    /** The memory card a file is on (/storage/XXXX-XXXX), or null for the internal storage. */
    static File volumeOf(File f) {
        String path = f.getAbsolutePath();
        if (path.startsWith(android.os.Environment.getExternalStorageDirectory().getPath() + "/")) return null;
        String[] parts = path.split("/");
        return parts.length > 3 && parts[1].equals("storage") ? new File("/storage/" + parts[2]) : null;
    }

    private void build(File game, PrintStream log, int share) throws IOException, InterruptedException {
        progress("prepare", share);
        Properties p = NativeBuild.properties(this);
        File files = getFilesDir();
        needSpace(files, 1500L << 20);
        File toolchain = new File(files, "toolchain"), work = new File(files, "build");
        NativeBuild.extract(this, "toolchain.zip", toolchain, p.getProperty("toolchain.version", "?"));
        // The work belongs to this kit and this game: another of either starts it over.
        NativeBuild.extract(this, "kit.zip", work, p.getProperty("kit.version", "?") + "/"
                + p.getProperty("noita.exe.sha256", "?"));

        String lib = getApplicationInfo().nativeLibraryDir;
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(mem);
        long freeMb = mem.availMem >> 20;
        int cores = Runtime.getRuntime().availableProcessors();
        // A compile takes up to 400 MB; a lift worker about 600 MB besides the 3 GB they share.
        int jobs = (int) Math.max(1, Math.min(cores, (freeMb - 1000) / 450));
        int liftJobs = (int) Math.max(1, Math.min(6, (freeMb - 3000) / 600));
        log.println("free memory " + freeMb + " MB, " + cores + " cores: " + jobs + " compiles, " + liftJobs
                + " lift workers");
        File out = NativeBuild.gameLib(this);
        List<String> cmd = Arrays.asList(lib + "/libnd_python.so", "-u", work + "/tools/android/device_build.py",
                "--game", game.getPath(), "--out", out.getPath(), "--jobs", String.valueOf(jobs),
                "--lift-jobs", String.valueOf(liftJobs));
        String[] error = {null};
        int status = run(cmd, work, env -> {
            env.put("NOITADROID_TOOLCHAIN", lib);
            env.put("NOITADROID_TOOLCHAIN_DATA", toolchain.getPath());
            env.put("PYTHONHOME", toolchain + "/python");
            env.put("PYTHONPATH", toolchain + "/python/site-packages");
            env.put("LIBCAPSTONE_PATH", lib);
            env.put("HOME", files.getPath());
            env.put("TMPDIR", getCacheDir().getPath());
        }, log, line -> {
            String[] f = line.split(" ", 4);
            if (f[0].equals("ND-PROGRESS") && f.length >= 3) {
                int permille = Integer.parseInt(f[1]);
                progress(f[2], share + permille * (1000 - share) / 1000);
            } else if (f[0].equals("ND-ERROR")) {
                error[0] = line.substring("ND-ERROR ".length());
            }
        });
        if (cancelled) throw new IOException("cancelled");
        if (status != 0) {
            throw new IOException(error[0] != null ? error[0] : getString(R.string.build_exit, status));
        }
        if (NativeBuild.need(this, p) != NativeBuild.Need.NONE) throw new IOException(getString(R.string.build_bad));
        NativeBuild.delete(work);  // about 1 GB of generated C and objects
    }

    private void needSpace(File dir, long bytes) throws IOException {
        long free = new StatFs(dir.getPath()).getAvailableBytes();
        if (free < bytes) {
            throw new IOException(getString(R.string.build_space, dir.getPath(), bytes >> 20, free >> 20));
        }
    }

    // --- running the programs

    private interface Lines {
        void line(String line);
    }

    private interface Env {
        void set(Map<String, String> env);
    }

    /** Runs cmd in dir, its output (lines ended by \n or \r) to the log and to lines; its exit status. */
    private int run(List<String> cmd, File dir, Env env, PrintStream log, Lines lines)
            throws IOException, InterruptedException {
        if (cancelled) throw new IOException("cancelled");
        log.println("$ " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
        if (env != null) env.set(pb.environment());
        Process p = pb.start();
        process = p;
        if (cancelled) p.destroy();
        StringBuilder line = new StringBuilder();
        try (Reader in = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buf = new char[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                for (int i = 0; i < n; i++) {
                    char ch = buf[i];
                    if (ch == '\n' || ch == '\r') {
                        if (line.length() > 0) {
                            String s = line.toString();
                            if (ch == '\n') log.println(s);
                            try {
                                lines.line(s);
                            } catch (RuntimeException e) {
                                log.println("(unreadable: " + e + ")");
                            }
                            line.setLength(0);
                        }
                    } else {
                        line.append(ch);
                    }
                }
            }
        }
        int status = p.waitFor();
        process = null;
        log.println("exit " + status);
        return status;
    }

    private PrintStream openLog() throws IOException {
        File f = new File(GameCheck.home(), "build.log");
        try {
            return new PrintStream(new FileOutputStream(f), true, "UTF-8");
        } catch (IOException e) {  // no access to shared storage yet
            return new PrintStream(new FileOutputStream(new File(getFilesDir(), "build.log")), true, "UTF-8");
        }
    }

    // --- progress

    private void progress(String stage, int permille) {
        NativeBuild.post(s -> {
            s.stage = stage;
            s.permille = permille;
        });
        long now = System.currentTimeMillis();
        if (now - lastNotified < 1000) return;
        lastNotified = now;
        Context t = Lang.wrap(this);
        getSystemService(NotificationManager.class).notify(NOTIFICATION,
                notification(t, stageLabel(t, stage), permille, true));
    }

    static String stageLabel(Context t, String stage) {
        int id = t.getResources().getIdentifier("build_stage_" + stage, "string", t.getPackageName());
        return id != 0 ? t.getString(id) : stage;
    }

    private Notification notification(Context t, String text, int permille, boolean ongoing) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, SetupActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(t.getString(R.string.build_title))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true);
        if (ongoing) b.setProgress(1000, permille, false);
        return b.build();
    }
}
