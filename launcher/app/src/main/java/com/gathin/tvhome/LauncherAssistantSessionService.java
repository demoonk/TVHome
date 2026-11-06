package com.gathin.tvhome;

import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

public final class LauncherAssistantSessionService extends VoiceInteractionSessionService {
    @Override public VoiceInteractionSession onNewSession(Bundle args) {
        return new VoiceInteractionSession(this) {
            @Override public void onPrepareShow(Bundle args, int flags) {
                super.onPrepareShow(args, flags);
                setUiEnabled(false);
            }

            @Override public void onShow(Bundle args, int flags) {
                super.onShow(args, flags);
                try {
                    startAssistantActivity(new Intent(LauncherAssistantSessionService.this,
                            VoiceLaunchActivity.class).putExtra("remote_microphone", true));
                } finally { finish(); }
            }
        };
    }
}
