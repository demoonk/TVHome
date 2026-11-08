import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Living-room bridge: observes evdev without grabbing or injecting input, so the
 * firmware key auto-repeat (held volume keys) stays intact.
 *
 * Single press of the physical gear -> picture settings.
 * Held press (>= long press threshold) -> sound settings, fired as soon as the
 * threshold is reached, while the key is still down.
 */
public final class PqRemoteProof {
    private static final String PICTURE_TARGET =
            "com.android.tv.settings/.partnercustomizer.picture.PictureActivity";
    private static final String SOUND_TARGET =
            "com.android.tv.settings/.device.sound.SoundActivity";

    private static String output(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (InputStream input = process.getInputStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int size;
            while ((size = input.read(buffer)) >= 0) {
                if (bytes.size() > 1024 * 1024) break;
                bytes.write(buffer, 0, size);
            }
            process.waitFor(3, TimeUnit.SECONDS);
            return bytes.toString("UTF-8");
        } finally {
            process.destroy();
        }
    }

    /** The activities are resolved on the TV itself; nothing is guessed. */
    private static boolean available(String component) {
        try {
            return output("/system/bin/cmd", "package", "resolve-activity", "--brief", "-n", component)
                    .contains(component);
        } catch (Exception error) {
            System.out.println("PQ_REMOTE_RESOLVE_ERROR " + component + " " + error.getClass().getSimpleName());
            return false;
        }
    }

    private static final AtomicBoolean returning = new AtomicBoolean();

