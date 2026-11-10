package io.github.sumyabazar1.bridgehelper;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;

/**
 * The one screen of this add-on: it runs the single bounded job and shows what adbd answered, so the owner can see
 * the result without logcat. The app does not do this at boot any more; the job happens when the owner opens the
 * app or when the settings row in Home («Система» → «Запросить загрузку сейчас») sends the action to the receiver.
 *
 * The first run is also where the key gets approved: adbd answers an unknown key with the authorization dialog on
 * the TV, the owner presses Allow once, the approval persists, and every later connection goes through without a
 * dialog.
 */
public final class EnsureActivity extends Activity {

    private static final String TAG = "bridge-helper";
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ensure);
        status(getString(R.string.status_pending));
        new Thread(() -> {
            String line;
            try {
                File imported = new File(getExternalFilesDir(null), "adbkey");
                File own = new File(getFilesDir(), "adbkey.pem");
                AdbKey key = AdbKey.loadOrCreate(imported, own);
                File stored = EnsureReceiver.storedKeyFile(getExternalFilesDir(null), getFilesDir());
                Log.i(TAG, "key: " + (AdbKey.readableFile(imported) ? "the key the user placed in the app's folder"
                        : imported.isFile() ? "the placed file is not readable for this app, so " + own
                                            : "the app's own generated key, " + getFilesDir()));
                // adbd compares the whole string, comment included, so the app must send the string the TV stored for
                // this key. What the TV has with alwaysAllow = true is "…AQA= demOOn@HOMRPC"; the app's own comment
                // bridgehelper@tvhome is stored with alwaysAllow = false and is answered with a prompt every time.
                Log.i(TAG, "key string: " + key.keyString(stored));
                AdbClient.Result result = new AdbClient(key, stored, AdbClient.DIALOG_WAIT_MS,
                        AdbClient.READ_TIMEOUT_MS, AdbClient.DEADLINE_MS, line2 -> Log.i(TAG, line2)).run();
                Log.i(TAG, "open: " + result.status + " — " + result.message);
                if (!result.output.isEmpty()) Log.i(TAG, "output: " + result.output);
                line = result.status + " — " + result.message;
                if (!result.output.isEmpty()) line = line + "\n" + result.output;
            } catch (Exception e) {
                Log.e(TAG, "bridge-helper failed: " + e);
                line = "Ошибка: " + e;
            }
            String shown = line;
            main.post(() -> status(shown));
        }, "bridge-helper-ensure").start();
    }

    private void status(String text) {
        ((android.widget.TextView) findViewById(R.id.status)).setText(text);
    }
}
