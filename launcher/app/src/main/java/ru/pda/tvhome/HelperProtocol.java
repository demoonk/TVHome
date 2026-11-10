package ru.pda.tvhome;

/** The request format shared by the launcher and the adb helper: mode, then an optional package, then the uuid. */
final class HelperProtocol {
    private HelperProtocol() {}

    static String format(String mode, String arg, String uuid) {
        return arg.isEmpty() ? mode + ":" + uuid : mode + ":" + arg + ":" + uuid;
    }

    /** The shell reads the mode as the part before the first colon. */
    static String mode(String request) {
        int colon = request.indexOf(':');
        return colon < 0 ? request : request.substring(0, colon);
    }

    /** The package name is the first part of everything after the mode, as the shell takes it. */
    static String packageOf(String request) {
        int start = request.indexOf(':') + 1;
        int colon = request.indexOf(':', start);
        return colon < 0 ? request.substring(start) : request.substring(start, colon);
    }
}
