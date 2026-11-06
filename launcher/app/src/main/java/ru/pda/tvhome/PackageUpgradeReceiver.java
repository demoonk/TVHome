package ru.pda.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Migrate only our previously selected components; never turn on an unselected helper. */
public final class PackageUpgradeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) PackageUpgrade.migrate(context);
    }
}
