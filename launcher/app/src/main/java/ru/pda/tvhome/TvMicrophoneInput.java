package ru.pda.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.media.*;
import android.os.*;
import android.speech.*;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Explicit built-in capture. USB and BLE routes are rejected, never used as a fallback. */
final class TvMicrophoneInput implements AutoCloseable {
    private AudioRecord record;
    private SpeechRecognizer speech;
    private ParcelFileDescriptor readEnd;
    private java.io.OutputStream output;
    private volatile boolean closed;
    @android.annotation.SuppressLint("MissingPermission")
    void start(Activity activity, Consumer<ArrayList<String>> result, Consumer<String> error) {
        try {
            if (Build.VERSION.SDK_INT < 33) throw new IllegalStateException("Нужен Android 13 или новее");
            AudioDeviceInfo builtin = null;
            for (AudioDeviceInfo device : activity.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS))
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) { builtin = device; break; }
            if (builtin == null) throw new IllegalStateException("Микрофон ТВ недоступен");
            int minimum = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(19200, minimum * 2)).build();
            if (!record.setPreferredDevice(builtin)) throw new IllegalStateException("ТВ отказал в выборе микрофона");
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            readEnd=pipe[0]; output=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
            speech=SpeechRecognizer.createSpeechRecognizer(activity, new ComponentName("com.google.android.tts",
                    "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"));
            speech.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle b) {}
                public void onBeginningOfSpeech() {}
                public void onRmsChanged(float v) {}
                public void onBufferReceived(byte[] b) {}
                public void onEndOfSpeech() {}
                public void onPartialResults(Bundle b) {}
                public void onEvent(int t,Bundle b) {}
                public void onError(int code) { if(!closed){close();error.accept("Речь не распознана. Повторите запрос");} }
                public void onResults(Bundle b) { if(closed)return;ArrayList<String> text=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);close();if(text==null||text.isEmpty())error.accept("Речь не распознана");else result.accept(text); }
            });
            record.startRecording();
            final AudioRecord recorder=record;final java.io.OutputStream stream=output;
            new Thread(() -> {
                try {
                    byte[] buffer=new byte[1920]; long deadline=SystemClock.elapsedRealtime()+15000;
                    while(!closed&&SystemClock.elapsedRealtime()<deadline) {
                        int count=recorder.read(buffer,0,buffer.length);
                        AudioDeviceInfo route=recorder.getRoutedDevice();
                        if(route==null||route.getType()!=AudioDeviceInfo.TYPE_BUILTIN_MIC)
                            throw new IllegalStateException("Микрофон ТВ не выбран системой");
                        if(count<0)throw new IllegalStateException("Не удалось прочитать микрофон ТВ");
                        stream.write(buffer,0,count);
                    }
                    stream.close();
                } catch(Exception failure) {
                    activity.runOnUiThread(() -> {if(!closed){close();error.accept("Не удалось получить звук со встроенного микрофона ТВ");}});
                }
            },"tv-microphone").start();
            speech.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ru-RU")
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE,readEnd)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,48000));
        } catch(Exception failure) {close();error.accept("Микрофон ТВ недоступен: "+failure.getMessage());}
    }
    public void close() {
        if(closed)return;closed=true;
        if(record!=null){try{record.stop();}catch(RuntimeException ignored){}record.release();record=null;}
        try{if(output!=null)output.close();}catch(Exception ignored){}
        try{if(readEnd!=null)readEnd.close();}catch(Exception ignored){}
        if(speech!=null){speech.cancel();speech.destroy();speech=null;}
    }
}
