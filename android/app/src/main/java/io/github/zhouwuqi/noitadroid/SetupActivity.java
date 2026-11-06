package io.github.zhouwuqi.noitadroid;

import static io.github.zhouwuqi.noitadroid.Ui.ACCENT;
import static io.github.zhouwuqi.noitadroid.Ui.BAD;
import static io.github.zhouwuqi.noitadroid.Ui.BG;
import static io.github.zhouwuqi.noitadroid.Ui.CARD;
import static io.github.zhouwuqi.noitadroid.Ui.DIM;
import static io.github.zhouwuqi.noitadroid.Ui.GOOD;
import static io.github.zhouwuqi.noitadroid.Ui.TEXT;
import static io.github.zhouwuqi.noitadroid.Ui.WAIT;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

/**
 * The launcher's activity. When the game can start (GameCheck), it goes straight to NoitaActivity and is never seen,
 * unless FMOD can still be imported and the user hasn't chosen to play without it;
 * otherwise it shows what's missing, with what to do about each item, and checks again whenever it comes back.
 * When the app builds the game's code on the device (NativeBuild), it starts that build (BuildService) and shows its
 * progress; from GOG's installer, when it finds one, it unpacks the game first.
 * Plain framework views: touch and a gamepad (A is the confirm key, B is back) both work.
 */
public class SetupActivity extends Activity implements NativeBuild.Listener {
    private LinearLayout rows;
    private TextView status, progress;
    private Button start, recheck, cancel;
    private boolean resumedBefore, wasRunning, shownBusy;
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {  // the elapsed time, while a build runs
        @Override
        public void run() {
            onStatus(NativeBuild.STATUS);
            if (NativeBuild.STATUS.running) ticker.postDelayed(this, 1000);
        }
    };

