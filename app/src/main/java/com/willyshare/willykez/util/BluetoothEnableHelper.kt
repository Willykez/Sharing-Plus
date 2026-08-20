package com.willyshare.willykez.util

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent

object BluetoothEnableHelper {

    fun isBluetoothEnabled(context: Context): Boolean {
        val manager = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return try {
            manager?.adapter?.isEnabled == true
        } catch (_: SecurityException) {
            // isEnabled() itself requires BLUETOOTH_CONNECT on Android 12+ - treat "can't even
            // check" the same as "off" rather than crashing a banner's visibility check.
            false
        }
    }

    /**
     * The correct API for this specific job: unlike Wi-Fi (which only offers a Settings
     * panel), Bluetooth has a purpose-built system dialog that turns the adapter on directly
     * from a single tap, no detour through Settings at all. Must be launched with
     * startActivityForResult/an ActivityResultLauncher (not a bare startActivity) since the
     * system needs to know this specific request was made by a foreground activity.
     */
    fun requestEnableIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
}
