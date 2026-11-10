package ru.pda.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TvMicrophoneInputTest {
    @Test public void theCodesTheEngineReportsHaveNames(){
        assertEquals("ERROR_NO_MATCH",TvMicrophoneInput.errorName(7));
        assertEquals("ERROR_SPEECH_TIMEOUT",TvMicrophoneInput.errorName(6));
        assertEquals("ERROR_CLIENT",TvMicrophoneInput.errorName(5));
        assertEquals("ERROR_SERVER",TvMicrophoneInput.errorName(4));
        assertEquals("ERROR_AUDIO",TvMicrophoneInput.errorName(3));
        assertEquals("ERROR_RECOGNIZER_BUSY",TvMicrophoneInput.errorName(8));
        assertEquals("ERROR_NETWORK",TvMicrophoneInput.errorName(2));
    }
    @Test public void anUnknownCodeIsStillReportedAsANumber(){
        assertEquals("error 99",TvMicrophoneInput.errorName(99));
    }
    @Test public void theSavedListeningLimitIsUsedAndABrokenValueFallsBack(){
        assertEquals(5,TvMicrophoneInput.captureSeconds(5));
        assertEquals(15,TvMicrophoneInput.captureSeconds(15));
        assertEquals(10,TvMicrophoneInput.captureSeconds(0));
        assertEquals(10,TvMicrophoneInput.captureSeconds(20));
    }
    @Test public void theModeDecidesWhichMicrophoneIsAllowed(){
        assertTrue(TvMicrophoneInput.acceptsRoute(0,15)); // built-in TV mic
        assertFalse(TvMicrophoneInput.acceptsRoute(0,26)); // BLE headset is not the TV mic
        assertTrue(TvMicrophoneInput.acceptsRoute(2,26)); // combined mode takes the remote mic
        assertTrue(TvMicrophoneInput.acceptsRoute(2,15)); // and the TV mic as well
        assertFalse(TvMicrophoneInput.acceptsRoute(1,15)); // window path: the engine chooses the device
    }
    @Test public void combinedModePrefersTheRemoteMicButFallsBackToTheTv(){
        assertEquals(26,TvMicrophoneInput.preferredDevice(2,java.util.List.of(15,26)));
        assertEquals(15,TvMicrophoneInput.preferredDevice(2,java.util.List.of(15)));
        assertEquals(15,TvMicrophoneInput.preferredDevice(0,java.util.List.of(15,26)));
    }
    @Test public void thePipeIsOnlyTriedForTheChosenRemoteMic(){
        assertTrue(TvMicrophoneInput.pipeIsWorthTrying(2));
        assertFalse(TvMicrophoneInput.pipeIsWorthTrying(0));
        assertFalse(TvMicrophoneInput.pipeIsWorthTrying(1));
    }
    @Test public void theRateTheEngineIsToldMatchesTheCapture(){
        assertEquals(16000,TvMicrophoneInput.SAMPLE_RATE);
    }
}
