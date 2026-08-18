package com.willyshare.willykez.net

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin, real wrapper around Android's WifiP2pManager (Wi-Fi Direct).
 * No simulated/fake devices anywhere here - every device in [peers] came from
 * an actual WIFI_P2P_PEERS_CHANGED_ACTION broadcast from the OS.
 */
class WifiDirectManager(private val context: Context) {

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val _isWifiP2pEnabled = MutableStateFlow(false)
    val isWifiP2pEnabled: StateFlow<Boolean> = _isWifiP2pEnabled.asStateFlow()

    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    private val _thisDeviceName = MutableStateFlow(android.os.Build.MODEL ?: "This device")
    val thisDeviceName: StateFlow<String> = _thisDeviceName.asStateFlow()

    /** This device's own Wi-Fi Direct MAC, as last reported by the OS. Handed out over BLE
     *  (see [com.willyshare.willykez.net.BleNearbyManager.localInfoProvider]) so a nearby
     *  peer can dial [connectByAddress] the instant they spot us over Bluetooth, instead of
     *  waiting for their own `discoverPeers()` cycle to surface us. */
    private val _thisDeviceAddress = MutableStateFlow<String?>(null)
    val thisDeviceAddress: StateFlow<String?> = _thisDeviceAddress.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _groupInfo = MutableStateFlow<WifiP2pGroup?>(null)
    val groupInfo: StateFlow<WifiP2pGroup?> = _groupInfo.asStateFlow()

    /**
     * True only once a *real* peer has joined our self-created group - i.e. this device is
     * the group owner AND the group's client list is non-empty. Forming the group by
     * yourself (host with zero clients) must never read as "connected"; that was the source
     * of the false-positive "Connected" notification firing the instant the QR was shown,
     * before any sender had actually joined.
     */
    private val _hostHasPeer = MutableStateFlow(false)
    val hostHasPeer: StateFlow<Boolean> = _hostHasPeer.asStateFlow()

    private fun refreshGroupInfo() {
        val mgr = manager ?: return
        val ch = channel ?: return
        try {
            mgr.requestGroupInfo(ch) { group ->
                _groupInfo.value = group
                _hostHasPeer.value = group?.isGroupOwner == true && group.clientList.isNotEmpty()
            }
        } catch (_: SecurityException) {
        }
    }

    val isFastConnectSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    val isSupported: Boolean get() = manager != null

