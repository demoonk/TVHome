package io.github.sumyabazar1.bridgehelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.io.File;

/**
 * Nothing runs here by itself: the app does not connect at boot any more. It answers one action,
 * {@value #ACTION_ENSURE}, which the settings row in Home («Система» → «Запросить загрузку сейчас») sends when the
 * owner presses it. The same single job runs when the app is opened.
 *
 * One ask, one connection. The TV shows the authorization dialog for every connection, so a press is a prompt the
 * owner asked for; the receiver does not connect on its own and does not ask again for the same press.
 *
 * goAsync() holds the receiver open only while the single attempt runs, with a bounded timeout inside AdbClient.
 * No foreground service, no forever loop: a refused or unanswered key means the dialog is open on the TV, and
 * spinning would be pointless.
 */
public final class EnsureReceiver extends BroadcastReceiver {

    private static final String TAG = "bridge-helper";
    public static final String ACTION_ENSURE = "io.github.sumyabazar1.bridgehelper.action.ENSURE";

    /**
     * The broadcast ANR on this TV is 60 s, and a connect that adbd does not answer burns the whole connect
     * timeout, so a couple of attempts pass the window and the app is killed as an unresponding background
     * process. The pending result is therefore released after this window: the broadcast is answered, the run
     * continues on its own thread, and a slow connect is not paid with a killed process.
     */
    static final long RECEIVER_FINISH_MS = 12000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_ENSURE.equals(intent.getAction())) return;
        final PendingResult pending = goAsync();
        final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) pending.finish();
        };
        new Thread(() -> {
            try {
                File imported = new File(context.getExternalFilesDir(null), "adbkey");
                File own = new File(context.getFilesDir(), "adbkey.pem");
                AdbKey key = AdbKey.loadOrCreate(imported, own);
                if (imported.isFile() && !AdbKey.readableFile(imported))
                    Log.w(TAG, "cannot read the key the user placed, " + imported + ": a file adb created in this folder "
                            + "belongs to shell and is not readable for the app; using " + own);
                File stored = storedKeyFile(context.getExternalFilesDir(null), context.getFilesDir());
                Log.i(TAG, "key string: " + key.keyString(stored) + (stored != null
                        ? " (from the file the user placed: " + stored.getName() + ")"
                        : " (the app's own key, " + context.getFilesDir() + ")"));
                // On this TV an answer that arrives after the connection is closed is ignored
                // ("authorization received for deleted transport"), and the key is never written to the list adbd
                // reads, so the run keeps the connection open long enough for the answer to the dialog.
                AdbClient.Result result = new AdbClient(key, stored, AdbClient.DIALOG_WAIT_MS,
                        AdbClient.READ_TIMEOUT_MS, AdbClient.DEADLINE_MS, line -> Log.i(TAG, line)).run();
                Log.i(TAG, "attempt: " + result.status + " — " + result.message);
                if (!result.output.isEmpty()) Log.i(TAG, "output: " + result.output);
            } catch (Exception e) {
                Log.e(TAG, "bridge-helper failed: " + e);
            } finally {
                finish.run();
            }
        }, "bridge-helper-ensure").start();
        new Thread(() -> {
            try {
                Thread.sleep(RECEIVER_FINISH_MS);
            } catch (InterruptedException ignored) {
                return;
            }
            if (!finished.get()) Log.i(TAG, "broadcast released after " + RECEIVER_FINISH_MS
                    + " ms, the run continues on its own thread");
            finish.run();
        }, "bridge-helper-release").start();
    }

    /** The string the TV stored is the whole line, comment included, so the file the user placed is read as it is. */
    static File storedKeyFile(File externalDir, File ownDir) {
        for (String name : new String[]{"adbkey.pub", "approved.key"}) {
            File inExternal = new File(externalDir, name);
            if (AdbKey.readableFile(inExternal)) return inExternal;
            File inOwn = new File(ownDir, name);
            if (AdbKey.readableFile(inOwn)) return inOwn;
        }
        return null;
    }
}