    /** Its strings in the app's language (Lang: English unless Chinese was picked), not the system's. */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null && !BuildService.running() && new GameCheck(this).startsAtOnce(this)) {
            startGame();  // not when recreated for a language
            return;
        }
        setContentView(layout());
    }

    /** The launcher's icon again, with this screen still up: into the game if it can start by now. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!BuildService.running() && new GameCheck(this).startsAtOnce(this)) startGame();
    }

    /**
     * Back from Android's settings, from copying the game over USB, or from the launcher: into the game when it can
     * start by now, else the list again. Not on the first resume, after onCreate (shown for a reason: something was
     * missing, or the language was just switched).
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (rows == null || isFinishing()) return;
        if (resumedBefore && !BuildService.running() && !NativeBuild.STATUS.done && new GameCheck(this).startsAtOnce(this)) {
            startGame();
            return;
        }
        resumedBefore = true;
        show();
        NativeBuild.listen(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        NativeBuild.unlisten(this);
        ticker.removeCallbacks(tick);
    }

    private void startGame() {
        startActivity(new Intent(this, NoitaActivity.class));
        finish();
        overridePendingTransition(0, 0);
    }

    private void show() {
        if (Environment.isExternalStorageManager()) {
            GameCheck.gameDir(this).mkdirs();  // so it's there to copy into, over USB too
        }
        GameCheck check = new GameCheck(this);
        boolean busy = BuildService.running() || NativeBuild.STATUS.running;
        shownBusy = busy;
        rows.removeAllViews();
        Button first = null;

        first = row(check.access, R.string.access_title, check.access == GameCheck.State.OK
                ? getString(R.string.access_ok, check.home.getPath())
                : getString(R.string.access_missing), check.access == GameCheck.State.OK ? 0 : R.string.access_action,
                v -> openAccessSettings(), first);

        String files;
        int filesAction = 0;
        View.OnClickListener onFiles = null;
        switch (check.files) {
            case WAITING: files = getString(R.string.files_waiting); break;
            case WRONG: files = getString(R.string.files_incomplete, Formatter.formatFileSize(this, check.wakSize),
                    Formatter.formatFileSize(this, check.wakExpected)); break;
            case MISSING:
                if (check.installer != null && check.buildsOnDevice) {
                    File installer = check.installer;
                    files = getString(R.string.files_installer, installer.getName(), installer.getParent(),
                            check.gameDir.getPath());
                    if (!busy) {
                        filesAction = R.string.files_installer_action;
                        onFiles = v -> confirmBuild(installer);
                    }
                } else if (check.missing.size() == GameCheck.REQUIRED.length) {
                    files = getString(check.buildsOnDevice ? R.string.files_missing_all_or_installer
                            : R.string.files_missing_all, check.gameDir.getPath());
                } else {
                    files = getString(R.string.files_missing_some, check.gameDir.getPath(),
                            String.join(", ", check.missing));
                }
                if (filesAction == 0 && check.buildsOnDevice && !busy) {
                    filesAction = R.string.choose_file;
                    onFiles = v -> pickFile();
                }
                break;
            default:
                files = getString(R.string.files_ok, check.gameDir.getPath());
                if (busy) break;
                if (check.version == GameCheck.State.WRONG && check.installer != null && check.buildsOnDevice) {
                    // The folder is another build, but GOG's installer is there: install from it instead.
                    File installer = check.installer;
                    files += " " + getString(R.string.files_wrong_installer, installer.getName(), installer.getParent());
                    filesAction = R.string.files_installer_action;
                    onFiles = v -> confirmBuild(installer);
                } else if (check.installer != null && "gog".equals(check.game)) {  // unpacked: GOG's installer can go
                    File installer = check.installer;
                    files += " " + getString(R.string.files_installer_left, installer.getParent(),
                            Formatter.formatFileSize(this, installer.length()));
                    filesAction = R.string.files_installer_delete;
                    onFiles = v -> confirmDelete(installer);
                } else if (check.buildsOnDevice) {  // another folder or GOG's installer
                    filesAction = R.string.choose_file;
                    onFiles = v -> pickFile();
                }
        }
        Button filesButton = row(check.files, R.string.files_title, files, filesAction, onFiles, null);
        if (first == null && check.files != GameCheck.State.OK) first = filesButton;  // not "delete the installer"

        row(check.version, R.string.version_title, check.version == GameCheck.State.WRONG
                ? getString(R.string.version_wrong, check.wrongFile, GameCheck.GOG_BUILD)
                : check.version == GameCheck.State.OK ? getString(R.string.version_ok, "steam".equals(check.game)
                        ? getString(R.string.game_steam) : getString(R.string.game_gog, GameCheck.GOG_BUILD))
                : getString(R.string.version_waiting), 0, null, null);

        if (check.buildsOnDevice) {
            String code;
            switch (check.nativeCode) {
                case OK: code = getString(R.string.native_ok); break;
                case WAITING: code = getString(R.string.native_waiting); break;
                default:
                    code = getString(check.need == NativeBuild.Need.OLD ? R.string.native_old
                            : check.need == NativeBuild.Need.OTHER_GAME ? R.string.native_other
                            : R.string.native_missing);
            }
            boolean canBuild = check.nativeCode == GameCheck.State.MISSING && !busy;
            first = row(busy ? GameCheck.State.WAITING : check.nativeCode, R.string.native_title, code,
                    canBuild ? R.string.native_action : 0, v -> confirmBuild(null), first);
        }

        String sound;
        int soundAction = 0;
        View.OnClickListener onSound = null;
        if (check.sound == GameCheck.State.OK) {
            sound = getString(check.buildsOnDevice ? R.string.sound_imported : R.string.sound_ok, check.fmodVersion);
        } else if (!check.buildsOnDevice) {
            sound = getString(R.string.sound_missing);
        } else if (check.fmodArchive != null) {
            File archive = check.fmodArchive;
            sound = getString(R.string.sound_archive, archive.getName(), archive.getParent());
            soundAction = R.string.sound_import;
            onSound = v -> importFmod(Uri.fromFile(archive), archive.getName());
        } else {
            sound = getString(R.string.sound_missing_device);
            soundAction = R.string.choose_file;
            onSound = v -> pickFile();
        }
        if (busy || importing) soundAction = 0;
        row(check.sound, R.string.sound_title, sound, soundAction, onSound, null);

        boolean ready = check.ready() && !busy;
        status.setText(busy ? R.string.setup_building : ready ? R.string.setup_ready : R.string.setup_not_ready);
        status.setTextColor(busy ? ACCENT : ready ? GOOD : BAD);
        start.setEnabled(ready);
        start.setAlpha(ready ? 1f : 0.4f);
        cancel.setVisibility(busy ? View.VISIBLE : View.GONE);
        recheck.setVisibility(busy ? View.GONE : View.VISIBLE);
        (ready ? start : busy ? cancel : first != null ? first : recheck).requestFocus();
    }

    // --- building

    /** Before the first build: what it takes (time, heat, the charger), then BuildService. */
    private void confirmBuild(File installer) {
        // plugged in, not BatteryManager.isCharging(): that one is only true while the level goes up, which on a
        // computer's USB port, with the device busy, it may not
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        boolean plugged = battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        String text = getString(installer != null ? R.string.build_confirm_installer : R.string.build_confirm)
                + (plugged ? "" : "\n\n" + getString(R.string.build_confirm_charger));
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.build_confirm_title)
                .setMessage(text)
                .setPositiveButton(R.string.build_confirm_go, (dialog, which) -> startBuild(installer))
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    private void startBuild(File installer) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);  // the progress, outside
        }
        BuildService.start(this, installer);
        wasRunning = true;
        progress.setText("");
        show();
    }

    /** BuildService's progress (main thread). */
    @Override
    public void onStatus(NativeBuild.Status s) {
        if (progress == null) return;
        if (s.running) {
            long secs = (System.currentTimeMillis() - s.startedAt) / 1000;
            progress.setTextColor(TEXT);
            progress.setText(getString(R.string.build_progress, BuildService.stageLabel(this, s.stage),
                    s.permille / 10, secs / 60, secs % 60));
            wasRunning = true;
            if (!shownBusy) show();  // the service started after this screen was drawn
            ticker.removeCallbacks(tick);
            ticker.postDelayed(tick, 1000);
        } else if (wasRunning) {  // just finished
            wasRunning = false;
            ticker.removeCallbacks(tick);
            if (s.failed) {
                progress.setTextColor(BAD);
                progress.setText(getString(R.string.build_failed, s.error));
            } else if (s.done) {
                long secs = (System.currentTimeMillis() - s.startedAt) / 1000;
                progress.setTextColor(GOOD);
                progress.setText(getString(R.string.build_done, secs / 60, secs % 60));
            } else {
                progress.setTextColor(DIM);
                progress.setText(R.string.build_cancelled);
            }
            show();
        }
    }

    // --- files the user picks: GOG's installer, noita.exe in a game folder, FMOD's download

    private static final int PICK = 2;

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try {
            startActivityForResult(i, PICK);
        } catch (ActivityNotFoundException e) {
            message(getString(R.string.pick_no_picker), BAD);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = displayName(uri);
        if (FmodImport.isArchive(name)) {
            importFmod(uri, name);
            return;
        }
        boolean installer = name.startsWith(GameCheck.INSTALLER_PREFIX) && name.toLowerCase(java.util.Locale.ROOT).endsWith(".exe");
        boolean exe = name.equalsIgnoreCase("noita.exe");
        if (!installer && !exe) {
            message(getString(R.string.pick_unknown, name), BAD);
            return;
        }
        File f = pathOf(uri, name);  // both need a path: innoextract and the game read files, not streams
        if (f == null || !f.isFile()) {
            message(getString(R.string.pick_no_path, name), BAD);
        } else if (installer) {
            confirmBuild(f);
        } else {
            File dir = f.getParentFile();
            GameCheck.setGameDir(this, dir.equals(new File(GameCheck.home(), "Noita")) ? null : dir);
            show();
        }
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[] {android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (RuntimeException e) {  // a provider that can't say
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last.substring(last.lastIndexOf('/') + 1);
    }

    /**
     * The file a picked document is, when the app can see it as a file (it has all-files access): the storage's own
     * documents name volume and path; Downloads' sometimes give the path; else, the same name in Download.
     */
    private static File pathOf(Uri uri, String name) {
        try {
            String id = android.provider.DocumentsContract.getDocumentId(uri);
            if ("com.android.externalstorage.documents".equals(uri.getAuthority()) && id.contains(":")) {
                String volume = id.substring(0, id.indexOf(':')), rel = id.substring(id.indexOf(':') + 1);
                File base = volume.equalsIgnoreCase("primary") ? Environment.getExternalStorageDirectory()
                        : new File("/storage/" + volume);
                return new File(base, rel);
            }
            if (id.startsWith("raw:")) return new File(id.substring(4));
        } catch (IllegalArgumentException e) {  // not a document
        }
        File inDownload = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                name);
        return inDownload.isFile() ? inDownload : null;
    }

    // --- FMOD

    private boolean importing;

    /** FmodImport, off the main thread; then the app restarts, so its class loader has FMOD's classes (AppFactory). */
    private void importFmod(Uri uri, String name) {
        importing = true;
        message(getString(R.string.fmod_importing, name), ACCENT);
        show();
        Context app = getApplicationContext();
        new Thread(() -> {
            String error = null;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException(name);
                FmodImport.importFrom(Lang.wrap(app), in);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
                android.util.Log.e("noitadroid", "FMOD import", e);
            }
            String failure = error;
            runOnUiThread(() -> {
                importing = false;
                if (failure != null) {
                    message(getString(R.string.fmod_failed, failure), BAD);
                    show();
                } else {
                    restart();
                }
            });
        }, "fmod").start();
    }

    /** A new process (RestartActivity waits for this one to end, then opens this screen again). */
    private void restart() {
        startActivity(new Intent(this, RestartActivity.class));
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    // --- the installer, after it's unpacked

    private void confirmDelete(File installer) {
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.installer_delete_title)
                .setMessage(getString(R.string.installer_delete_text, installer.getName(),
                        Formatter.formatFileSize(this, installer.length()), installer.getParent()))
                .setPositiveButton(R.string.installer_delete_go, (dialog, which) -> {
                    if (!installer.delete()) message(getString(R.string.installer_delete_failed), BAD);
                    show();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }

    private void message(String text, int color) {
        progress.setTextColor(color);
        progress.setText(text);
    }

    private void openAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {  // some builds of Android only have the list of all apps
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // --- views

    private View layout() {
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(BG);
        root.setPadding(dp(32), dp(24), dp(32), dp(24));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        LinearLayout top = new LinearLayout(this);  // the icon, and the language switch at the right
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        Button lang = Ui.button(this, getString(R.string.setup_language), v -> {
            Lang.set(this, Lang.other(this));
            recreate();
        });
        lang.setSingleLine(true);
        top.addView(lang);
        left.addView(top, Ui.full(0, 0, 0, 0));
        left.addView(Ui.text(this, "noitadroid", 28, ACCENT, true), Ui.wrap(0, dp(8), 0, 0));
        left.addView(Ui.text(this, getString(R.string.setup_subtitle), 15, TEXT, false));
        left.addView(Ui.text(this, getString(R.string.setup_intro, GameCheck.GOG_BUILD), 13, DIM, false),
                Ui.wrap(0, dp(12), 0, 0));
        status = Ui.text(this, "", 14, TEXT, true);
        left.addView(status, Ui.wrap(0, dp(12), 0, 0));
        progress = Ui.text(this, "", 13, TEXT, false);
        left.addView(progress, Ui.wrap(0, dp(4), 0, 0));
        left.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout buttons = new LinearLayout(this);
        start = Ui.button(this, getString(R.string.start), v -> {
            GameCheck.setSilent(this, true);  // if it's still without sound: the user chose that, don't ask again
            startGame();
        });
        recheck = Ui.button(this, getString(R.string.recheck), v -> show());
        cancel = Ui.button(this, getString(R.string.build_cancel), v -> BuildService.cancel());
        buttons.addView(start);
        buttons.addView(recheck, Ui.wrap(dp(12), 0, 0, 0));
        buttons.addView(cancel, Ui.wrap(dp(12), 0, 0, 0));
        left.addView(buttons);
        root.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2));

        ScrollView scroll = new ScrollView(this);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3);
        right.leftMargin = dp(28);
        root.addView(scroll, right);
        return root;
    }

    /** One checked item: its state, what it is, what to do; with an action button, which it returns. */
    private Button row(GameCheck.State state, int title, String detail, int action, View.OnClickListener onAction,
                       Button first) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(Ui.rounded(this, CARD, 0, 0));
        String mark;
        int color;
        switch (state) {
            case OK: mark = "✓"; color = GOOD; break;
            case MISSING: case WRONG: mark = "✕"; color = BAD; break;
            case INFO: mark = "i"; color = ACCENT; break;
            default: mark = "…"; color = WAIT;
        }
        TextView glyph = Ui.text(this, mark, 22, color, true);
        glyph.setGravity(Gravity.CENTER);
        row.addView(glyph, new LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, getString(title), 16, TEXT, true));
        texts.addView(Ui.text(this, detail, 13, DIM, false), Ui.wrap(0, dp(2), 0, 0));
        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        grow.leftMargin = dp(8);
        row.addView(texts, grow);
        Button b = null;
        if (action != 0) {
            b = Ui.button(this, getString(action), onAction);
            row.addView(b, Ui.wrap(dp(12), 0, 0, 0));
        }
        rows.addView(row, Ui.full(0, 0, 0, dp(10)));
        return first != null ? first : b;
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }
}
