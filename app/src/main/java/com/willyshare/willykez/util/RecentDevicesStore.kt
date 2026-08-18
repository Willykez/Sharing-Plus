package com.willyshare.willykez.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers the last few Wi-Fi Direct devices this app has successfully connected to, so Send
 * can surface them ahead of the plain discovery list next time they're back in range, and so
 * a device can be marked "trusted" to skip this device's own active confirm prompt later.
 *
 * Deliberately NOT a new Room table: the transfer-history database uses
 * fallbackToDestructiveMigration(), so adding a table there would bump the schema version and
 * silently wipe every existing user's transfer history on their next update. A small JSON blob
 * in SharedPreferences carries zero migration risk for a few kilobytes of "recent devices."
 *
 * Wi-Fi Direct itself only exposes a MAC address, never a stable persistent device ID, so this
 * is deliberately best-effort: it's for recognizing "this is the phone I sent to yesterday"
 * *if* their MAC hasn't changed (Android randomizes Wi-Fi Direct MACs on some OEMs/versions),
 * not a guaranteed match. A miss just means the device shows up as a normal (unpinned) entry.
 */
object RecentDevicesStore {
    private const val PREFS_NAME = "recent_devices"
    private const val KEY_DEVICES = "devices"
    private const val MAX_REMEMBERED = 6

    data class RecentDevice(val name: String, val address: String, val lastConnectedMs: Long, val trusted: Boolean = false)

    fun recordConnection(context: Context, name: String, address: String) {
        if (address.isBlank()) return
        val existingTrust = load(context).firstOrNull { it.address == address }?.trusted ?: false
        val current = load(context).filterNot { it.address == address }
        val updated = (listOf(RecentDevice(name, address, System.currentTimeMillis(), existingTrust)) + current)
            .take(MAX_REMEMBERED)
        save(context, updated)
    }

    /** Trust is a purely local, per-device decision - this device deciding it will skip its
     *  own active confirm prompt for a peer it recognizes. It's keyed by Wi-Fi Direct MAC
     *  address for the Send-side "recent devices" list (auto-reconnect uses this), and
     *  separately checked by device NAME on the accepting side of a handshake - see
     *  [isTrustedByName] for why those have to be different checks. Revoking is just
     *  flipping the flag back; nothing else needs cleanup. */
    fun setTrusted(context: Context, address: String, trusted: Boolean) {
        val updated = load(context).map { if (it.address == address) it.copy(trusted = trusted) else it }
        save(context, updated)
    }

    fun isTrusted(context: Context, address: String): Boolean =
        load(context).firstOrNull { it.address == address }?.trusted == true

    /** Used on the accepting side of a handshake, which only ever learns the dialer's
     *  self-reported device NAME (Android blocks apps from reading their own device's real
     *  Wi-Fi MAC address since API 23, so there's no stable identifier to send instead).
     *  This is a convenience layer on top of the match-code confirmation, not a replacement
     *  for it - a device name is something anyone could set to match, so trusting by name
     *  only ever skips the LOCAL confirm tap for a name this device has *itself* previously
     *  connected to and been explicitly told to trust; it was never the thing standing
     *  between an attacker and a transfer to begin with, the PIN was. */
    fun isTrustedByName(context: Context, name: String): Boolean =
        name.isNotBlank() && load(context).any { it.name == name && it.trusted }

    fun load(context: Context): List<RecentDevice> {
        return try {
            val raw = prefs(context).getString(KEY_DEVICES, null) ?: return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentDevice(o.getString("name"), o.getString("address"), o.optLong("ts", 0L), o.optBoolean("trusted", false))
            }.sortedByDescending { it.lastConnectedMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, devices: List<RecentDevice>) {
        try {
            val arr = JSONArray()
            devices.forEach { d ->
                arr.put(JSONObject().apply {
                    put("name", d.name)
                    put("address", d.address)
                    put("ts", d.lastConnectedMs)
                    put("trusted", d.trusted)
                })
            }
            prefs(context).edit().putString(KEY_DEVICES, arr.toString()).apply()
        } catch (_: Exception) {
            // Best-effort - losing the "recent devices" list is never worth crashing over.
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
