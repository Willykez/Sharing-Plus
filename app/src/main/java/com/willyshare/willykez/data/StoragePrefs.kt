package com.willyshare.willykez.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.storagePrefsDataStore by preferencesDataStore(name = "spark_storage_prefs")

class StoragePrefs(private val context: Context) {
    private object Keys {
        val RECEIVE_TREE_URI = stringPreferencesKey("receive_tree_uri")
        val BLE_FAST_DISCOVERY = androidx.datastore.preferences.core.booleanPreferencesKey("ble_fast_discovery")
    }

    /** The saved SAF tree Uri string for received files, or null if using the app default. */
    val receiveTreeUri: Flow<String?> =
        context.storagePrefsDataStore.data.map { it[Keys.RECEIVE_TREE_URI] }

    suspend fun setReceiveTreeUri(uriString: String?) {
        context.storagePrefsDataStore.edit { prefs ->
            if (uriString == null) {
                prefs.remove(Keys.RECEIVE_TREE_URI)
            } else {
                prefs[Keys.RECEIVE_TREE_URI] = uriString
            }
        }
    }

    /** "Fast discovery (Bluetooth)" toggle - on by default. Turning it off stops BLE
     *  advertising/scanning entirely; discovery falls back to Wi-Fi Direct alone. */
    val bleFastDiscoveryEnabled: Flow<Boolean> =
        context.storagePrefsDataStore.data.map { it[Keys.BLE_FAST_DISCOVERY] ?: true }

    suspend fun setBleFastDiscoveryEnabled(enabled: Boolean) {
        context.storagePrefsDataStore.edit { prefs -> prefs[Keys.BLE_FAST_DISCOVERY] = enabled }
    }
}
