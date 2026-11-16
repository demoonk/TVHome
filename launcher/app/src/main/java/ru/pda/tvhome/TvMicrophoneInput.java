package ru.pda.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * The listening session of the launcher. On this firmware the speech engine reads only the microphone it
 * chooses itself: a pipe handed over by the application answered ERROR_NO_MATCH over about 320 KB of a flat
 * noise floor, while the very same words went through when the engine took its own microphone. So the pipe
 * is tried only when the settings ask for the remote microphone, and a silent chosen device is handed to the
 * engine after two seconds instead of wasting the whole window.
 */
final class TvMicrophoneInput implements AutoCloseable {
    static final int SAMPLE_RATE = 16000;
    private SpeechRecognizer speech;
    private AudioRecord record;
    private ParcelFileDescriptor readEnd;
    private java.io.OutputStream output;
    private final AtomicLong bytes = new AtomicLong();
    private volatile boolean closed;
    private volatile boolean delivered;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long startedAt;
    private long lastWaveLog;
    private Consumer<String> partial;
    private DoubleConsumer level;
    private Consumer<String> state;

    /** The listening screen wants the words as they arrive and the level for its wave. */
    void onPartial(Consumer<String> callback) { partial = callback; }
    void onLevel(DoubleConsumer callback) { level = callback; }
    void onState(Consumer<String> callback) { state = callback; }

    /** SpeechRecognizer error codes as names. The numbers are the framework constants from android.jar, so this stays testable on plain JVM. */
    static String errorName(int code){
        switch(code){
            case 1:return "ERROR_NETWORK_TIMEOUT"; case 2:return "ERROR_NETWORK"; case 3:return "ERROR_AUDIO";
            case 4:return "ERROR_SERVER"; case 5:return "ERROR_CLIENT"; case 6:return "ERROR_SPEECH_TIMEOUT";
            case 7:return "ERROR_NO_MATCH"; case 8:return "ERROR_RECOGNIZER_BUSY"; case 9:return "ERROR_INSUFFICIENT_PERMISSIONS";
            case 10:return "ERROR_TOO_MANY_REQUESTS"; case 11:return "ERROR_SERVER_DISCONNECTED";
            case 12:return "ERROR_LANGUAGE_NOT_SUPPORTED"; case 13:return "ERROR_LANGUAGE_UNAVAILABLE";
            case 14:return "ERROR_CANNOT_CHECK_SUPPORT"; case 15:return "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS";
            default:return "error "+code;
        }
    }

    /** Saved listening limit in seconds; the setting offers 1–15 and anything else falls back to the default. */
    static int captureSeconds(int stored){ return stored >= 1 && stored <= 15 ? stored : 10; }

    /** Settings modes: 1 = пульт (движок системы), 0 = микрофон ТВ, 2 = пульт или микрофон ТВ.
     *  Device types: builtin=15, BLE headset=26, SCO=7, wired headset=3, USB headset=22. */
    static boolean acceptsRoute(int mode, int type) {
        if (mode == 0) return type == 15;
        if (mode == 2) return type == 15 || type == 26 || type == 7 || type == 3 || type == 22;
        return false;
    }

    /** Which input device to ask for: the remote mic when the mode allows it, otherwise the built-in TV mic. */
    static int preferredDevice(int mode, java.util.List<Integer> available) {
        if (mode == 2) for (int type : new int[]{26, 7, 3, 22}) if (available.contains(type)) return type;
        return 15;
    }

    /** The engine reads only the source it trusts, so the pipe is only worth trying when a remote mic is chosen. */
    static boolean pipeIsWorthTrying(int mode) { return mode == 2; }

    /** A level below this is the noise floor of a device that carries no sound at all. On this TV the
     *  remote-mic input stayed at about 0.039 for ten seconds while the same words were recognised
     *  through the engine's own microphone. */
    static final float SILENCE_LEVEL = 0.08f;

