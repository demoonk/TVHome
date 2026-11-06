package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/** Explicit speech-engine delegation prevents recursion if Android selects this service. */
public final class AssistantRecognitionService extends RecognitionService {
    private SpeechRecognizer recognizer;
    private interface Delivery { void send() throws RemoteException; }
    private void deliver(Delivery delivery) { try { delivery.send(); } catch (RemoteException ignored) { close(); } }
    private void close() { if (recognizer != null) { recognizer.destroy(); recognizer = null; } }

    @Override protected void onStartListening(Intent intent, Callback callback) {
        close();
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this, new ComponentName(
                    "com.google.android.tts", "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"));
            recognizer.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle b) { deliver(() -> callback.readyForSpeech(b)); }
                public void onBeginningOfSpeech() { deliver(callback::beginningOfSpeech); }
                public void onRmsChanged(float rms) { deliver(() -> callback.rmsChanged(rms)); }
                public void onBufferReceived(byte[] buffer) { deliver(() -> callback.bufferReceived(buffer)); }
                public void onEndOfSpeech() { deliver(callback::endOfSpeech); }
                public void onError(int error) { deliver(() -> callback.error(error)); close(); }
                public void onResults(Bundle b) { deliver(() -> callback.results(b)); close(); }
                public void onPartialResults(Bundle b) { deliver(() -> callback.partialResults(b)); }
                public void onEvent(int type, Bundle b) { }
            });
            recognizer.startListening(new Intent(intent));
        } catch (RuntimeException unavailable) {
            deliver(() -> callback.error(SpeechRecognizer.ERROR_CLIENT)); close();
        }
    }
    @Override protected void onStopListening(Callback callback) { if (recognizer != null) recognizer.stopListening(); }
    @Override protected void onCancel(Callback callback) { close(); }
    @Override public void onDestroy() { close(); super.onDestroy(); }
}
