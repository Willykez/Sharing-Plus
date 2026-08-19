package com.willyshare.willykez.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One nearby Sharing Plus install, found and identified over BLE - not yet resolved by
 * Wi-Fi Direct's own (much slower) peer discovery. [wifiP2pAddress] came straight from the
 * peer's GATT characteristic, so it's ready to hand to [WifiDirectManager.connectByAddress]
 * the moment the person taps it - no waiting for `discoverPeers()` to catch up.
 */
data class BleNearbyDevice(
    val bleAddress: String,
    val name: String,
    val wifiP2pAddress: String?,
    val lastSeenAtMs: Long,
    val rssi: Int,
    /** False while the peer is already mid-transfer with someone else (or not actively
     *  listening) - lets the UI grey out / deprioritize a device that would just queue behind
     *  another transfer instead of accepting one right away. */
    val readyToReceive: Boolean = true
)

/** What this device hands out over GATT about itself - see [BleNearbyManager.localInfoProvider]. */
data class LocalBleInfo(val name: String, val wifiP2pAddress: String?, val readyToReceive: Boolean)

/**
 * BLE layer for *discovery only* - mirrors how Quick Share/Nearby Share actually bootstraps:
 * Bluetooth LE finds and identifies a nearby device in under a second (vs Wi-Fi Direct's
 * ~10-12s discovery cycle and the OS silently dropping it after ~2 minutes), then the real
 * file transfer still happens entirely over Wi-Fi Direct - this class never moves a single
 * byte of file data. Every device in [nearbyDevices] came from a real BLE scan result and a
 * real GATT read; nothing here is simulated.
 *
 * Two roles run concurrently, same as every other participant on the mesh:
 *  - Advertiser + GATT server: announces "a Sharing Plus device is here" and, when asked,
 *    hands back this device's name + current Wi-Fi Direct address.
 *  - Scanner + GATT client: watches for that same advertisement, connects just long enough
 *    to read the peer's name/address, then disconnects - it never holds a BLE link open.
 */
class BleNearbyManager(private val context: Context) {

    companion object {
        // Fixed 128-bit UUID unique to this app - the service filter both sides scan/advertise
        // for. Random UUID, generated once; never reuse Google's own Nearby Share service ID.
        private val SERVICE_UUID: UUID = UUID.fromString("8e6f2f2e-3a11-4bde-9a3d-8f6a7b6e5b21")
        private val CHARACTERISTIC_UUID: UUID = UUID.fromString("8e6f2f2f-3a11-4bde-9a3d-8f6a7b6e5b21")
        private val PARCEL_SERVICE_UUID = ParcelUuid(SERVICE_UUID)

        /** A device not re-confirmed by a fresh advert/GATT read within this window is
         *  dropped - handles someone walking out of range or closing the app without any
         *  explicit "goodbye" message existing on the wire. */
        private const val STALE_MS = 15_000L
        private const val PRUNE_INTERVAL_MS = 3_000L

        /** How long a resolved address + readiness flag is trusted before the next
         *  advertisement sighting triggers a fresh GATT read - readiness in particular
         *  (busy/free) changes far more often than the address does, so this needs to be
         *  short enough that the UI doesn't show a stale "ready" badge for a peer that
         *  started a transfer with someone else moments ago. */
        private const val READY_REFRESH_MS = 8_000L

        /** Re-attempted at most this many times per BLE address per advertisement sighting,
         *  so one flaky/unresponsive peer can't spin the scanner in a tight connect loop. */
        private const val MAX_GATT_ATTEMPTS_PER_SIGHTING = 2
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    private var scope: CoroutineScope? = null
    private var pruneJob: Job? = null

    private val _nearbyDevices = MutableStateFlow<List<BleNearbyDevice>>(emptyList())
    val nearbyDevices: StateFlow<List<BleNearbyDevice>> = _nearbyDevices.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val deviceMap = ConcurrentHashMap<String, BleNearbyDevice>()
    private val gattAttempts = ConcurrentHashMap<String, Int>()
    private val gattResolvedAt = ConcurrentHashMap<String, Long>()

    /** Supplies our own current name, Wi-Fi Direct address, and whether we're free to accept
     *  a transfer right now, at GATT-read time (not cached at construction) since all three
     *  can change - address on group re-formation, readiness whenever a transfer starts. */
    var localInfoProvider: () -> LocalBleInfo = { LocalBleInfo(android.os.Build.MODEL ?: "Device", null, true) }

    val isSupported: Boolean
        get() = adapter != null &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(android.Manifest.permission.BLUETOOTH_SCAN)
        } else true // covered by the normal (install-time) BLUETOOTH/BLUETOOTH_ADMIN permissions