    private static void returnToSearch() {
        if (!returning.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try {
                String request = output("/system/bin/settings", "get", "secure", "ru_pda_tvhome_search_return").trim();
                String[] fields = request.split(":");
                if (fields.length != 2 || !fields[0].matches("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+") || !fields[1].matches("[0-9]+")) return;
                String activities = output("/system/bin/dumpsys", "activity", "activities");
                String resumed = "";
                for (String line : activities.split("\\r?\\n")) if (line.contains("ResumedActivity:")) resumed = line;
                if (!resumed.contains(fields[0] + "/") && !resumed.contains("ru.pda.tvhome/.MainActivity")) return;
                if (!request.equals(output("/system/bin/settings", "get", "secure", "ru_pda_tvhome_search_return").trim())) return;
                output("/system/bin/settings", "put", "secure", "ru_pda_tvhome_search_return", "");
                System.out.println("SEARCH_RETURN_BACK " + fields[0]);
                System.out.println(output("/system/bin/am", "start", "-n", "ru.pda.tvhome/.VoiceLaunchActivity", "--ez", "return_to_search", "true"));
            } catch (Exception unavailable) {
                System.out.println("SEARCH_RETURN_ERROR " + unavailable.getClass().getSimpleName());
            } finally {
                returning.set(false);
            }
        }, "search-return");
        worker.setDaemon(true);
        worker.start();
    }

    private static final AtomicLong lastLaunch = new AtomicLong(Long.MIN_VALUE / 2);

    private static void launch(String target, long holdMs, String reason) throws Exception {
        long now = System.nanoTime();
        if (now - lastLaunch.get() < TimeUnit.MILLISECONDS.toNanos(GearDecision.DEBOUNCE_MS)) {
            System.out.println("PQ_REMOTE_SKIP_DEBOUNCE hold=" + holdMs + " reason=" + reason);
            return;
        }
        lastLaunch.set(now);
        System.out.println("PQ_REMOTE_OPEN target=" + target + " hold=" + holdMs + " reason=" + reason);
        System.out.flush();
        Process process = new ProcessBuilder("/system/bin/am", "start", "-n", target)
                .redirectErrorStream(true).start();
        // A separate consumer keeps the input reader independent of activity launch latency.
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[1024];
                int size;
                while ((size = input.read(buffer)) >= 0) {
                    System.out.write(buffer, 0, size);
                    System.out.flush();
                }
                System.out.println("PQ_REMOTE_AM_EXIT " + process.waitFor());
            } catch (Exception error) {
                System.out.println("PQ_REMOTE_AM_ERROR " + error);
            }
        }, "gear-am-output");
        reader.setDaemon(true);
        reader.start();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException("device eventSize seconds [longPressMs]");
        }
        String device = args[0];
        int eventSize = Integer.parseInt(args[1]);
        int seconds = Integer.parseInt(args[2]);
        long longPressMs = args.length == 4 ? Long.parseLong(args[3]) : GearDecision.DEFAULT_LONG_PRESS_MS;
        if (!device.matches("/dev/input/event[0-9]+")
                || (eventSize != 16 && eventSize != 24) || seconds < 0 || seconds > 600
                || longPressMs < 100 || longPressMs > 5000) {
            throw new IllegalArgumentException("Invalid bounded experiment arguments");
        }
        // Zero is an explicitly requested current-boot session, stopped by the companion script.
        if (seconds > 0) {
            Thread expiry = new Thread(() -> {
                try {
                    Thread.sleep(seconds * 1000L);
                    System.out.println("PQ_REMOTE_EXPIRED");
                    System.exit(0);
                } catch (InterruptedException ignored) { }
            }, "pq-expiry");
            expiry.setDaemon(true);
            expiry.start();
        }

        boolean pictureAvailable = available(PICTURE_TARGET);
        boolean soundAvailable = available(SOUND_TARGET);

        byte[] record = new byte[eventSize];
        int pendingScan = -1;
        AtomicLong pressedAt = new AtomicLong(-1);
        AtomicBoolean longFired = new AtomicBoolean(false);

        try (InputStream input = new FileInputStream(device)) {
            System.out.println("PQ_REMOTE_READY device=" + device + " size=" + eventSize
                    + " arch=" + System.getProperty("os.arch") + " lifetime=" + seconds
                    + " long_ms=" + longPressMs
                    + " picture=" + (pictureAvailable ? "available" : "unavailable")
                    + " sound=" + (soundAvailable ? "available" : "unavailable"));
            System.out.flush();
            while (true) {
                int read = 0;
                while (read < eventSize) {
                    int size = input.read(record, read, eventSize - read);
                    if (size < 0) throw new IllegalStateException("Input device disconnected");
                    read += size;
                }
                int type = GearDecision.type(record, eventSize);
                int code = GearDecision.code(record, eventSize);
                int value = GearDecision.value(record, eventSize);
                if (type == GearDecision.EV_MSC && code == GearDecision.MSC_SCAN) pendingScan = value; // EV_MSC / MSC_SCAN
                if (type == GearDecision.EV_KEY) { // EV_KEY
                    if (code == GearDecision.KEY_BACK && value == 0) returnToSearch(); // KEY_BACK, observed without grabbing input
                    if (code == GearDecision.KEY_UNKNOWN && pendingScan == GearDecision.GEAR_SCAN) {
                        if (value == 1 && pressedAt.get() < 0) {
                            long pressAt = System.nanoTime();
                            pressedAt.set(pressAt);
                            longFired.set(false);
                            System.out.println("PQ_REMOTE_PRESS hold_ms=" + longPressMs);
                            if (soundAvailable) {
                                Thread held = new Thread(() -> {
                                    try {
                                        TimeUnit.MILLISECONDS.sleep(longPressMs);
                                    } catch (InterruptedException ignored) {
                                        return;
                                    }
                                    if (pressedAt.get() != pressAt || longFired.get()) return; // released before the threshold
                                    longFired.set(true);
                                    try {
                                        launch(SOUND_TARGET, longPressMs, "held");
                                    } catch (Exception error) {
                                        System.out.println("PQ_REMOTE_ERROR " + error);
                                    }
                                }, "gear-held");
                                held.setDaemon(true);
                                held.start();
                            }
                        } else if (value == 0 && pressedAt.get() >= 0) {
                            long holdMs = (System.nanoTime() - pressedAt.get()) / 1_000_000L;
                            pressedAt.set(-1);
                            if (!longFired.get()) {
                                launch(GearDecision.targetFor(holdMs, longPressMs, soundAvailable,
                                        PICTURE_TARGET, SOUND_TARGET), holdMs, "release");
                            }
                        }
                    }
                    System.out.flush();
                }
            }
        }
    }
}