    fun start() {
        val mgr = manager ?: return
        channel = mgr.initialize(context, context.mainLooper, null)

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        val br = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        _isWifiP2pEnabled.value = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        val ch = channel ?: return
                        try {
                            mgr.requestPeers(ch) { peerList ->
                                _peers.value = peerList.deviceList.toList()
                            }
                        } catch (e: SecurityException) {
                            _lastError.value = "Missing permission to read nearby devices"
                        }
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val ch = channel ?: return
                        try {
                            mgr.requestConnectionInfo(ch) { info -> _connectionInfo.value = info }
                            mgr.requestGroupInfo(ch) { group ->
                                _groupInfo.value = group
                                _hostHasPeer.value = group?.isGroupOwner == true && group.clientList.isNotEmpty()
                            }
                        } catch (_: SecurityException) {
                        }
                    }

                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val device =
                            intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        if (!device?.deviceName.isNullOrBlank()) {
                            _thisDeviceName.value = device!!.deviceName
                        }
                        // deviceAddress here is frequently randomized/anonymized on Android 12+
                        // for apps without NEARBY_WIFI_DEVICES, but on the rare devices where
                        // it isn't, this is the cheapest possible way to pick it up - no extra
                        // permission or API call beyond what discovery already needs.
                        if (!device?.deviceAddress.isNullOrBlank()) {
                            _thisDeviceAddress.value = device!!.deviceAddress
                        }
                    }
                }
            }
        }
        receiver = br
        // ContextCompat handles the version branching: on API 33+, Android requires an
        // explicit exported/not-exported flag for any context-registered receiver, or it
        // can throw at runtime. These are all system Wi-Fi Direct broadcasts - no other app
        // should be able to spoof them into this receiver, so NOT_EXPORTED is correct here.
        androidx.core.content.ContextCompat.registerReceiver(
            context, br, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshOwnDeviceInfo()
    }

    /** A second, more reliable source for [thisDeviceAddress]/[thisDeviceName] than waiting on
     *  a THIS_DEVICE_CHANGED broadcast that may not fire again once already delivered once. */
    @SuppressLint("MissingPermission")
    private fun refreshOwnDeviceInfo() {
        val mgr = manager ?: return
        val ch = channel ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            mgr.requestDeviceInfo(ch) { device ->
                if (!device?.deviceName.isNullOrBlank()) _thisDeviceName.value = device!!.deviceName
                if (!device?.deviceAddress.isNullOrBlank()) _thisDeviceAddress.value = device!!.deviceAddress
            }
        } catch (_: SecurityException) {
        }
    }

    fun stop() {
        stopDiscovery()
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        receiver = null
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val mgr = manager ?: return
        val ch = channel ?: return
        try {
            mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _isDiscovering.value = true
                    _lastError.value = null
                }

                override fun onFailure(reason: Int) {
                    _isDiscovering.value = false
                    _lastError.value = "Discovery failed (code $reason)"
                }
            })
        } catch (e: SecurityException) {
            _lastError.value = "Location / Nearby devices permission required"
        }
    }

    fun stopDiscovery() {
        val mgr = manager ?: return
        val ch = channel ?: return
        _isDiscovering.value = false
        try {
            mgr.stopPeerDiscovery(ch, null)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice, onResult: (Boolean, String) -> Unit) {
        connectToAddress(device.deviceAddress, device.deviceName, onResult)
    }

    /**
     * BLE fast path: dials a Wi-Fi Direct connect using an address handed over by
     * [BleNearbyManager]'s GATT exchange, without needing that peer to have shown up in our
     * own `discoverPeers()` list first. Wi-Fi Direct's `connect()` only needs the MAC - it
     * doesn't care whether the caller learned it via P2P discovery or another channel - so
     * this is a legitimate shortcut, not a protocol workaround: it just skips the slowest
     * part (waiting for our own discovery cycle to notice them) while every subsequent step
     * (negotiation, group formation, the match-code handshake) is identical to the normal path.
     */
    fun connectByAddress(address: String, displayName: String, onResult: (Boolean, String) -> Unit) {
        connectToAddress(address, displayName, onResult)
    }

    @SuppressLint("MissingPermission")
    private fun connectToAddress(
        address: String,
        displayName: String,
        onResult: (Boolean, String) -> Unit,
        attempt: Int = 1
    ) {
        val mgr = manager ?: return onResult(false, "Wi-Fi Direct not supported")
        val ch = channel ?: return onResult(false, "Not initialized")
        val config = WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = WpsInfo.PBC
            // 0 means least inclination to be the Group Owner (Client)
            groupOwnerIntent = 0
        }
        try {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    onResult(true, "Connecting to $displayName\u2026")
                }

                override fun onFailure(reason: Int) {
                    // Reason 2 = BUSY: extremely common right after a previous group/connection
                    // was torn down, since the driver hasn't finished cleaning up yet. A blind
                    // "connection failed" here is the single most common false-negative this
                    // app used to surface - one short-delayed retry clears the vast majority
                    // of them without the person having to notice or tap anything twice.
                    if (reason == WifiP2pManager.BUSY && attempt < 2) {
                        android.os.Handler(context.mainLooper).postDelayed({
                            connectToAddress(address, displayName, onResult, attempt + 1)
                        }, 1200L)
                    } else {
                        onResult(false, "Connection failed (code $reason)")
                    }
                }
            })
        } catch (e: SecurityException) {
            onResult(false, "Missing permission to connect")
        }
    }

    fun disconnect() {
        val mgr = manager ?: return
        val ch = channel ?: return
        try {
            mgr.removeGroup(ch, null)
        } catch (_: Exception) {
        }
        _connectionInfo.value = null
        _groupInfo.value = null
        _hostHasPeer.value = false
    }

    /**
     * HOST side of "high-speed mode": creates this device's own autonomous Wi-Fi Direct
     * group and, on Android 10+, pins it to the 5GHz band - the same trick apps like
     * Xender use to get 30-40 MB/s instead of the ~6 MB/s a 2.4GHz link caps out at.
     * On older Android versions there's no API to force the band, so this falls back to
     * a normal (auto-band) group; the caller should treat [preferHighSpeed] as a best effort.
     */
    @SuppressLint("MissingPermission")
    fun createFastGroup(networkName: String, passphrase: String, preferHighSpeed: Boolean, onResult: (Boolean, String) -> Unit) {
        val mgr = manager ?: return onResult(false, "Wi-Fi Direct not supported")
        val ch = channel ?: return onResult(false, "Not initialized")
        _hostHasPeer.value = false

        val plainListener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                refreshGroupInfo()
                onResult(true, "Group created")
            }
            override fun onFailure(reason: Int) {
                onResult(false, "Group creation failed (code $reason)")
            }
        }

        val fastListener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                refreshGroupInfo()
                onResult(true, "Group created (5GHz)")
            }

            override fun onFailure(reason: Int) {
                // Common on budget/Go-edition chipsets: the OS rejects a forced 5GHz group.
                // Don't just give up - a normal (auto-band) group still works fine.
                try {
                    mgr.createGroup(ch, plainListener)
                } catch (e2: SecurityException) {
                    onResult(false, "Missing permission to create group")
                }
            }
        }

        fun proceedToCreate() {
            try {
                if (isFastConnectSupported && preferHighSpeed) {
                    val config = WifiP2pConfig.Builder()
                        .setNetworkName(networkName)
                        .setPassphrase(passphrase)
                        .enablePersistentMode(false)
                        .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                        .build()
                    config.groupOwnerIntent = 15
                    mgr.createGroup(ch, config, fastListener)
                } else {
                    // Pre-Q devices (or high-speed mode turned off): plain autonomous group,
                    // band is whatever the chipset/driver picks. This is always available on
                    // every Android version - it's the reliable baseline, not a fallback of
                    // last resort, since it doesn't depend on already being on some Wi-Fi network.
                    mgr.createGroup(ch, plainListener)
                }
            } catch (e: SecurityException) {
                onResult(false, "Missing permission to create group")
            } catch (e: IllegalArgumentException) {
                // Some OEMs reject the network name/passphrase format outright - fall back to
                // auto-generated credentials via the plain (no explicit config) overload.
                try {
                    mgr.createGroup(ch, plainListener)
                } catch (e2: SecurityException) {
                    onResult(false, "Missing permission to create group")
                }
            }
        }

        // Tear down any existing group FIRST. createGroup() fails with BUSY if this device
        // is already part of a group - which is exactly what happens when "High-speed Mode"
        // gets flipped after MyQrScreen already formed a group on entry. Without this, toggling
        // the switch silently fails to actually change bands: the fast attempt fails BUSY, the
        // plain fallback also fails BUSY, and the QR ends up advertising credentials for a
        // group that was never actually (re)created - while the real, still-live group is
        // still on the old band. requestGroupInfo's callback always fires (null group is a
        // valid "nothing to remove" result), so this never leaves proceedToCreate() uncalled.
        try {
            mgr.requestGroupInfo(ch) { existingGroup ->
                if (existingGroup != null) {
                    mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = proceedToCreate()
                        override fun onFailure(reason: Int) = proceedToCreate() // proceed anyway; worst case createGroup fails cleanly on its own
                    })
                } else {
                    proceedToCreate()
                }
            }
        } catch (e: SecurityException) {
            onResult(false, "Missing permission to create group")
        }
    }

    /**
     * CLIENT side of "high-speed mode": joins a group created by [createFastGroup] directly
     * using the network name + passphrase carried in the QR code - no discovery/pairing
     * dialog needed. Requires Android 10+; call [isFastConnectSupported] first.
     */
    @SuppressLint("MissingPermission")
    fun joinFastGroup(networkName: String, passphrase: String, onResult: (Boolean, String) -> Unit) {
        val mgr = manager ?: return onResult(false, "Wi-Fi Direct not supported")
        val ch = channel ?: return onResult(false, "Not initialized")
        if (!isFastConnectSupported) {
            return onResult(false, "High-speed mode needs Android 10 or newer")
        }
        
        // 1. Build the configuration first
        val config = WifiP2pConfig.Builder()
            .setNetworkName(networkName)
            .setPassphrase(passphrase)
            .build()
            
        // 2. Set the group owner intent directly on the resulting config object
        // 0 means least inclination to be the Group Owner (Client)
        config.groupOwnerIntent = 0

        try {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    onResult(true, "Connecting\u2026")
                }

                override fun onFailure(reason: Int) {
                    onResult(false, "Connection failed (code $reason)")
                }
            })
        } catch (e: SecurityException) {
            onResult(false, "Missing permission to connect")
        }
    }

    fun stopGroup() {
        val mgr = manager ?: return
        val ch = channel ?: return
        try { mgr.removeGroup(ch, null) } catch (_: Exception) {}
        _groupInfo.value = null
        _hostHasPeer.value = false
    }
}