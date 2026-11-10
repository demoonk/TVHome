package io.github.sumyabazar1.bridgehelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;

import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;

import org.junit.Test;

/**
 * The key layout as Android reads it (ANDROID_PUBKEY_MODULUS_SIZE_WORDS = 64, n0inv, the modulus as a
 * little-endian array, the Montgomery parameter rr, the exponent) — verified against adb's own key file on this
 * machine, where the base64 decodes to exactly these five fields. The TV remembers the string it receives, so
 * these bytes are the contract.
 */
public class AdbKeyTest {

    private static byte[] slice(byte[] bytes, int offset, int length) {
        return Arrays.copyOfRange(bytes, offset, offset + length);
    }

    private static byte[] reversed(byte[] bytes, int offset, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) out[i] = bytes[offset + length - 1 - i];
        return out;
    }

    @Test
    public void structIsWordCountN0invModulusRrAndExponent() throws Exception {
        AdbKey key = AdbKey.generate(2048);
        byte[] out = AdbKey.publicKeyStruct(key.getPublicKey().getModulus(), AdbKey.EXPONENT);
        assertEquals(524, out.length);                 // 4 + 4 + 256 + 256 + 4
        assertEquals(AdbKey.MODULUS_SIZE_WORDS, AdbProtocol.le32(out, 0));   // adbd asserts this is 64
        assertEquals(AdbKey.MODULUS_SIZE, AdbKey.MODULUS_SIZE_WORDS * 4);

        BigInteger modulus = new BigInteger(1, reversed(out, 8, AdbKey.MODULUS_SIZE));  // little-endian array
        assertEquals(key.getPublicKey().getModulus(), modulus);

        BigInteger r32 = BigInteger.valueOf(2).pow(32);
        BigInteger n0inv = new BigInteger(1, reversed(out, 4, 4));
        assertEquals(r32.subtract(modulus.mod(r32).modInverse(r32)).mod(r32), n0inv);    // -1 / n[0] mod 2^32

        BigInteger rr = new BigInteger(1, reversed(out, 8 + AdbKey.MODULUS_SIZE, AdbKey.MODULUS_SIZE));
        assertEquals(BigInteger.valueOf(2).pow(AdbKey.MODULUS_SIZE * 8).modPow(BigInteger.valueOf(2), modulus), rr);

        assertEquals(AdbKey.EXPONENT, AdbProtocol.le32(out, out.length - 4));
    }

    @Test
    public void aKeyOfAnotherSizeIsNotAKeyAdbdCanRead() {
        // ANDROID_PUBKEY_MODULUS_SIZE_WORDS is fixed at 64, so a 4096-bit key is rejected; the app must stay at 2048.
        assertEquals(64, AdbKey.MODULUS_SIZE_WORDS);
        assertEquals(2048, AdbKey.KEY_BITS);
    }

    @Test
    public void base64IsStandardAlphabetWithoutPaddingAndRoundTrips() {
        byte[] data = new byte[524];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        String encoded = AdbKey.base64NoPadding(data);
        assertEquals(false, encoded.indexOf('=') >= 0);            // the key file convention: no padding
        assertArrayEquals(data, AdbKey.decodeBase64(encoded));
        assertArrayEquals(data, Base64.getDecoder().decode(encoded)); // decodes back on the TV side
        assertEquals("AQ", AdbKey.base64NoPadding(new byte[]{1}));  // 1 byte: two chars, no padding
    }

    @Test
    public void keySurvivesRebootsThroughItsPemFile() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "bridge-helper-test-" + System.nanoTime());
        dir.mkdirs();
        File pem = new File(dir, "adbkey.pem");
        AdbKey firstRun = AdbKey.loadOrCreate(pem);   // generates and writes here
        AdbKey nextBoot = AdbKey.loadOrCreate(pem);   // reads the same file back
        assertEquals(firstRun.publicKeyBase64(), nextBoot.publicKeyBase64()); // the TV remembers this exact string
        assertEquals("-----BEGIN PRIVATE KEY-----", firstRun.toPem().split("\\R")[0]);
        assertEquals(firstRun.toPem(), nextBoot.toPem()); // PKCS#8 PEM in the app's own files directory
    }

    @Test
    public void keyStringIsTheFormatTheTvStores() throws Exception {
        AdbKey key = AdbKey.generate(2048);
        String line = key.keyString();                    // what adbd compares with the string it stored
        String[] parts = line.split(" ");
        assertEquals(2, parts.length);                    // base64, a space, the comment
        assertEquals(524, AdbKey.decodeBase64(parts[0]).length);  // adbd reads exactly this many bytes
        assertEquals(AdbKey.COMMENT, parts[1]);           // the same comment every run, so the string matches
    }

    @Test
    public void aPlacedPublicKeyFileIsSentExactlyAsTheTvStoredIt() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "bridge-helper-test-" + System.nanoTime());
        dir.mkdirs();
        AdbKey key = AdbKey.generate(2048);
        File pub = new File(dir, "adbkey.pub");
        // adb writes this file as base64 + a space + the comment of the machine that created the key.
        Files.write(pub.toPath(), (key.publicKeyBase64() + " demOOn@HOMEPC").getBytes("UTF-8"));
        assertEquals(key.publicKeyBase64() + " demOOn@HOMEPC", key.keyString(pub));
        assertEquals(key.keyString(), key.keyString(new File(dir, "absent.pub")));  // no file, the app's own comment
    }

    @Test
    public void aKeyPlacedByTheUserIsUsedAsItIs() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "bridge-helper-test-" + System.nanoTime());
        dir.mkdirs();
        AdbKey placed = AdbKey.generate(2048);
        File imported = new File(dir, "adbkey");
        Files.write(imported.toPath(), placed.toPem().getBytes(StandardCharsets.UTF_8));
        File own = new File(dir, "adbkey.pem");

        AdbKey used = AdbKey.loadOrCreate(imported, own);
        assertEquals(placed.publicKeyBase64(), used.publicKeyBase64());  // the string the TV already approved
        assertFalse(own.exists());                                        // nothing is generated or rewritten
    }

    @Test
    public void signIsSha1WithRsaOverTheToken() throws Exception {
        AdbKey key = AdbKey.generate(2048);
        byte[] token = "AUTH1token123".getBytes(StandardCharsets.UTF_8);
        byte[] signature = key.sign(token);
        assertEquals(256, signature.length);
        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(key.getPublicKey());
        verifier.update(token);
        assertEquals(true, verifier.verify(signature));
    }
}
