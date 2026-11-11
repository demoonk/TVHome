package io.github.sumyabazar1.bridgehelper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * The minimum ADB client an ordinary app is allowed to be: connect to adbd on the TV's loopback (`127.0.0.1`) at
 * port 5555, authenticate with the app's key, run exactly one command, read the output for a bounded time, and
 * finish. Commands sent through adbd run as uid 2000 shell, which is the only domain that reads /dev/input and can
 * start the bridge again — that is why this app exists.
 *
 * The order the frames go out in is the standard one, and the order this adbd answers:
 *   1. CNXN to register the connection.
 *   2. adbd answers with the challenge: AUTH type 1 and a 20-byte token.
 *   3. The client signs that token and sends the signature as AUTH type 2.
 *   4. If adbd knows this key (the signature verifies against /data/misc/adb/adb_keys), it answers CNXN and the
 *      connection is authorized without any dialog. If not, it sends a new challenge; the client then sends its
 *      key string as AUTH type 3, which is the frame the TV's AdbDebuggingManager reads, records and shows in the
 *      dialog. The answer to the dialog comes back on this same connection, so the client keeps reading.
 *   5. Exactly one shell service: OPEN with a non-zero local id, then OKAY, then WRTE until CLOSE.
 *
 * The key string is what adbd compares with the keys it has already recorded. The TV keeps that string exactly as it
 * received it, comment included, and an app cannot read /data/misc/adb to learn it — so when the user places the
 * public key file of the same key pair in the app's folder, the app sends its content as it is: that is the string
 * the TV approved. Without such a file the app sends its own base64 plus a stable comment.
 *
 * One thing cannot be done from an app: adbd serves a single client at a time, so while the adb daemon on the PC
 * is connected this connection is refused. No retries beyond a few attempts, no forever loop.
 */
public final class AdbClient {

    /**
     * Loopback, as adb clients normally reach adbd: the app runs on the TV itself, so the address it has to reach is
     * the TV's own loopback. Checked on this TV: the connect completes only when adbd is not serving another client —
     * while the adb client on the PC is connected this connect hangs until the connect timeout and completes only
     * when adbd becomes idle.
     */
    public static final String LOOPBACK = "127.0.0.1";

    /** The old name is kept so the checks written against it still read the same address. */
    public static final String HOST = LOOPBACK;

    public static final int PORT = 5555;

    /** The idempotent script already on the TV (tools/tvhome-bridge-ensure.sh); this app pushes nothing itself. */
    public static final String COMMAND = "sh /data/local/tmp/tvhome-bridge-ensure.sh";

    /** The stream id this client uses for its single shell stream; adbd requires a non-zero local id. */
    public static final int LOCAL_ID = 1;

    /**
     * At most this many connections per run, and only for a connection that failed before the key went out.
     * The TV answers every connection without a known key with the authorization dialog, so each extra connection
     * here is one more prompt on the screen: a key that was already sent and not accepted must not be sent again.
     */
    public static final int MAX_ATTEMPTS = 3;

    /** True only for a failure before the key string: nothing was asked of the user, so a retry asks nothing. */
    static boolean shouldRetry(Status status, boolean keyWasSent) {
        return status == Status.IO_ERROR && !keyWasSent;
    }

    /** Time to wait for the answer to the key: the dialog the user has to answer, or the reply for an approved key. */
    public static final long DIALOG_WAIT_MS = 120000;

    public static final long READ_TIMEOUT_MS = 15000;
    public static final long DEADLINE_MS = 45000;

    /**
     * The handshake of this TV is slow to start: the connection to adbd is established, the key is accepted, but the
     * reply comes only after the connection has already been closed if the connect timeout is short. So the connect
     * is given more time than a plain read.
     */
    public static final long CONNECT_TIMEOUT_MS = 30000;

    public enum Status { OK, KEY_NOT_AUTHORIZED, NO_ANSWER, IO_ERROR }

    public static final class Result {
        public final Status status;
        public final String message;
        public final String output;

        Result(Status status, String message, String output) {
            this.status = status;
            this.message = message;
            this.output = output;
        }
    }

