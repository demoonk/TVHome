package io.github.sumyabazar1.bridgehelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * The auth conversation against a fake adbd on an ephemeral loopback port, in the order the TV expects:
 * CNXN, challenge (AUTH type 1), signature (AUTH type 2), a second challenge because the key is not in the list,
 * the key string (AUTH type 3), then CNXN and the single shell command. This is the conversation that failed
 * before 1.7: the key went out as type 1 and the signature as type 3, so adbd prompted with the signature and never
 * authorized the real key.
 */
public class AdbClientAuthTest {

    private static final byte[] TOKEN = new byte[]{
            0x48, 0x45, 0x4c, 0x4c, 0x4f, 0x21, 0x01, 0x02, 0x03, 0x04,
            0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e};

    @Test
    public void signsTheChallengeAndSendsTheKeyOnlyWhenAdbdAsksAgain() throws Exception {
        final AdbKey key = AdbKey.generate(2048);
        final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        final AdbProtocol.Frame[] seen = new AdbProtocol.Frame[4];
        final String[] openService = new String[1];

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread adbd = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();

                    seen[0] = AdbProtocol.read(in);
                    send(out, AdbProtocol.frame("AUTH", AdbProtocol.AUTH_TOKEN, 0, TOKEN));

                    seen[1] = AdbProtocol.read(in);
                    send(out, AdbProtocol.frame("AUTH", AdbProtocol.AUTH_TOKEN, 0, TOKEN));

                    seen[2] = AdbProtocol.read(in);
                    send(out, AdbProtocol.frame("CNXN", AdbProtocol.VERSION, AdbProtocol.MAX_DATA,
                            "device::ro.product.name=test;".getBytes(StandardCharsets.UTF_8)));

                    seen[3] = AdbProtocol.read(in);
                    openService[0] = seen[3].payloadText();
                    send(out, AdbProtocol.frame("OKAY", 1, 1, new byte[0]));
                    send(out, AdbProtocol.frame("WRTE", 1, 1, "ran\n".getBytes(StandardCharsets.UTF_8)));
                    send(out, AdbProtocol.frame("CLSE", 1, 1, new byte[0]));
                } catch (SocketTimeoutException e) {
                    serverFailure.set(e);
                } catch (Throwable e) {
                    serverFailure.set(e);
                }
            }, "fake-adbd");
            adbd.setDaemon(true);
            adbd.start();

            AdbClient.Result result = new AdbClient(key, null, 10000, 10000, 10000, null,
                    "127.0.0.1", server.getLocalPort()).run();
            adbd.join(10000);

            assertNull(String.valueOf(serverFailure.get()), serverFailure.get());
            assertEquals(result.message, AdbClient.Status.OK, result.status);
            assertEquals("ran\n", result.output);
            assertNull(serverFailure.get());

            assertEquals("CNXN", seen[0].command);
            assertEquals("AUTH", seen[1].command);
            assertEquals(AdbProtocol.AUTH_SIGNATURE, seen[1].arg0);
            assertEquals(256, seen[1].payload.length);
            assertTrue(verify(key, TOKEN, seen[1].payload));

            assertEquals("AUTH", seen[2].command);
            assertEquals(AdbProtocol.AUTH_RSAPUBLICKEY, seen[2].arg0);
            assertEquals(key.keyString(), seen[2].payloadText());

            assertEquals("OPEN", seen[3].command);
            assertEquals(AdbClient.LOCAL_ID, seen[3].arg0);
            assertEquals("shell:" + AdbClient.COMMAND, openService[0]);
        }
    }

    private static void send(OutputStream out, byte[] frame) throws java.io.IOException {
        out.write(frame);
        out.flush();
    }

    private static boolean verify(AdbKey key, byte[] token, byte[] signature) throws Exception {
        Signature verifier = Signature.getInstance("SHA1withRSA");
        verifier.initVerify(key.getPublicKey());
        verifier.update(token);
        return verifier.verify(signature);
    }
}
