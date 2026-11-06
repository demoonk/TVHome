package ru.pda.tvhome;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Android-independent validation for release metadata and every download redirect. */
final class UpdateRules {
    static String repository(String value) {
        if (value == null) return "";
        value = value.trim();
        if (!value.matches("https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/?")) return "";
        String result = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        for (String part : result.substring("https://github.com/".length()).split("/"))
            if (part.equals(".") || part.equals("..")) return "";
        return result;
    }

    static boolean downloadUrl(String address) {
        if (address == null) return false;
        try {
            URI uri = new URI(address);
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getHost() == null) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return host.equals("github.com") || host.equals("api.github.com")
                    || host.endsWith(".githubusercontent.com");
        } catch (URISyntaxException invalid) { return false; }
    }

    static boolean apkName(String name) {
        return name != null && name.matches("TVHome-[A-Za-z0-9._-]+\\.apk");
    }
    static boolean sha256(String hash) {
        return hash != null && hash.matches("[A-Fa-f0-9]{64}");
    }
    private UpdateRules() {}
}