    private final AdbKey key;
    private final File storedKeyFile;
    private final long dialogWaitMs;
    private final long readTimeoutMs;
    private final long deadlineMs;
    private final String host;
    private final int port;
    /** Every frame is reported here as it happens, so a run can be followed live in logcat, not only at its end. */
    private final java.util.function.Consumer<String> sink;

    public AdbClient(AdbKey key) {
        this(key, null, DIALOG_WAIT_MS, READ_TIMEOUT_MS, DEADLINE_MS, null);
    }

    public AdbClient(AdbKey key, File storedKeyFile) {
        this(key, storedKeyFile, DIALOG_WAIT_MS, READ_TIMEOUT_MS, DEADLINE_MS, null);
    }

    public AdbClient(AdbKey key, File storedKeyFile, long dialogWaitMs, long readTimeoutMs, long deadlineMs) {
        this(key, storedKeyFile, dialogWaitMs, readTimeoutMs, deadlineMs, null);
    }

    public AdbClient(AdbKey key, File storedKeyFile, long dialogWaitMs, long readTimeoutMs, long deadlineMs,
                     java.util.function.Consumer<String> sink) {
        this(key, storedKeyFile, dialogWaitMs, readTimeoutMs, deadlineMs, sink, HOST, PORT);
    }

    /** The address is a parameter so a src/test JVM can answer as adbd on an ephemeral port. */
    AdbClient(AdbKey key, File storedKeyFile, long dialogWaitMs, long readTimeoutMs, long deadlineMs,
              java.util.function.Consumer<String> sink, String host, int port) {
        this.key = key;
        this.storedKeyFile = storedKeyFile;
        this.dialogWaitMs = dialogWaitMs;
        this.readTimeoutMs = readTimeoutMs;
        this.deadlineMs = deadlineMs;
        this.sink = sink;
        this.host = host;
        this.port = port;
    }

    /**
     * adbd serves one client at a time, so a connection can be refused while the adb daemon on the PC is still
     * connected. A few short retries are worth it; a refusal of the key is not retried, because that means the dialog
     * on the TV has to be answered first.
     */
    public static final long RETRY_PAUSE_MS = 2000;

    /** Whether the last attempt got as far as sending the key string. */
    private boolean keySent;

