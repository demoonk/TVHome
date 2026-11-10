package ru.pda.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VoiceLevelMeterTest {
    @Test public void theEngineLevelIsNormalisedAndSilenceIsZero(){
        assertEquals(0f, VoiceLevelMeter.normalize(-2), 0.01f);   // no reading
        assertEquals(0.5f, VoiceLevelMeter.normalize(5), 0.01f);
        assertEquals(1f, VoiceLevelMeter.normalize(10), 0.01f);
        assertEquals(1f, VoiceLevelMeter.normalize(40), 0.01f);   // clamped
    }
    @Test public void theLevelFromCapturedPcmMatchesItsRootMeanSquare(){
        assertEquals(0f, VoiceLevelMeter.fromSamples(new byte[0], 0), 0.01f);
        byte[] loud = { (byte) 0xB8, 0x0B, (byte) 0xB8, 0x0B };  // two samples of 3000
        assertEquals(0.5f, VoiceLevelMeter.fromSamples(loud, loud.length), 0.01f);
        assertEquals(1f, VoiceLevelMeter.fromSamples(new byte[]{(byte) 0xFF, 0x7F, (byte) 0xFF, 0x7F}, 4), 0.02f);
    }
    @Test public void everySampleIsOneColumnOfTheTimeline(){
        VoiceLevelMeter meter = new VoiceLevelMeter(1000);
        assertEquals(0, meter.drawnColumns(1000));
        meter.push(0.2f); meter.push(0f); meter.push(0.9f);
        assertEquals(3, meter.drawnColumns(1000));
        assertEquals(0.2f, meter.valueAtAge(2), 0.01f);   // the oldest sample is drawn on the left
        assertEquals(0.9f, meter.newest(), 0.01f);
    }
    @Test public void theTimelineKeepsOnlyTheLastReadings(){
        VoiceLevelMeter meter = new VoiceLevelMeter(3);
        meter.push(0.2f); meter.push(0.9f);
        assertFalse(meter.silent());
        meter.push(0.4f);
        assertEquals(3, meter.count());
        assertEquals(2, meter.drawnColumns(2));
        meter.push(0f); meter.push(0f); meter.push(0f);
        assertTrue(meter.silent());
        assertEquals(0f, meter.valueAtAge(3), 0.01f);   // older than the window
    }
    @Test public void theMicrophoneCircleScalesWithTheLevelAndStaysInsideItsRange(){
        assertEquals(1f, VoiceLevelMeter.iconScale(0f), 0.01f);
        assertEquals(1.3f, VoiceLevelMeter.iconScale(1f), 0.01f);
        assertTrue(VoiceLevelMeter.iconScale(0.6f) > VoiceLevelMeter.iconScale(0.2f));
        assertEquals(1.3f, VoiceLevelMeter.iconScale(5f), 0.01f);   // clamped
    }
}
