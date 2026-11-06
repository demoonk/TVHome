package com.gathin.tvhome;
import android.os.Bundle;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;
/** Selected assistant entry point shared by the remote and header button. */
public final class LauncherAssistantService extends VoiceInteractionService {
 private static LauncherAssistantService current;
 @Override public void onReady(){super.onReady();current=this;}
 @Override public void onShutdown(){if(current==this)current=null;super.onShutdown();}
 static boolean launch(){if(current==null)return false;try{current.showSession(new Bundle(),VoiceInteractionSession.SHOW_SOURCE_ASSIST_GESTURE);return true;}catch(RuntimeException e){return false;}}
}
