package io.github.sumyabazar1.bridgehelper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The adb message framing adbd accepts on this TV. A message is a 24-byte header and an optional payload:
 * command (4 ASCII chars), arg0, arg1, payload length, checksum, magic — every integer int32 little-endian, and
 * magic is command XOR 0xFFFFFFFF. The checksum is the sum of the payload bytes; adbd does not check it, but adb
 * writes it, so the app writes the same.
 *
 * This is what a captured exchange with the TV proved: the old text protocol (4 hex digits then the body) is not
 * accepted by this adbd — with it the connection is closed immediately or stays silent, while the binary framing
 * gets the AUTH challenge and the shell service opens.
 *
 * Plain Java, no Android: the boot receiver only calls AdbClient, so src/test can cover this on the JVM.
 */
public final class AdbProtocol {

    public static final int VERSION = 0x01000001;
    public static final int MAX_DATA = 0x100000;

    /**
     * The AUTH types. adb numbers them 1, 2, 3: the client's key string goes out as AUTH_FIRST, the server answers
     * with a 20-byte token, the signature back is AUTH_SIGNATURE. On this TV the token arrives as type 1, so the key
     * and the token share that value. What matters is that the key is sent in the frame adbd records it from: sent as
     * type 3, adbd reads it as a signature for a key it never recorded, answers "Invalid base64 key" with an empty
     * key, and asks again — that is the repeated authorization dialog.
     */
    public static final int AUTH_FIRST = 1;      // the frame adbd records the key from
    public static final int AUTH_TOKEN = 1;      // the same value: this adbd sends the challenge as type 1
    public static final int AUTH_SIGNATURE = 3;  // as adb numbers the answer to the challenge

    private AdbProtocol() {}

    /** The sum of the payload bytes, as adb writes it. */
    public static int checksum(byte[] payload) {
        long sum = 0;
        for (byte b : payload) sum += (b & 0xFF);
        return (int) (sum & 0xFFFFFFFFL);
    }

    /** command XOR 0xFFFFFFFF, read as the little-endian int32 adb uses. */
    public static int magic(String command) {
        return (int) ((long) le32(command.getBytes(StandardCharsets.US_ASCII), 0) ^ 0xFFFFFFFFL);
    }

    /** The whole wire message: 24-byte header followed by the payload. */
    public static byte[] frame(String command, int arg0, int arg1, byte[] payload) {
        byte[] out = new byte[24 + payload.length];
        byte[] name = command.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, out, 0, 4);
        putLE32(out, 4, arg0);
        putLE32(out, 8, arg1);
        putLE32(out, 12, payload.length);
        putLE32(out, 16, checksum(payload));
        putLE32(out, 20, magic(command));
        System.arraycopy(payload, 0, out, 24, payload.length);
        return out;
    }

    /** One message from adbd. The header must be complete; a short read is an error, not a partial frame. */
    public static Frame read(InputStream in) throws IOException {
        byte[] header = readExactly(in, 24);
        String command = new String(header, 0, 4, StandardCharsets.US_ASCII);
        int arg0 = le32(header, 4);
        int arg1 = le32(header, 8);
        int length = le32(header, 12);
        byte[] payload = length == 0 ? new byte[0] : readExactly(in, length);
        return new Frame(command, arg0, arg1, payload);
    }

    public static int le32(byte[] bytes, int offset) {
        return (int) ((bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24));
    }

    private static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] bytes = new byte[n];
        int read = 0;
        while (read < n) {
            int k = in.read(bytes, read, n - read);
            if (k < 0) throw new IOException("adbd closed the connection before a full frame");
            read += k;
        }
        return bytes;
    }

    private static void putLE32(byte[] out, int offset, int value) {
        out[offset] = (byte) (value & 0xFF);
        out[offset + 1] = (byte) ((value >> 8) & 0xFF);
        out[offset + 2] = (byte) ((value >> 16) & 0xFF);
        out[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    /** A parsed message. arg0 means the AUTH type for AUTH, the local stream id for OPEN, OKAY and WRTE. */
    public static final class Frame {
        public final String command;
        public final int arg0;
        public final int arg1;
        public final byte[] payload;

        Frame(String command, int arg0, int arg1, byte[] payload) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.payload = payload;
        }

        public String payloadText() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }
}