    private fun hasAdvertisePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
        } else true

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else true

    /**
     * Starts advertising + scanning. Safe to call repeatedly (e.g. once on app start, then
     * again from a screen's "permissions just got granted" effect) - a call that finds
     * Bluetooth off or permissions missing just no-ops and leaves [isActive] false rather
     * than throwing, exactly like [WifiDirectManager] treats a denied permission as "nothing
     * to do yet" instead of a crash.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (_isActive.value) return
        val ad = adapter ?: return
        // isEnabled() itself requires BLUETOOTH_CONNECT on Android 12+ - this can be called
        // from the ViewModel's init{} before any permission prompt has run, so a missing
        // permission here must be a quiet "not yet" rather than a crash at app launch.
        val enabled = try { ad.isEnabled } catch (_: SecurityException) { return }
        if (!enabled) return
        if (!isSupported) return

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope

        startGattServerAndAdvertising()
        startScanning()

        pruneJob = newScope.launch {
            while (true) {
                kotlinx.coroutines.delay(PRUNE_INTERVAL_MS)
                val now = System.currentTimeMillis()
                var changed = false
                deviceMap.entries.removeAll { (_, v) ->
                    (now - v.lastSeenAtMs > STALE_MS).also { if (it) changed = true }
                }
                if (changed) _nearbyDevices.value = deviceMap.values.sortedByDescending { it.lastSeenAtMs }
            }
        }
        _isActive.value = true
    }

    @SuppressLint("MissingPermission")
    private fun startGattServerAndAdvertising() {
        if (!hasAdvertisePermission() || !hasConnectPermission()) return
        val mgr = bluetoothManager ?: return
        val ad = adapter ?: return

        val server = try {
            mgr.openGattServer(context, object : BluetoothGattServerCallback() {
                override fun onCharacteristicReadRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    offset: Int,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    if (characteristic.uuid != CHARACTERISTIC_UUID) return
                    val info = localInfoProvider()
                    val payload = encodePayload(info.name, info.wifiP2pAddress, info.readyToReceive)
                    val slice = if (offset < payload.size) payload.copyOfRange(offset, payload.size) else ByteArray(0)
                    try {
                        gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, slice)
                    } catch (_: SecurityException) {
                    }
                }
            })
        } catch (_: SecurityException) { null } ?: return
        gattServer = server

        try {
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(characteristic)
            server.addService(service)
        } catch (_: SecurityException) {
        }

        val leAdvertiser = ad.bluetoothLeAdvertiser ?: return
        advertiser = leAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(PARCEL_SERVICE_UUID)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                // Common on devices with no BLE peripheral/advertiser support, or when
                // another app already holds all available advertise sets - discovery of
                // OTHER devices (scanning) still works fine, we just won't be found
                // ourselves over BLE; Wi-Fi Direct discovery remains the fallback either way.
            }
        }
        advertiseCallback = cb
        try {
            leAdvertiser.startAdvertising(settings, data, cb)
        } catch (_: SecurityException) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (!hasScanPermission() || !hasConnectPermission()) return
        val ad = adapter ?: return
        val leScanner = ad.bluetoothLeScanner ?: return
        scanner = leScanner

        val filters = listOf(ScanFilter.Builder().setServiceUuid(PARCEL_SERVICE_UUID).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onSighted(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onSighted(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                // Leave whatever devices are already known in place; the prune loop will
                // age them out naturally if scanning never recovers. Wi-Fi Direct discovery
                // (already running independently) is unaffected by a BLE scan failure.
            }
        }
        scanCallback = cb
        try {
            leScanner.startScan(filters, settings, cb)
        } catch (_: SecurityException) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun onSighted(result: ScanResult) {
        val address = try { result.device.address } catch (_: SecurityException) { null } ?: return
        val existing = deviceMap[address]
        val now = System.currentTimeMillis()
        // Refresh last-seen immediately from the advertisement alone, even before a GATT
        // read completes - so a device already resolved on a previous sighting doesn't
        // get pruned while we're mid-reconnect for a refreshed payload.
        if (existing != null) {
            deviceMap[address] = existing.copy(lastSeenAtMs = now, rssi = result.rssi)
        }
        val attempts = gattAttempts.getOrDefault(address, 0)
        val lastResolved = gattResolvedAt.getOrDefault(address, 0L)
        if (existing?.wifiP2pAddress != null && attempts == 0 && now - lastResolved < READY_REFRESH_MS) {
            // Resolved recently enough that the address is still good and readyToReceive is
            // still a fair reflection of the peer's current state; just keep the last-seen
            // bump above rather than reconnecting on every single advertisement tick.
            _nearbyDevices.value = deviceMap.values.sortedByDescending { it.lastSeenAtMs }
            return
        }
        if (attempts >= MAX_GATT_ATTEMPTS_PER_SIGHTING) return
        gattAttempts[address] = attempts + 1
        if (!hasConnectPermission()) return

        try {
            result.device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try { gatt.discoverServices() } catch (_: SecurityException) {}
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        try { gatt.close() } catch (_: Exception) {}
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
                    if (characteristic == null || status != BluetoothGatt.GATT_SUCCESS) {
                        try { gatt.disconnect() } catch (_: Exception) {}
                        return
                    }
                    try { gatt.readCharacteristic(characteristic) } catch (_: SecurityException) {}
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        decodePayload(characteristic.value)?.let { decoded ->
                            deviceMap[address] = BleNearbyDevice(
                                bleAddress = address,
                                name = decoded.name,
                                wifiP2pAddress = decoded.wifiP2pAddress,
                                lastSeenAtMs = System.currentTimeMillis(),
                                rssi = result.rssi,
                                readyToReceive = decoded.readyToReceive
                            )
                            gattAttempts[address] = 0
                            gattResolvedAt[address] = System.currentTimeMillis()
                            _nearbyDevices.value = deviceMap.values.sortedByDescending { it.lastSeenAtMs }
                        }
                    }
                    try { gatt.disconnect() } catch (_: Exception) {}
                }
            })
        } catch (_: SecurityException) {
        }
    }

    private fun encodePayload(name: String, wifiMac: String?, ready: Boolean): ByteArray {
        val safeName = name.take(40).replace("\"", "'")
        val json = "{\"n\":\"$safeName\",\"m\":\"${wifiMac ?: ""}\",\"r\":${if (ready) 1 else 0}}"
        return json.toByteArray(StandardCharsets.UTF_8)
    }

    private fun decodePayload(bytes: ByteArray?): LocalBleInfo? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            val json = String(bytes, StandardCharsets.UTF_8)
            val name = Regex("\"n\":\"(.*?)\"").find(json)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return null
            val mac = Regex("\"m\":\"(.*?)\"").find(json)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            // Missing/unparseable "r" defaults to ready=true rather than silently hiding an
            // older peer build that doesn't send the field yet.
            val ready = Regex("\"r\":(\\d)").find(json)?.groupValues?.get(1) != "0"
            LocalBleInfo(name, mac, ready)
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        pruneJob?.cancel()
        pruneJob = null
        scope?.cancel()
        scope = null
        try { scanCallback?.let { scanner?.stopScan(it) } } catch (_: Exception) {}
        try { advertiseCallback?.let { advertiser?.stopAdvertising(it) } } catch (_: Exception) {}
        try { gattServer?.close() } catch (_: Exception) {}
        scanCallback = null
        advertiseCallback = null
        gattServer = null
        scanner = null
        advertiser = null
        deviceMap.clear()
        gattAttempts.clear()
        gattResolvedAt.clear()
        _nearbyDevices.value = emptyList()
        _isActive.value = false
    }
}
