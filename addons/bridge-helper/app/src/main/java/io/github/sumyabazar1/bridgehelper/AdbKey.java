package io.github.sumyabazar1.bridgehelper;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * The RSA key of this add-on: 2048 bits, generated once on the first run and reused at every boot. The TV stores
 * the key string it receives, so the app has to be self-consistent across runs — the same bytes every time. The
 * private key is kept as PKCS#8 PEM in the app's own files directory, never written outside the app's storage.
 *
 * The public half travels as Android's RSAPublicKey struct (see publicKeyStruct below), base64 with the standard
 * alphabet and no padding. Plain Java, no Android, so src/test can cover the layout on the JVM.
 */
public final class AdbKey {

    public static final int KEY_BITS = 2048;
    public static final int EXPONENT = 65537;

    /** Android's fixed key size: adbd asserts that the word count is exactly this, so the key must be 2048 bits. */
    public static final int MODULUS_SIZE = 256;              // ANDROID_PUBKEY_MODULUS_SIZE
    public static final int MODULUS_SIZE_WORDS = 64;         // ANDROID_PUBKEY_MODULUS_SIZE_WORDS
    private static final BigInteger R32 = BigInteger.valueOf(2).pow(32);

    private final PrivateKey privateKey;
    private final RSAPublicKey publicKey;

    private AdbKey(PrivateKey privateKey, RSAPublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    public static AdbKey generate(int bits) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        KeyPair pair = generator.generateKeyPair();
        return new AdbKey(pair.getPrivate(), (RSAPublicKey) pair.getPublic());
    }

    /** First run generates and writes the PEM; every later boot reads the same file back. */
    public static AdbKey loadOrCreate(File pem) throws GeneralSecurityException, IOException {
        if (pem.isFile()) {
            return fromPem(new String(Files.readAllBytes(pem.toPath()), StandardCharsets.UTF_8));
        }
        AdbKey key = generate(KEY_BITS);
        // Files.writeString is not in the API 26 surface this project compiles against.
        Files.write(pem.toPath(), key.toPem().getBytes(StandardCharsets.UTF_8));
        return key;
    }

    /**
     * A file this app may actually read. A key placed in the app's folder with adb is created by shell: on Android 14
     * the app cannot read a file shell created there, and the run failed with AccessDeniedException instead of using
     * its own key. A placed file therefore counts only when it is readable; a place by adb needs an explicit mode
     * that lets the app read it, and without one the app falls back to the key it wrote itself.
     */
    static boolean readableFile(File file) {
        return file != null && file.isFile() && Files.isReadable(file.toPath());
    }

    /**
     * The key for one run: a PEM the user placed in preferred is used as it is, otherwise the app's own stored key.
     * This matters because adbd only authenticates a key that is already in its list, and the app cannot read or
     * write /data/misc/adb — so a key the TV has already approved for another client can only be used by putting
     * its private half into the app's own folder, where the app is allowed to read it. Nothing is ever copied out
     * of the app's storage, and a key placed by the user is never rewritten.
     */
    public static AdbKey loadOrCreate(File preferred, File own) throws GeneralSecurityException, IOException {
        if (readableFile(preferred)) {
            try {
                return fromPem(new String(Files.readAllBytes(preferred.toPath()), StandardCharsets.UTF_8));
            } catch (java.nio.file.AccessDeniedException e) {
                // The mode can change between the check and the read; the app's own key is the fallback.
            }
        }
        return loadOrCreate(own);
    }

