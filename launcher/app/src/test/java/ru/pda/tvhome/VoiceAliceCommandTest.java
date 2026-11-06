package ru.pda.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.URLDecoder;

public final class VoiceAliceCommandTest {
    @Test public void extractsMessageWithoutChangingItsMeaning(){
        assertEquals("Сколько будет 2+2?",VoiceAliceCommand.parse("  АЛИСА, Сколько будет 2+2?  "));
        assertEquals("выключи телевизор",VoiceAliceCommand.parse("Алиса выключи телевизор"));
        assertEquals("",VoiceAliceCommand.parse("Алиса"));
        assertEquals("",VoiceAliceCommand.parse("Алиса, "));
    }
    @Test public void ignoresOtherCommandsAndSimilarWords(){
        assertNull(VoiceAliceCommand.parse("найди фильм в Нум"));
        assertNull(VoiceAliceCommand.parse("Алисавстране чудес"));
        assertNull(VoiceAliceCommand.parse(null));
    }
    @Test public void encodesOneJsonMessageIncludingQuotesAndControls()throws Exception{
        String url=VoiceAliceCommand.url("Кот \"Мур\" + & ? # \\\nпривет 🐈");
        String json=URLDecoder.decode(url.substring(url.indexOf('=')+1),"UTF-8");
        assertEquals("{\"text\":\"Кот \\\"Мур\\\" + & ? # \\\\\\u000aпривет 🐈\"}",json);
        assertFalse(url.contains("\n"));
        assertEquals("https://ya.ru/alice",VoiceAliceCommand.url(""));
    }
}