    public Result run() {
        StringBuilder trace = new StringBuilder();
        Result last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            keySent = false;
            report(trace, "attempt " + attempt + " via " + host + ":" + port);
            last = attempt(trace, host);
            if (last.status == Status.OK) return last;
            if (!shouldRetry(last.status, keySent)) return last;
            if (attempt == MAX_ATTEMPTS) return last;
            report(trace, "attempt " + attempt + " ended with " + last.status
                    + " before the key was sent, retrying");
            try {
                Thread.sleep(RETRY_PAUSE_MS);
            } catch (InterruptedException e) {
                return last;
            }
        }
        return last;
    }

    /**
     * One attempt, in the order adb itself uses: CNXN, then the challenge (AUTH type 1, 20 bytes) answered with the
     * signature over it (AUTH type 2). If that signature is not accepted — the key is not in adbd's list yet — adbd
     * sends a new challenge, and the key string goes out in the frame adbd records it from (AUTH type 3), which is
     * what makes the TV show the authorization dialog. The answer to the dialog comes back on this same connection,
     * so the client keeps reading until CNXN. Sending the key before the signature, or the signature as type 3, is
     * not a variant this adbd supports: it reads the signature as a public key and the real key is never authorized.
     */
    private Result attempt(StringBuilder trace, String host) {
        long started = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) CONNECT_TIMEOUT_MS);
            socket.setSoTimeout((int) dialogWaitMs);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            out.write(AdbProtocol.frame("CNXN", AdbProtocol.VERSION, AdbProtocol.MAX_DATA,
                    "host::".getBytes(StandardCharsets.UTF_8)));
            out.flush();
            report(trace, "sent CNXN");

            boolean authorized = false;
            boolean signatureSent = false;
            long until = System.currentTimeMillis() + dialogWaitMs;
            while (!authorized && System.currentTimeMillis() < until) {
                AdbProtocol.Frame frame;
                try {
                    frame = AdbProtocol.read(in);
                } catch (SocketTimeoutException e) {
                    report(trace, "read timed out, nothing back");
                    break;
                }
                report(trace, "read " + describe(frame));
                if (frame.command.equals("CNXN")) {
                    // This adbd answers an accepted key with the plain handshake (handle_online + send_connect),
                    // not with AUTH type 0: the log line "adb client authorized" is followed by this CNXN.
                    authorized = true;
                } else if (frame.command.equals("AUTH") && frame.arg0 == 0) {
                    authorized = true;
                } else if (frame.command.equals("AUTH")
                        && frame.arg0 == AdbProtocol.AUTH_TOKEN
                        && frame.payload.length == 20) {
                    if (!signatureSent) {
                        byte[] signature = key.sign(frame.payload);
                        report(trace, "token " + hex(frame.payload));
                        report(trace, "signature " + hex(signature));
                        out.write(AdbProtocol.frame("AUTH", AdbProtocol.AUTH_SIGNATURE, 0, signature));
                        out.flush();
                        signatureSent = true;
                        report(trace, "sent AUTH type 2, signature over the challenge");
                    } else if (!keySent) {
                        // The signature was not accepted: the key is not in adbd's list yet. This frame is the one
                        // adbd records and shows in the dialog; after Allow the connection is authorized as it is.
                        String keyString = key.keyString(storedKeyFile);
                        out.write(AdbProtocol.frame("AUTH", AdbProtocol.AUTH_RSAPUBLICKEY, 0,
                                keyString.getBytes(StandardCharsets.UTF_8)));
                        out.flush();
                        keySent = true;
                        report(trace, "sent AUTH type 3, the key string: " + keyString);
                    } else {
                        report(trace, "adbd asked again after the key was sent; it was not accepted");
                        return new Result(Status.KEY_NOT_AUTHORIZED,
                                "adbd asked for the key again after it was sent; the dialog was denied or the key is "
                                        + "not accepted. " + trace, "");
                    }
                }
                // Anything else is ignored, so the answer to the dialog on the TV can still come on this connection.
            }

            if (!authorized) {
                return new Result(Status.KEY_NOT_AUTHORIZED,
                        "adbd did not authorize the connection. If the key is not approved yet, the dialog has to be "
                                + "answered on the TV: approve the key once, then run this again; from then on, "
                                + "including at boot, the connection is authorized without a dialog. " + trace, "");
            }

            socket.setSoTimeout((int) readTimeoutMs);
            out.write(AdbProtocol.frame("OPEN", LOCAL_ID, 0, ("shell:" + COMMAND).getBytes(StandardCharsets.UTF_8)));
            out.flush();

            AdbProtocol.Frame opened;
            try {
                opened = AdbProtocol.read(in);
            } catch (SocketTimeoutException e) {
                return new Result(Status.NO_ANSWER,
                        "the shell service did not answer after the authorization. " + trace, "");
            }
            report(trace, "read " + describe(opened));
            if (!opened.command.equals("OKAY")) {
                return new Result(Status.IO_ERROR,
                        "the shell service was not opened, the reply was " + opened.command + " " + opened.payloadText(), "");
            }

            StringBuilder output = new StringBuilder();
            while (System.currentTimeMillis() - started < deadlineMs) {
                try {
                    AdbProtocol.Frame chunk = AdbProtocol.read(in);
                    // adb's close command is the four-character CLSE (a command field is exactly four ASCII chars).
                    if (chunk.command.equals("CLSE")) break;
                    if (chunk.command.equals("WRTE")) output.append(chunk.payloadText());
                } catch (SocketTimeoutException e) {
                    return new Result(Status.OK, "read until the deadline without a CLOSE, the output may be partial", output.toString());
                }
            }
            return new Result(Status.OK, "the command ran as shell, output follows", output.toString());
        } catch (IOException e) {
            return new Result(Status.IO_ERROR, "the adbd connection failed: " + e.getMessage() + " " + trace, "");
        } catch (GeneralSecurityException e) {
            return new Result(Status.IO_ERROR, "signing the token failed: " + e.getMessage() + " " + trace, "");
        }
    }

    private static String describe(AdbProtocol.Frame frame) {
        return frame.command + " arg0 " + frame.arg0 + " length " + frame.payload.length;
    }

    /** Records a step and, when a sink is given, reports it right away so the run can be watched in logcat. */
    private void report(StringBuilder trace, String line) {
        trace.append(line).append('\n');
        if (sink != null) sink.accept(line);
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