    public static AdbKey fromPem(String pem) throws GeneralSecurityException {
        KeyFactory factory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(decodeBase64(pemBody(pem))));
        BigInteger modulus = ((java.security.interfaces.RSAPrivateKey) privateKey).getModulus();
        RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(new RSAPublicKeySpec(modulus, BigInteger.valueOf(EXPONENT)));
        return new AdbKey(privateKey, publicKey);
    }

    /**
     * The Android RSAPublicKey struct, exactly as adb writes it and as adbd reads it: uint32 word count (64),
     * uint32 n0inv = -1 / n[0] mod 2^32, the modulus as a 256-byte little-endian array, the Montgomery parameter
     * rr = (2^2048)^2 mod n as a 256-byte little-endian array, and uint32 exponent. The base64 of these bytes is
     * the key string the TV stores; a struct without n0inv and rr is not a key adbd can verify, which is what the
     * first runs on the TV showed: the string was remembered, the signature never verified.
     */
    public static byte[] publicKeyStruct(BigInteger modulus, int exponent) {
        BigInteger n0inv = R32.subtract(modulus.mod(R32).modInverse(R32)).mod(R32);
        BigInteger rr = BigInteger.valueOf(2).pow(MODULUS_SIZE * 8).modPow(BigInteger.valueOf(2), modulus);
        byte[] out = new byte[4 + 4 + MODULUS_SIZE + MODULUS_SIZE + 4];
        putLE32(out, 0, MODULUS_SIZE_WORDS);
        putLE32(out, 4, n0inv.longValue());
        byte[] magnitude = modulusBytes(modulus);          // big-endian, up to 256 bytes
        for (int i = 0; i < MODULUS_SIZE; i++) {
            int src = magnitude.length - 1 - i;           // the little-endian array: last byte first
            out[8 + i] = (byte) (src >= 0 ? magnitude[src] : 0);
        }
        byte[] rrBytes = rr.toByteArray();
        for (int i = 0; i < MODULUS_SIZE; i++) {
            int src = rrBytes.length - 1 - i;
            out[8 + MODULUS_SIZE + i] = (byte) (src >= 0 ? rrBytes[src] : 0);
        }
        putLE32(out, out.length - 4, exponent);
        return out;
    }

    /** Positive modulus bytes without the sign byte BigInteger may add. */
    public static byte[] modulusBytes(BigInteger modulus) {
        byte[] bytes = modulus.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    /**
     * The struct as adb writes it: standard base64, standard alphabet, with padding. adbd reads exactly these 524
     * bytes, so the padding is part of the format, not decoration.
     */
    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKeyStruct(publicKey.getModulus(), EXPONENT));
    }

    /**
     * The string the TV stores for this key: the base64 plus a space and a comment, as adb does. The TV remembers
     * the whole string it received and compares the whole string on the next connection, so the comment has to be the
     * same on every run — a stable one for this app, because an app cannot read /data/misc/adb to find out what the
     * TV stored for another client.
     */
    public static final String COMMENT = "bridgehelper@tvhome";

    public String keyString() {
        return publicKeyBase64() + " " + COMMENT;
    }

    /**
     * If the user placed the public key file of this same key pair in the app's folder, its content is exactly the
     * string the TV has already approved for it, and that string is what adbd must be sent: the TV compares the whole
     * received string, comment included, and a different comment means a different key, so the dialog appears again on
     * every connection. The file is only used when it is the public half of the pair this object signs with: a file
     * of another pair sends a string adbd cannot verify against this signature, and the connection is not authorized
     * — which is what a boot on the device showed when the file of the PC key was read by an app signing with its
     * own key. Without such a file, with a file of another pair, or with a file this app cannot read, the app falls back to
      * its own stable string.
     */
    public String keyString(File storedKeyFile) throws IOException {
        if (!readableFile(storedKeyFile)) return keyString();
        String text = new String(Files.readAllBytes(storedKeyFile.toPath()), StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return keyString();
        int space = text.indexOf(' ');
        String base64 = space < 0 ? text : text.substring(0, space);
        byte[] struct;
        try {
            struct = decodeBase64(base64);
        } catch (IllegalArgumentException e) {
            return keyString();
        }
        if (struct.length != 4 + 4 + MODULUS_SIZE + MODULUS_SIZE + 4) return keyString();
        byte[] modulus = new byte[MODULUS_SIZE];
        for (int i = 0; i < MODULUS_SIZE; i++) {
            modulus[i] = struct[8 + MODULUS_SIZE - 1 - i];   // the array is little-endian
        }
        if (new BigInteger(1, modulus).equals(publicKey.getModulus())) return text;
        return keyString();
    }

    public static String base64NoPadding(byte[] bytes) {
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] decodeBase64(String text) {
        return Base64.getDecoder().decode(text);
    }

    /** SHA1withRSA (PKCS#1 v1.5) over the token, what adbd checks. */
    public byte[] sign(byte[] token) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA1withRSA");
        signature.initSign(privateKey);
        signature.update(token);
        return signature.sign();
    }

    public String toPem() {
        String body = Base64.getEncoder().encodeToString(privateKey.getEncoded()); // PKCS#8
        StringBuilder pem = new StringBuilder("-----BEGIN PRIVATE KEY-----\n");
        for (int i = 0; i < body.length(); i += 64) {
            pem.append(body, i, Math.min(i + 64, body.length())).append('\n');
        }
        return pem.append("-----END PRIVATE KEY-----\n").toString();
    }

    public RSAPublicKey getPublicKey() {
        return publicKey;
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    private static String pemBody(String pem) {
        StringBuilder body = new StringBuilder();
        for (String line : pem.split("\\R")) {
            if (line.isEmpty() || line.startsWith("-----")) continue;
            body.append(line.trim());
        }
        return body.toString();
    }

    private static void putLE32(byte[] out, int offset, long value) {
        out[offset] = (byte) (value & 0xFF);
        out[offset + 1] = (byte) ((value >> 8) & 0xFF);
        out[offset + 2] = (byte) ((value >> 16) & 0xFF);
        out[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
