import java.io.FileInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Living-room bridge: observes evdev without grabbing or injecting input. */
public final class PqRemoteProof {
    private static final int GEAR_SCAN = 0x000c009f;
    private static final int KEY_UNKNOWN = 240;
    private static final String TARGET =
            "com.android.tv.settings/.partnercustomizer.picture.PictureActivity";

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8);
    }

    private static int i32(byte[] data, int offset) {
        return u16(data, offset) | (u16(data, offset + 2) << 16);
    }

    private static String output(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (InputStream input = process.getInputStream()) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int size;
            while ((size = input.read(buffer)) >= 0) { if (bytes.size() > 1024 * 1024) break; bytes.write(buffer, 0, size); }
            process.waitFor(3, TimeUnit.SECONDS);
            return bytes.toString("UTF-8");
        } finally { process.destroy(); }
    }
    private static final java.util.concurrent.atomic.AtomicBoolean returning = new java.util.concurrent.atomic.AtomicBoolean();
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
            } catch (Exception unavailable) { System.out.println("SEARCH_RETURN_ERROR " + unavailable.getClass().getSimpleName()); }
            finally { returning.set(false); }
        }, "search-return");
        worker.setDaemon(true); worker.start();
    }

    private static void openPicture() throws Exception {
        Process process = new ProcessBuilder("/system/bin/am", "start", "-n", TARGET)
                .redirectErrorStream(true).start();
        // A separate consumer keeps the input reader independent of activity launch latency.
        Thread output = new Thread(() -> {
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
        }, "pq-am-output");
        output.setDaemon(true);
        output.start();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("device eventSize seconds");
        }
        String device = args[0];
        int eventSize = Integer.parseInt(args[1]);
        int seconds = Integer.parseInt(args[2]);
        if (!device.matches("/dev/input/event[0-9]+")
                || (eventSize != 16 && eventSize != 24) || seconds < 0 || seconds > 600) {
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
        byte[] record = new byte[eventSize];
        int pendingScan = -1;
        long lastLaunch = Long.MIN_VALUE / 2;
        try (InputStream input = new FileInputStream(device)) {
            System.out.println("PQ_REMOTE_READY device=" + device + " size=" + eventSize
                    + " arch=" + System.getProperty("os.arch") + " lifetime=" + seconds);
            System.out.flush();
            while (true) {
                int read = 0;
                while (read < eventSize) {
                    int size = input.read(record, read, eventSize - read);
                    if (size < 0) throw new IllegalStateException("Input device disconnected");
                    read += size;
                }
                int type = u16(record, eventSize - 8);
                int code = u16(record, eventSize - 6);
                int value = i32(record, eventSize - 4);
                if (type == 4 && code == 4) pendingScan = value; // EV_MSC / MSC_SCAN
                if (type == 1) { // EV_KEY
                    if (code == 158 && value == 0) returnToSearch(); // KEY_BACK, observed without grabbing input
                    if (code == KEY_UNKNOWN && value == 0 && pendingScan == GEAR_SCAN) {
                        long now = System.nanoTime();
                        if (now - lastLaunch >= TimeUnit.MILLISECONDS.toNanos(800)) {
                            lastLaunch = now;
                            System.out.println("PQ_REMOTE_OPEN");
                            openPicture();
                        }
                    }
                    System.out.flush();
                }
            }
        }
    }
}