    @android.annotation.SuppressLint("MissingPermission")
    void start(Activity activity, Consumer<ArrayList<String>> result, Consumer<String> error, int seconds, int mode) {
        if (!pipeIsWorthTrying(mode)) { startPlain(activity, result, error, seconds); return; }
        try {
            java.util.List<AudioDeviceInfo> inputs = java.util.Arrays.asList(
                    activity.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS));
            java.util.ArrayList<Integer> types = new java.util.ArrayList<>();
            for (AudioDeviceInfo device : inputs) types.add(device.getType());
            int wanted = preferredDevice(mode, types);
            AudioDeviceInfo chosen = null;
            for (AudioDeviceInfo device : inputs) if (device.getType() == wanted) { chosen = device; break; }
            if (chosen == null) { android.util.Log.w("tvvoice", "chosen microphone type " + wanted + " is absent, engine session instead");
                startPlain(activity, result, error, seconds); return; }
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(19200, minimum * 2)).build();
            if (!record.setPreferredDevice(chosen)) throw new IllegalStateException("ТВ отказал в выборе микрофона");
            android.util.Log.w("tvvoice", "record state " + record.getState() + " wanted type " + wanted);
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            readEnd = pipe[0]; output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
            speech = newRecognizer(activity, result, error);
            startedAt = SystemClock.elapsedRealtime();
            record.startRecording();
            final AudioRecord recorder = record; final java.io.OutputStream stream = output;
            new Thread(() -> {
                try {
                    byte[] buffer = new byte[1920]; long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
                    boolean routeLogged = false; float peak = 0;
                    while (!closed && SystemClock.elapsedRealtime() < deadline) {
                        int count = recorder.read(buffer, 0, buffer.length);
                        AudioDeviceInfo route = recorder.getRoutedDevice();
                        if (route == null || !acceptsRoute(mode, route.getType()))
                            throw new IllegalStateException("Слушается не тот источник, что выбран в настройках");
                        if (!routeLogged) { routeLogged = true; android.util.Log.w("tvvoice", "capturing from device type " + route.getType()); }
                        if (count < 0) throw new IllegalStateException("Не удалось прочитать микрофон");
                        if (count > 0) {
                            bytes.addAndGet(count); stream.write(buffer, 0, count);
                            float measured = VoiceLevelMeter.fromSamples(buffer, count);
                            if (measured > peak) peak = measured;
                            if (level != null) level.accept((double) measured);
                            logThrottled("wave pcm level " + measured);
                        }
                        // The remote-mic input of this set is open but silent: waiting the whole window
                        // wastes it. After two seconds without any rise the engine is asked to listen itself.
                        if (SystemClock.elapsedRealtime() - startedAt > 2000 && peak < SILENCE_LEVEL) {
                            android.util.Log.w("tvvoice", "chosen microphone stayed silent, level " + peak + " bytes=" + bytes.get());
                            stream.close();
                            activity.runOnUiThread(() -> { if (!closed) { close(); error.accept("Выбранный микрофон не дал звук"); } });
                            return;
                        }
                    }
                    stream.close();
                    // The engine can still answer after the audio ends. If it stays silent, the window must say so
                    // instead of hanging on "Слушаю микрофон ТВ…" forever.
                    handler.postDelayed(() -> {
                        if (!closed && !delivered) {
                            android.util.Log.w("tvvoice", "no result after " + (SystemClock.elapsedRealtime() - startedAt) + "ms bytes=" + bytes.get());
                            close(); error.accept("Микрофон не дал звук за " + seconds + " с");
                        }
                    }, 4000);
                } catch (Exception failure) {
                    android.util.Log.w("tvvoice", "capture ended: " + failure.getMessage() + " bytes=" + bytes.get());
                    activity.runOnUiThread(() -> { if (!closed) { close(); error.accept(failure instanceof java.io.IOException
                            ? "Распознаватель прекратил слушать раньше, чем закончилась запись"
                            : "Не удалось получить звук: " + failure.getMessage()); } });
                }
            }, "tv-microphone").start();
            speech.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE));
        } catch (Exception failure) { close(); error.accept("Микрофон недоступен: " + failure.getMessage()); }
    }

    /** Session without the pipe: the engine takes its own microphone, and the listening time is ours. */
    void startPlain(Activity activity, Consumer<ArrayList<String>> result, Consumer<String> error, int seconds) {
        try {
            speech = newRecognizer(activity, result, error);
            startedAt = SystemClock.elapsedRealtime();
            android.util.Log.w("tvvoice", "session limit " + seconds + "s");
            speech.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    // No PREFER_OFFLINE: this firmware has no downloaded Russian data, so the offline-only
                    // request answered ERROR_LANGUAGE_UNAVAILABLE at once. Without it the engine may use its
                    // online recognition, and a downloaded language would still be picked up first.
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true));
            handler.postDelayed(() -> {
                if (closed || speech == null) return;
                android.util.Log.w("tvvoice", "stop listening after " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
                try { speech.stopListening(); } catch (RuntimeException ignored) {}
            }, seconds * 1000L);
            handler.postDelayed(() -> {
                // The engine can answer a moment after the window closes; if it stays silent, say so.
                if (!closed && !delivered) {
                    android.util.Log.w("tvvoice", "no result after " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
                    close(); error.accept("Не услышало за " + seconds + " с");
                }
            }, seconds * 1000L + 4000L);
        } catch (Exception failure) { close(); error.accept("Микрофон недоступен: " + failure.getMessage()); }
    }

    private SpeechRecognizer newRecognizer(Activity activity, Consumer<ArrayList<String>> result, Consumer<String> error) {
        SpeechRecognizer recognizer = SpeechRecognizer.createSpeechRecognizer(activity, new ComponentName("com.google.android.tts",
                "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"));
        recognizer.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle b) {
                android.util.Log.w("tvvoice", "ready after " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
                if (state != null) state.accept("Слушаю");
            }
            public void onBeginningOfSpeech() {
                android.util.Log.w("tvvoice", "speech started");
                if (state != null) state.accept("Слышу голос");
            }
            public void onRmsChanged(float v) {
                if (level != null) level.accept((double) VoiceLevelMeter.normalize(v));
                logThrottled("wave engine rms " + v);
            }
            public void onBufferReceived(byte[] b) {}
            public void onEndOfSpeech() {
                android.util.Log.w("tvvoice", "speech ended");
                if (state != null) state.accept("Распознаю…");
            }
            public void onPartialResults(Bundle b) {
                if (partial == null) return;
                ArrayList<String> text = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (text != null && !text.isEmpty()) { partial.accept(text.get(0)); android.util.Log.w("tvvoice", "words " + text.get(0)); }
            }
            public void onEvent(int t, Bundle b) {}
            public void onError(int code) {
                if (closed) return;
                android.util.Log.w("tvvoice", "error " + errorName(code) + " after " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
                if (code == 4 || code == 2) error.accept("Нет связи с распознаванием речи");
                else if (code == 7) error.accept("Речь не распознана (" + errorName(code) + "). Повторите запрос");
                else error.accept("Распознавание не удалось: " + errorName(code));
            }
            public void onResults(Bundle b) {
                if (closed) return;
                ArrayList<String> text = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                delivered = true;
                android.util.Log.w("tvvoice", "results " + (text == null ? 0 : text.size()) + " after " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
                close();
                if (text == null || text.isEmpty()) error.accept("Речь не распознана"); else result.accept(text);
            }
        });
        return recognizer;
    }

    private void logThrottled(String line) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastWaveLog < 2000) return;
        lastWaveLog = now;
        android.util.Log.w("tvvoice", line);
    }

    public void close() {
        if (closed) return; closed = true;
        if (speech != null) { try { speech.cancel(); } catch (RuntimeException ignored) {}
            try { speech.destroy(); } catch (RuntimeException ignored) {} speech = null; }
        if (record != null) { try { record.stop(); } catch (RuntimeException ignored) {} record = null; }
        if (output != null) { try { output.close(); } catch (Exception ignored) {} output = null; }
        if (readEnd != null) { try { readEnd.close(); } catch (Exception ignored) {} readEnd = null; }
    }
}
