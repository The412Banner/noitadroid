package io.github.zhouwuqi.noitadroid;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * Starts the game again after its process ended itself (an import from the in-game menu: the new save goes in
 * before the game starts, SaveTransfer.applyPending). It runs in a process of its own (":restart", see the
 * manifest), waits for the game's to be gone, and opens the launcher's activity. Never seen.
 */
public class RestartActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        new Thread(() -> {
            ActivityManager am = getSystemService(ActivityManager.class);
            for (long until = SystemClock.elapsedRealtime() + 5000; SystemClock.elapsedRealtime() < until; ) {
                boolean running = false;
                for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                    running |= p.processName.equals(getPackageName());
                }
                if (!running) break;
                SystemClock.sleep(50);
            }
            runOnUiThread(() -> {
                // A new task: the old one may still hold the dead process's activities, and an intent equal to its
                // root's would only bring it to the front, starting nothing.
                startActivity(new Intent(this, SetupActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                finish();
                overridePendingTransition(0, 0);
            });
        }, "noitadroid-restart").start();
    }
}
