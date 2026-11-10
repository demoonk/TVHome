package io.github.sumyabazar1.bridgehelper;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RetryPolicyTest {

    @Test public void onlyAFailureBeforeTheKeyIsSentIsRetried(){
        assertTrue(AdbClient.shouldRetry(AdbClient.Status.IO_ERROR, false));
        // The dialog was already shown for this connection: another connection is another prompt.
        assertFalse(AdbClient.shouldRetry(AdbClient.Status.IO_ERROR, true));
        assertFalse(AdbClient.shouldRetry(AdbClient.Status.KEY_NOT_AUTHORIZED, true));
        assertFalse(AdbClient.shouldRetry(AdbClient.Status.NO_ANSWER, true));
        assertFalse(AdbClient.shouldRetry(AdbClient.Status.OK, true));
    }

    @Test public void theStoredKeyFileIsReadFromEitherFolder(){
        // A fresh folder each run: the external folder is checked first, so a file left from an earlier run would
        // answer for the wrong one.
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "bridge-helper-test-" + System.nanoTime());
        java.io.File own = new java.io.File(dir, "own");
        dir.mkdirs(); own.mkdirs();
        java.io.File approved = new java.io.File(own, "approved.key");
        try {
            java.nio.file.Files.write(approved.toPath(), "QAAA demOOn@HOMRPC\n".getBytes());
            assertEquals(approved, EnsureReceiver.storedKeyFile(dir, own));
            java.io.File pub = new java.io.File(dir, "adbkey.pub");
            java.nio.file.Files.write(pub.toPath(), "QAAA demOOn@HOMRPC\n".getBytes());
            assertEquals(pub, EnsureReceiver.storedKeyFile(dir, own));
            assertNull(EnsureReceiver.storedKeyFile(new java.io.File(dir, "nothing"), new java.io.File(own, "nothing")));
        } catch (java.io.IOException e) {
            fail(e.toString());
        } finally {
            own.delete(); dir.delete();
        }
    }
}
