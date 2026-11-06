package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateRulesTest {
    @Test public void acceptsRepositoryAndRemovesTrailingSlash() {
        assertEquals("https://github.com/owner/TVHome", UpdateRules.repository(" https://github.com/owner/TVHome/ "));
    }
    @Test public void rejectsInvalidRepositories() {
        for (String value : new String[]{null, "", "http://github.com/a/b", "https://github.com.evil/a/b",
                "https://github.com@evil.com/a/b", "https://user@github.com/a/b", "https://github.com:444/a/b",
                "https://github.com/a/b?x=1", "https://github.com/a/b/releases", "https://github.com/a/.."})
            assertEquals("Rejected repository: " + value, "", UpdateRules.repository(value));
    }
    @Test public void acceptsGitHubDownloads() {
        for (String value : new String[]{"https://github.com/a/b/releases/download/v1/TVHome-1.apk",
                "https://api.github.com/repos/a/b/releases/latest", "https://release-assets.githubusercontent.com/a",
                "https://objects.githubusercontent.com:443/a", "https://raw.githubusercontent.com/a/b/main/update.json"})
            assertTrue(value, UpdateRules.downloadUrl(value));
    }
    @Test public void rejectsUntrustedDownloads() {
        for (String value : new String[]{null, "", "http://github.com/a", "https://github.com.evil/a",
                "https://evilgithubusercontent.com/a", "https://objects.githubusercontent.com.evil/a",
                "https://github.com@evil.com/a", "https://user@github.com/a", "https://github.com:444/a",
                "https://github.com:80/a", "https://github.com:garbage/a", "https://githubusercontent.com/a",
                "https://github.com/a b", "//github.com/a"})
            assertFalse("Rejected download: " + value, UpdateRules.downloadUrl(value));
    }
    @Test public void validatesApkNames() {
        assertTrue(UpdateRules.apkName("TVHome-1.2.3-release_1.apk"));
        for (String value : new String[]{null, "", "TVHome-.apk", "other.apk", "../TVHome-1.apk",
                "TVHome-1.apk/evil", "TVHome-1.APK", "TVHome-1 apk", "TVHome-1.apk\n"})
            assertFalse("Rejected APK: " + value, UpdateRules.apkName(value));
    }
    @Test public void validatesSha256() {
        String valid = "0123456789abcdef0123456789ABCDEF0123456789abcdef0123456789ABCDEF";
        assertTrue(UpdateRules.sha256(valid));
        for (String value : new String[]{null, "", valid.substring(1), valid + "0", "g" + valid.substring(1), valid + "\n"})
            assertFalse("Rejected hash: " + value, UpdateRules.sha256(value));
    }
}
