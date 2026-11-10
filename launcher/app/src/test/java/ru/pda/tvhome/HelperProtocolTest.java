package ru.pda.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HelperProtocolTest {

    @Test public void aRequestWithoutAnArgIsModeColonUuid() {
        assertEquals("check:uuid-1", HelperProtocol.format("check", "", "uuid-1"));
        assertEquals("grant:uuid-2", HelperProtocol.format("grant", "", "uuid-2"));
    }

    @Test public void theArgSitsBetweenTheModeAndTheUuid() {
        assertEquals("freeze:com.homwee.help:uuid-3", HelperProtocol.format("freeze", "com.homwee.help", "uuid-3"));
    }

    @Test public void theShellPartsReadBackTheSameValues() {
        String request = HelperProtocol.format("unfreeze", "com.gdch.gameoptimiser", "uuid-4");
        assertEquals("unfreeze", HelperProtocol.mode(request));
        assertEquals("com.gdch.gameoptimiser", HelperProtocol.packageOf(request));
    }

    @Test public void aProtectedRequestStillParsesLikeTheShellReadsIt() {
        assertEquals("freeze", HelperProtocol.mode("freeze:com.google.android.gms:uuid-5"));
        assertEquals("com.google.android.gms", HelperProtocol.packageOf("freeze:com.google.android.gms:uuid-5"));
        assertEquals("com.google.android.gms", HelperProtocol.packageOf("freeze:com.google.android.gms"));
    }
}
