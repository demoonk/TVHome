package ru.pda.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Very small local system read: CPU busy percent and available memory in MB. */
final class SystemStats {
    private long lastTotal = -1L;
    private long lastIdle = -1L;

    static long freeMemoryMb(Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            return Math.max(0L, info.availMem / (1024L * 1024L));
        } catch (RuntimeException unavailable) {
            return -1L;
        }
    }

    int cpuBusyPercent() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream("/proc/stat")))) {
            String line = reader.readLine();
            if (line == null) return -1;
            String[] fields = line.split("\\s+");
            if (fields.length < 8) {
                Log.w("tvhome-stats", "CPU fields are too short: " + fields.length);
                return -1;
            }
            long user = parse(fields[1]);
            long nice = parse(fields[2]);
            long system = parse(fields[3]);
            long idle = parse(fields[4]);
            long ioWait = parse(fields[5]);
            long irq = parse(fields[6]);
            long softIrq = parse(fields[7]);
            long total = user + nice + system + idle + ioWait + irq + softIrq;
            if (total <= 0L) return -1;
            if (lastTotal < 0L) {
                lastTotal = total;
                lastIdle = idle;
                return Math.max(0, Math.min(100, (int) ((total - idle) * 100L / total)));
            }
            long totalDelta = total - lastTotal;
            long idleDelta = idle - lastIdle;
            lastTotal = total;
            lastIdle = idle;
            if (totalDelta <= 0L) return 0;
            long busyDelta = totalDelta - idleDelta;
            return Math.max(0, Math.min(100, (int) Math.round(busyDelta * 100.0 / totalDelta)));
        } catch (RuntimeException | java.io.IOException unavailable) {
            Log.w("tvhome-stats", "CPU unavailable: " + unavailable);
            return -1;
        }
    }

    private static long parse(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            return 0L;
        }
    }
}
