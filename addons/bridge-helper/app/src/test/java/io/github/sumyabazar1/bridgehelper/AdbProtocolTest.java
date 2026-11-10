package io.github.sumyabazar1.bridgehelper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * The framing as it was captured against adbd on the TV: 24-byte header (command, arg0, arg1, length, checksum,
 * magic), little-endian int32, then the payload. Every number below comes from a real frame of that exchange.
 */
public class AdbProtocolTest {

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    @Test
    public void checksumIsTheSumOfThePayloadBytes() {
        assertEquals(0, AdbProtocol.checksum(new byte[0]));
        assertEquals(0x06, AdbProtocol.checksum(new byte[]{1, 2, 3}));
        assertEquals(0x126, AdbProtocol.checksum("abc".getBytes(StandardCharsets.UTF_8)));  // 0x61+0x62+0x63 = 294
        // adb writes this value, adbd does not check it; the AUTH frame the server sent had 0, the client's had the real sum.
        assertEquals(0, AdbProtocol.checksum(new byte[]{0, 0, 0}));
    }

    @Test
    public void magicIsCommandXoredWithTheWholeMask() {
        assertEquals(0xb1a7b1bc, AdbProtocol.magic("CNXN"));   // as in the captured CNXN frames
        assertEquals(0xb7abaabe, AdbProtocol.magic("AUTH"));   // as in the captured AUTH frames
    }

    @Test
    public void frameIsHeaderThenPayload() {
        byte[] frame = AdbProtocol.frame("AUTH", 1, 0, "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(27, frame.length);
        assertEquals("AUTH", new String(frame, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(1, AdbProtocol.le32(frame, 4));                       // arg0 = the AUTH type
        assertEquals(0, AdbProtocol.le32(frame, 8));                       // arg1
        assertEquals(3, AdbProtocol.le32(frame, 12));                      // payload length
        assertEquals(0x126, AdbProtocol.le32(frame, 16));                  // checksum
        assertEquals(0xb7abaabe, AdbProtocol.le32(frame, 20));             // magic
        assertEquals("abc", new String(frame, 24, 3, StandardCharsets.UTF_8));
    }

    @Test
    public void theCapturedAuthReplyFromTheTvParsesBack() throws Exception {
        // server: AUTH (1, 0, 20, checksum 0, magic 0xb7abaabe) + the 20-byte token
        String wire = "41555448" + "01000000" + "00000000" + "14000000" + "00000000" + "beaaabb7"
                + "c8ac5a597838e1ffd4c38000dd4c4be3a3e5347a";
        AdbProtocol.Frame frame = AdbProtocol.read(new ByteArrayInputStream(hex(wire)));
        assertEquals("AUTH", frame.command);
        assertEquals(AdbProtocol.AUTH_TOKEN, frame.arg0);
        assertEquals(20, frame.payload.length);
        assertArrayEquals(hex("c8ac5a597838e1ffd4c38000dd4c4be3a3e5347a"), frame.payload);
    }

    @Test
    public void theKeyIsSentInAdbsFrameTypeAndTheSignatureInTheAnswerToTheChallenge() {
        // adb numbers them 1, 2, 3; this adbd answers the key frame with the challenge as type 1, so the key and the
        // challenge share the value, while the signature must go out as 3 — as 2 it is not the answer adb expects.
        assertEquals(1, AdbProtocol.AUTH_FIRST);
        assertEquals(3, AdbProtocol.AUTH_SIGNATURE);
        byte[] keyFrame = AdbProtocol.frame("AUTH", AdbProtocol.AUTH_FIRST, 0,
                "QAAA demOOn@HOMEPC".getBytes(StandardCharsets.UTF_8));
        assertEquals("AUTH", new String(keyFrame, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(1, AdbProtocol.le32(keyFrame, 4));
    }

    @Test
    public void theCapturedCnxnHeaderHasTheVersionAndMaxdataAsArg0AndArg1() throws Exception {
        String header = "434e584e" + "01000001" + "00001000" + "23010000" + "3a720000" + "bcb1a7b1";
        assertEquals(AdbProtocol.VERSION, AdbProtocol.le32(hex(header), 4));
        assertEquals(AdbProtocol.MAX_DATA, AdbProtocol.le32(hex(header), 8));
        assertEquals(291, AdbProtocol.le32(hex(header), 12));
        assertEquals(0x723a, AdbProtocol.le32(hex(header), 16));
        assertEquals(0xb1a7b1bc, AdbProtocol.le32(hex(header), 20));
    }

    @Test
    public void aShortReadIsAnErrorAndNotAPartialFrame() {
        assertThrows(java.io.IOException.class,
                () -> AdbProtocol.read(new ByteArrayInputStream(hex("41555448010000"))));
    }

    @Test
    public void theAppSendsExactlyOneShellCommandOnStreamOne() {
        assertEquals("127.0.0.1", AdbClient.HOST);
        assertEquals(5555, AdbClient.PORT);
        assertEquals(1, AdbClient.LOCAL_ID);
        assertEquals("sh /data/local/tmp/tvhome-bridge-ensure.sh", AdbClient.COMMAND);
        assertEquals(120000, AdbClient.DIALOG_WAIT_MS);
        assertEquals(3, AdbClient.MAX_ATTEMPTS);
        assertEquals(15000, AdbClient.READ_TIMEOUT_MS);
        assertEquals(45000, AdbClient.DEADLINE_MS);

        byte[] open = AdbProtocol.frame("OPEN", AdbClient.LOCAL_ID, 0,
                ("shell:" + AdbClient.COMMAND).getBytes(StandardCharsets.UTF_8));
        assertEquals("OPEN", new String(open, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(48, AdbProtocol.le32(open, 12));      // "shell:" + the command, the same 48 bytes adb sends
        assertEquals("shell:sh /data/local/tmp/tvhome-bridge-ensure.sh", new String(open, 24, 48, StandardCharsets.UTF_8));
    }
}
