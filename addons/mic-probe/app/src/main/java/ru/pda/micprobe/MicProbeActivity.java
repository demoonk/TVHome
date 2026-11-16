package ru.pda.micprobe;

import android.app.Activity;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * A throwaway diagnostic for the remote microphone: it lists the input devices the set exposes,
 * records a window from the chosen one, writes the WAV beside its own files and prints the level
 * so a silent microphone is visible without looking at the screen.
 *
 *   adb shell am start -n ru.pda.micprobe/.MicProbeActivity --ei seconds 12 --ei device 26
 *
 * Device types: 15 built-in, 26 Bluetooth headset, 7 SCO, 3 wired headset, 22 USB headset.
 * Without --device the built-in microphone is used.
 */
public class MicProbeActivity extends Activity {
    private static final int RATE = 16000;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        int seconds = clamp(getIntent().getIntExtra("seconds", 12), 1, 60);
        int wanted = getIntent().getIntExtra("device", -1);
        File directory = getExternalFilesDir(null);
        StringBuilder report = new StringBuilder();

        AudioManager manager = getSystemService(AudioManager.class);
        List<AudioDeviceInfo> inputs = Arrays.asList(manager.getDevices(AudioManager.GET_DEVICES_INPUTS));
        report.append("input devices:\n");
        for (AudioDeviceInfo device : inputs) {
            report.append("  type=").append(device.getType()).append("\n");
        }

        AudioDeviceInfo chosen = null;
        for (AudioDeviceInfo device : inputs) {
            if (wanted < 0 || device.getType() == wanted) { chosen = device; break; }
        }
        if (chosen == null) {
            report.append("no input device of type ").append(wanted).append("\n");
            write(directory, "report.txt", report.toString());
            Log.w("micprobe", report.toString());
            finish();
            return;
        }

        report.append("recording type=").append(chosen.getType())
                .append(" for ").append(seconds).append("s\n");
        int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord record = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(19200, minimum * 2))
                .build();
        report.append("setPreferredDevice=").append(record.setPreferredDevice(chosen)).append("\n");

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        long peak = 0;
        long frames = 0;
        try {
            record.startRecording();
            byte[] buffer = new byte[1920];
            long deadline = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                int read = record.read(buffer, 0, buffer.length);
                if (read <= 0) continue;
                pcm.write(buffer, 0, read);
                frames += read / 2;
                for (int i = 0; i + 1 < read; i += 2) {
                    short sample = (short) ((buffer[i] & 0xff) | (buffer[i + 1] << 8));
                    if (Math.abs(sample) > peak) peak = Math.abs(sample);
                }
            }
            AudioDeviceInfo route = record.getRoutedDevice();
            report.append("routed through type=").append(route == null ? "none" : route.getType()).append("\n");
        } catch (Exception failure) {
            report.append("recording failed: ").append(failure.getMessage()).append("\n");
        } finally {
            try { record.stop(); } catch (RuntimeException ignored) {}
            record.release();
        }

        report.append("frames=").append(frames).append(" peak=").append(peak)
                .append(" (full scale 32767, silence is under 2500)\n");
        write(directory, "report.txt", report.toString());
        writeWave(directory, pcm.toByteArray());
        Log.w("micprobe", report.toString());
        finish();
    }

    private static int clamp(int value, int low, int high) {
        return value < low ? low : (value > high ? high : value);
    }

    private static void write(File directory, String name, String text) {
        try (FileOutputStream out = new FileOutputStream(new File(directory, name))) {
            out.write(text.getBytes("UTF-8"));
        } catch (Exception failure) {
            Log.w("micprobe", "could not write " + name + ": " + failure.getMessage());
        }
    }

    /** The header is four chunk fields: the RIFF block, the fmt block and the data block. */
    private static void writeWave(File directory, byte[] samples) {
        File target = new File(directory, "record-" + System.currentTimeMillis() + ".wav");
        try (FileOutputStream out = new FileOutputStream(target)) {
            int size = samples.length;
            out.write(new byte[] {'R', 'I', 'F', 'F'});
            writeLittle(out, size + 36);
            out.write(new byte[] {'W', 'A', 'V', 'E'});
            out.write(new byte[] {'f', 'm', 't', ' '});
            writeLittle(out, 16);
            writeLittle(out, 1);          // PCM
            writeLittle(out, 1);          // mono
            writeLittle(out, RATE);
            writeLittle(out, RATE * 2);   // bytes per second
            writeLittle(out, 2);          // bytes per sample
            writeLittle(out, 16);         // bits per sample
            out.write(new byte[] {'d', 'a', 't', 'a'});
            writeLittle(out, size);
            out.write(samples);
            Log.w("micprobe", "wrote " + target.getAbsolutePath() + " " + size + " bytes");
        } catch (Exception failure) {
            Log.w("micprobe", "could not write the WAV: " + failure.getMessage());
        }
    }

    private static void writeLittle(FileOutputStream out, int value) throws java.io.IOException {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }
}
