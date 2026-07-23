package com.willyshare.willykez.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Wi-Fi Direct connections and the always-on receive listener both need to survive being
 * backgrounded. Stock Android's Doze mode is one thing (the foreground service already
 * handles that) - the real, well-documented problem is OEM-specific battery managers
 * (Xiaomi's MIUI, Samsung's "Sleeping apps", OnePlus, Huawei, etc.) that kill background
 * network connections and services far more aggressively than stock Android does, entirely
 * independent of the standard Doze/App Standby rules. This is one of the single most common
 * root causes of "it randomly disconnects" reports across every P2P file-sharing app on the
 * Play Store - and it's invisible to the user unless something in the app actually surfaces
 * whether they're exempted and offers a one-tap way to fix it.
 */
object BatteryOptimizationHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the system's own exemption-request dialog for this app specifically - the user
     * still has to tap "Allow" on it themselves; this never grants anything silently. Falls
     * back to the general battery-optimization list on the rare device/ROM combo that
     * rejects the direct-request intent (some OEM skins restrict it).
     */
    fun requestExemption(context: Context): Intent {
        return Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun openBatterySettingsFallback(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
