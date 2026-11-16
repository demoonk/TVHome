package ru.pda.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.HashSet;
import java.util.Set;

public final class FreezableAppsTest {
    @Test public void listKeepsOnlyPackagesThatAreSafeToFreeze(){
        for(FreezableApps.Item item:FreezableApps.list()){
            assertTrue(item.pkg(),FreezableApps.safe(item.pkg(),"ru.pda.tvhome"));
            assertFalse(item.pkg()+" has no reason",item.what().isEmpty());
        }
    }
    @Test public void theNamedPackagesAreInTheList(){
        Set<String> packages=new HashSet<>();
        for(FreezableApps.Item item:FreezableApps.list())packages.add(item.pkg());
        assertTrue(packages.contains("com.mediatek.wwtv.setupwizard"));
        assertTrue(packages.contains("com.google.android.katniss"));
        // The recovery stub is in the list because the Home key goes to it while it is enabled.
        assertTrue(packages.contains("com.google.android.tungsten.setupwraith"));
        // The stock shell is in the list: its gear menu doubled the launcher settings on the living-room TV.
        assertTrue(packages.contains("com.mediatek.wwtv.tvcenter"));
        // The factory key receiver is in the list by the owner's decision: the physical gear reaches it
        // and it shows system settings next to the picture settings window.
        assertTrue(packages.contains("com.homwee.factory"));
        // The idle extras the owner asked to put in the list (2026-11-14): the CTS test shims and the
        // feedback consent screen, whose uploader is already frozen.
        assertTrue(packages.contains("com.android.cts.ctsshim"));
        assertTrue(packages.contains("com.android.cts.priv.ctsshim"));
        assertTrue(packages.contains("com.android.tv.feedbackconsent"));
        assertFalse(packages.contains("com.android.managedprovisioning"));
    }
    @Test public void theAndroidCoreBlockIsNeverFreezable(){
        // Removing these branded a TV unbootable (cleanyourtv issue #884), so they stay out of the list
        // even though they look idle on this firmware.
        for(String key:new String[]{"com.google.android.ext.services","com.google.android.ext.shared",
                "com.google.android.modulemetadata","com.google.android.permissioncontroller"})
            assertFalse(key,FreezableApps.safe(key,"ru.pda.tvhome"));
    }
    @Test public void homeSettingsTunerKeyboardAndWebViewAreNeverFreezable(){
        for(String key:new String[]{"ru.pda.tvhome","com.android.tv.settings",
                "com.mediatek.tvinput","com.google.android.inputmethod.latin",
                "com.google.android.webview","com.google.android.tv.remote.service","com.android.statementservice"})
            assertFalse(key,FreezableApps.safe(key,"ru.pda.tvhome"));
    }
    @Test public void resourceOverlaysAreNotFreezable(){
        assertFalse(FreezableApps.safe("android.auto_generated_rro_product__","ru.pda.tvhome"));
        assertFalse(FreezableApps.safe("com.android.tv.overlay.framework","ru.pda.tvhome"));
        assertFalse(FreezableApps.safe("com.mediatek.overlay.tvcenter","ru.pda.tvhome"));
    }
}
