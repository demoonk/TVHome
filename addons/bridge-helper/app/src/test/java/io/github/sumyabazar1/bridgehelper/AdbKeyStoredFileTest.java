package io.github.sumyabazar1.bridgehelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import org.junit.Assume;
import org.junit.Test;

/**
 * The stored public key file is only the approved identity when it belongs to the pair that signs the challenge.
 * adbd verifies the signature against the string it recorded, so a file of another pair has to be ignored: the
 * device showed a dialog when an app signing with its own key sent the file of the PC key.
 */
public class AdbKeyStoredFileTest {

    private File write(String name, String content) throws Exception {
        File dir = Files.createTempDirectory("adbkey-test").toFile();
        File file = new File(dir, name);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    public void usesTheFileOfItsOwnPair() throws GeneralSecurityException, Exception {
        AdbKey key = AdbKey.generate(2048);
        File file = write("adbkey.pub", key.keyString() + "\n");
        assertEquals(key.keyString(), key.keyString(file));
    }

    @Test
    public void ignoresAFileOfAnotherPair() throws GeneralSecurityException, Exception {
        AdbKey signing = AdbKey.generate(2048);
        AdbKey other = AdbKey.generate(2048);
        File file = write("adbkey.pub", other.keyString());
        assertEquals(signing.keyString(), signing.keyString(file));
    }

    @Test
    public void ignoresAFileThatIsNotAKeyStruct() throws GeneralSecurityException, Exception {
        AdbKey key = AdbKey.generate(2048);
        assertEquals(key.keyString(), key.keyString(write("approved.key", "\u0000\u001f junk \u007f")));
        assertEquals(key.keyString(), key.keyString(write("adbkey.pub", "   ")));
    }

    /**
     * A placed file the app cannot read is not the key for this run. On the TV this is the shape the problem takes:
     * adb created the file as shell, so the app sees the name but is not allowed to read the content, and the run has
     * to take its own key instead of failing with AccessDeniedException. POSIX permission bits are what the check
     * reads, so this case runs only where they exist; the failure on the device is the same.
     */
    @Test
    public void takesTheOwnKeyWhenThePlacedFileIsNotReadable() throws Exception {
        File placed = write("adbkey", AdbKey.generate(2048).toPem());
        Assume.assumeTrue("needs POSIX permissions and a non-root user",
                Files.getFileStore(placed.toPath()).supportsFileAttributeView(PosixFileAttributeView.class)
                        && !System.getProperty("user.name").equals("root"));
        Files.setPosixFilePermissions(placed.toPath(), PosixFilePermissions.fromString("---------"));
        assertFalse(AdbKey.readableFile(placed));
        AdbKey own = AdbKey.generate(2048);
        assertEquals(own.keyString(), AdbKey.loadOrCreate(placed, write("adbkey.pem", own.toPem())).keyString());
    }

    /** A name in the app's folder that is not a regular file is skipped the same way, on every platform. */
    @Test
    public void ignoresAPlacedNameThatIsNotAFile() throws Exception {
        File dir = Files.createTempDirectory("adbkey-test").toFile();
        File notAFile = new File(dir, "adbkey");
        assertTrue(notAFile.mkdir());
        assertFalse(AdbKey.readableFile(notAFile));
        AdbKey own = AdbKey.generate(2048);
        assertEquals(own.keyString(), AdbKey.loadOrCreate(notAFile, write("adbkey.pem", own.toPem())).keyString());
    }
}
