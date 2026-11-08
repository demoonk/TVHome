package ru.pda.tvhome;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;
/** Selected assistant entry point shared by the remote and header button. */
public class LauncherAssistantService extends VoiceInteractionService {
    private static LauncherAssistantService current;
    private final Handler warmUp = new Handler(Looper.getMainLooper());
    private SpeechRecognizer warmEngine;
    private boolean warmed;
    @Override public void onReady(){
        super.onReady();current=this;
        if(!warmed){warmed=true;warm();}
    }
    /**
     * The first real request cold-starts the speech process and its native library (about 450 ms on
     * this TV), so the first words are lost. Warming once when the assistant is ready removes that
     * cost from the user's first press; the capture is released immediately.
     */
    private void warm(){
        try{
            warmEngine = SpeechRecognizer.createSpeechRecognizer(this, new ComponentName(
                    "com.google.android.tts", "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"));
            warmEngine.setRecognitionListener(new RecognitionListener(){
                public void onReadyForSpeech(Bundle b){} public void onBeginningOfSpeech(){}
                public void onRmsChanged(float rms){} public void onBufferReceived(byte[] buffer){}
                public void onEndOfSpeech(){} public void onError(int code){}
                public void onResults(Bundle b){} public void onPartialResults(Bundle b){}
                public void onEvent(int type, Bundle b){}
            });
            warmEngine.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU"));
            warmUp.postDelayed(this::releaseWarmEngine, 1500);
        }catch(RuntimeException unavailable){ releaseWarmEngine(); /* Engine unavailable: nothing to warm. */ }
    }
    private void releaseWarmEngine(){
        warmUp.removeCallbacks(this::releaseWarmEngine);
        if(warmEngine!=null){try{warmEngine.cancel();}catch(RuntimeException ignored){}
            try{warmEngine.destroy();}catch(RuntimeException ignored){} warmEngine=null;}
    }
    @Override public void onDestroy(){ releaseWarmEngine(); super.onDestroy(); }
    @Override public void onShutdown(){if(current==this)current=null;super.onShutdown();}
    static boolean launch(){if(current==null)return false;try{current.showSession(new Bundle(),VoiceInteractionSession.SHOW_SOURCE_ASSIST_GESTURE);return true;}catch(RuntimeException e){return false;}}
}
