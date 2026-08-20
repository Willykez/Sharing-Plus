package com.willyshare.willykez.ui

import android.app.Application
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.willyshare.willykez.net.BleNearbyDevice
import com.willyshare.willykez.net.BleNearbyManager
import com.willyshare.willykez.data.FileItemEntity
import com.willyshare.willykez.data.PulseDatabase
import com.willyshare.willykez.data.StoragePrefs
import com.willyshare.willykez.data.TransferEntity
import com.willyshare.willykez.net.DeviceFiles
import com.willyshare.willykez.net.FileReceiveServer
import com.willyshare.willykez.net.FileSenderClient
import com.willyshare.willykez.net.LocalFileNode
import com.willyshare.willykez.net.LocalFileSystem
import com.willyshare.willykez.net.NetworkUtils
import com.willyshare.willykez.net.QrPairing
import com.willyshare.willykez.net.ReceiveTarget
import com.willyshare.willykez.net.SafFileWriter
import com.willyshare.willykez.net.SendableFile
import com.willyshare.willykez.net.StorageRoot
import com.willyshare.willykez.net.TRANSFER_PORT
import com.willyshare.willykez.net.TransferProgress
import com.willyshare.willykez.net.WifiDirectManager
import com.willyshare.willykez.net.performPinHandshake
import com.willyshare.willykez.service.SparkTransferService
import com.willyshare.willykez.util.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.channels.SocketChannel
import java.util.UUID

/** How the current outgoing transfer's target was resolved. */
enum class TargetSource { WIFI_DIRECT, QR_PAIR, NONE }

/**
 * Single, unified "what's going on right now" signal - replaces having to separately check
 * targetSource / hostHasPeer / senderConnected / progress in every screen to answer the
 * same question. This is step one of the state-machine work; screens can adopt it
 * incrementally.
 */
enum class LinkState { IDLE, RESOLVING, CONNECTED, TRANSFERRING }

/** Intermediate tuple for the 5-way [combine] below - kotlinx's typed `combine` overloads only
 *  go up to 5 flows, and a 6th (BLE resolving signal) needs to fold in afterward. */
private data class LinkPhase(
    val source: TargetSource,
    val hostPeer: Boolean,
    val senderConn: Boolean,
    val sendTotal: Long,
    val sendComplete: Boolean,
    val recvTotal: Long,
    val recvComplete: Boolean
)

/** Matches FileTransfer.kt's own handshake socket timeout, so the local confirm prompt and
 *  the underlying socket read time out around the same moment rather than one hanging on
 *  after the other has already given up. */
private const val HANDSHAKE_UI_TIMEOUT_MS = 30_000L

class PulseViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = PulseDatabase.getDatabase(application).pulseDao()
    private val appContext get() = getApplication<Application>()
    private val storagePrefs = StoragePrefs(application)

    // ---- Real networking components ----
    val wifiDirect = WifiDirectManager(application)
    /** BLE is discovery/bootstrap only - see [BleNearbyManager]'s own doc comment. It hands
     *  its found devices' Wi-Fi Direct addresses to [WifiDirectManager.connectByAddress]; no
     *  file bytes ever move over Bluetooth. */
    val bleNearby = BleNearbyManager(application).apply {
        localInfoProvider = {
            com.willyshare.willykez.net.LocalBleInfo(
                name = wifiDirect.thisDeviceName.value,
                wifiP2pAddress = wifiDirect.thisDeviceAddress.value,
                // Only advertise "ready" while actually listening and not already mid-transfer
                // with someone else - avoids a sender's BLE scan showing this device as an
                // easy target when it would really just queue behind an active transfer.
                readyToReceive = fileReceiver.isListening.value && !fileReceiver.senderConnected.value
            )
        }
    }
    val nearbyBleDevices: StateFlow<List<BleNearbyDevice>> = bleNearby.nearbyDevices
    private val fileSender = FileSenderClient(application)
    private val defaultReceiveDir: File
        get() = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "PulseReceived"
        ).apply { mkdirs() }

    /** Where a custom "save received files to" folder currently points, or null for the app default. */
    val receiveTreeUri: StateFlow<String?> = storagePrefs.receiveTreeUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** "Fast discovery (Bluetooth)" from Settings - on by default. See [setBleFastDiscoveryEnabled]. */
    val bleFastDiscoveryEnabled: StateFlow<Boolean> = storagePrefs.bleFastDiscoveryEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val fileReceiver = FileReceiveServer(
        targetProvider = {
            val treeUriString = receiveTreeUri.value
            val treeUri = treeUriString?.let { Uri.parse(it) }
            if (treeUri != null && SafFileWriter.isAccessible(appContext, treeUri)) {
                ReceiveTarget.Tree(appContext, treeUri)
            } else {
                ReceiveTarget.Plain(defaultReceiveDir)
            }
        },
        onPullRequested = ::handleIncomingPullRequest,
        onHandshakeRequested = { pin, peerName, isPullIntent ->
            if (com.willyshare.willykez.util.RecentDevicesStore.isTrustedByName(appContext, peerName)) {
                true
            } else {
                requestLocalPinConfirm(pin, peerName, isPullIntent = isPullIntent)
            }
        }
    )

    /** Human-readable label for Settings: either the default path or the picked folder's name. */
    fun receiveDestinationLabel(uriString: String?): String {
        val treeUri = uriString?.let { Uri.parse(it) }
        return if (treeUri != null && SafFileWriter.isAccessible(appContext, treeUri)) {
            SafFileWriter.displayName(appContext, treeUri)
        } else {
            "Downloads/PulseReceived (default)"
        }
    }

    fun setReceiveDestination(treeUri: Uri?) {
        viewModelScope.launch { storagePrefs.setReceiveTreeUri(treeUri?.toString()) }
    }

    // ---- Persisted data ----
    val transfers: StateFlow<List<TransferEntity>> = dao.getAllTransfers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allFiles: StateFlow<List<FileItemEntity>> = dao.getAllFiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isLoadingFiles = MutableStateFlow(false)
    val selectedCategoryTab = MutableStateFlow("Photos")

    // ---- Full-device folder browser (internal storage + SD card, folders included) ----
    val storageRoots: List<StorageRoot> by lazy { LocalFileSystem.storageRoots(appContext) }
    /** Empty = showing the root volume list; each entry is one level deeper. */
    val browsePathStack = MutableStateFlow<List<String>>(emptyList())
    val browseEntries = MutableStateFlow<List<LocalFileNode>>(emptyList())
    val browseLoading = MutableStateFlow(false)
    /** Individually-checked file paths. */
    val browseSelectedFiles = MutableStateFlow<Set<String>>(emptySet())
    /** Whole-folder checks - resolved to their files at send time. */
    val browseSelectedFolders = MutableStateFlow<Set<String>>(emptySet())
    /** (file count, total bytes) across both selection sets, recomputed after each toggle. */
    val browseSelectionSummary = MutableStateFlow(0 to 0L)

    // ---- Wi-Fi Direct discovery (Send flow, primary QuickShare-style option) ----
    val discoveredDevices: StateFlow<List<WifiP2pDevice>> = wifiDirect.peers
    val isDiscovering: StateFlow<Boolean> = wifiDirect.isDiscovering
    val thisDeviceName: StateFlow<String> = wifiDirect.thisDeviceName
    val wifiDirectError: StateFlow<String?> = wifiDirect.lastError

    // ---- Resolved send target (either a Wi-Fi Direct peer or a scanned QR pairing code) ----
    val targetIp = MutableStateFlow<String?>(null)
    val targetPort = MutableStateFlow(TRANSFER_PORT)
    val targetName = MutableStateFlow<String?>(null)
    val targetSource = MutableStateFlow(TargetSource.NONE)

    // ---- Receive flow ----
    val isListening: StateFlow<Boolean> = fileReceiver.isListening
    val senderConnected: StateFlow<Boolean> = fileReceiver.senderConnected
    val receiveProgress: StateFlow<TransferProgress> = fileReceiver.progress

    /** Fires the moment a peer joins this device's Wi-Fi Direct group at the P2P layer -
     *  well before any TCP connection, let alone the handshake or a real transfer, exists.
     *  [senderConnected] only flips once a TCP socket is actually accepted, which is much
     *  later: the other device still has to open the handshake connection, both people have
     *  to confirm the match code, and only then does the real transfer connection open. In
     *  between, the OTHER device's own screen already shows itself as "connected" (its
     *  targetIp gets set the instant the P2P link forms), while this device was still showing
     *  a bare "waiting/listening" state with zero indication anything had happened yet - two
     *  phones side by side visibly disagreeing about whether they were connected. Screens
     *  should treat hostHasPeer as an earlier "linked" milestone, distinct from and prior to
     *  senderConnected's "actively talking" milestone. */
    val hostHasPeer: StateFlow<Boolean> = wifiDirect.hostHasPeer

    /** The IP of whoever most recently connected to this device - lets Receive offer "send
     *  files to them too" without the person ever having to leave and re-find the same peer.
     *  See [FileReceiveServer.lastPeerIp] for the full rationale. */
    val connectedPeerIp: StateFlow<String?> = fileReceiver.lastPeerIp

    /**
     * Sharing here was never meant to be a one-way street: once two devices have found each
     * other, either side should be able to push files to the other without backing all the
     * way out and re-discovering them from scratch. This reuses targetIp/targetSource - the
     * exact same fields the normal Send flow already sets - so the existing SelectFiles →
     * Transfer push path just works unmodified once this is called; the only difference is
     * *what* set them (a previously-accepted incoming connection, not a fresh outbound dial).
     */
    fun prepareSendToConnectedPeer() {
        val ip = connectedPeerIp.value ?: return
        targetIp.value = ip
        targetPort.value = TRANSFER_PORT
        targetName.value = targetName.value ?: "Connected device"
        targetSource.value = TargetSource.WIFI_DIRECT
    }

    // ---- Send flow progress ----
    val sendProgress: StateFlow<TransferProgress> = fileSender.progress

    val myQrPayload = MutableStateFlow<String?>(null)
    /** "High-speed Mode" toggle (mirrors Xender's): creates our own 5GHz Wi-Fi Direct
     *  group instead of relying on whatever Wi-Fi network happens to be shared. */
    val highSpeedMode = MutableStateFlow(false)
    val fastConnectStatus = MutableStateFlow<String?>(null)
    val isFastConnectSupported: Boolean get() = wifiDirect.isFastConnectSupported

    /** One combined signal for "what's going on right now," usable from any screen. */
    /** True while a BLE sighting exists whose Wi-Fi Direct address hasn't resolved yet via
     *  GATT - drives [LinkState.RESOLVING] so screens can show a distinct "found, identifying…"
     *  treatment instead of lumping it in with plain idle/scanning. */
    private val hasUnresolvedBleSighting: StateFlow<Boolean> = nearbyBleDevices
        .map { list -> list.any { it.wifiP2pAddress == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val linkState: StateFlow<LinkState> = combine(
        targetSource, wifiDirect.hostHasPeer, fileReceiver.senderConnected, sendProgress, receiveProgress
    ) { source, hostPeer, senderConn, sendP, recvP ->
        LinkPhase(source, hostPeer, senderConn, sendP.overallTotal, sendP.isComplete, recvP.overallTotal, recvP.isComplete)
    }.combine(hasUnresolvedBleSighting) { phase, resolving ->
        when {
            (phase.sendTotal > 0 && !phase.sendComplete) || (phase.recvTotal > 0 && !phase.recvComplete) ->
                LinkState.TRANSFERRING
            phase.source != TargetSource.NONE || phase.hostPeer || phase.senderConn -> LinkState.CONNECTED
            resolving -> LinkState.RESOLVING
            else -> LinkState.IDLE
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LinkState.IDLE)

    init {
        wifiDirect.start()
        viewModelScope.launch {
            wifiDirect.connectionInfo.collect { info ->
                // CLIENT side only: "groupFormed" also fires the instant a HOST forms its
                // own solo group (zero peers yet) - that used to be misread as "connected"
                // the moment the QR screen opened. Only trust this signal when we are
                // definitely the joining client of someone else's group.
                if (info != null && info.groupFormed && !info.isGroupOwner && info.groupOwnerAddress != null) {
                    connectWatchdogJob?.cancel()
                    connectTimeoutMessage.value = null
                    targetIp.value = info.groupOwnerAddress.hostAddress
                    targetPort.value = TRANSFER_PORT
                    targetSource.value = TargetSource.WIFI_DIRECT
                    NotificationHelper.notifyConnectionStatus(appContext, connected = true, deviceName = targetName.value)

                    pendingConnectDevice?.let { device ->
                        com.willyshare.willykez.util.RecentDevicesStore.recordConnection(appContext, device.deviceName, device.deviceAddress)
                        _recentDevices.value = com.willyshare.willykez.util.RecentDevicesStore.load(appContext)
                        pendingConnectDevice = null
                    }

                    // Real gotcha: Wi-Fi Direct's Group Owner negotiation is a genuine
                    // two-way negotiation. Setting groupOwnerIntent low is only a hint - on
                    // some chipsets/OEM pairs, or just due to the tie-breaker bit, the
                    // device that TAPPED a peer to connect (expecting to stay Client and
                    // push its cart) can end up as Group Owner instead, while the OTHER
                    // device becomes Client. Before this fix, that flip meant: the intended
                    // sender's targetIp never got set (see the isGroupOwner branch below -
                    // there wasn't one), and the intended receiver had targetIp set but
                    // nothing telling it to do anything with it. Both sides would just sit
                    // there looking "stuck," with zero feedback, indefinitely.
                    //
                    // Fix: whichever device actually lands as Client checks whether IT has
                    // a cart queued. If yes, push (unchanged, the common/expected path). If
                    // no, it's not the intended sender - auto-pull from the Group Owner
                    // instead, exactly like scanning a QR would. This makes the outcome
                    // self-correcting no matter which way the P2P negotiation actually goes.
                    //
                    // Scoped away from the QR pull flow: that flow also forms a real P2P
                    // group (Fast Connect), which fires this exact same collector once the
                    // join completes - but ScanQrBottomSheet already calls startPullSession()
                    // itself the moment the scan succeeds. pendingQrJoin covers that whole
                    // window so this doesn't race it into a second, duplicate pull attempt.
                    // transferJob == null is a second guard: WIFI_P2P_CONNECTION_CHANGED_ACTION
                    // can legitimately fire more than once for what's logically the same
                    // "still connected, nothing changed" state, and startPullSession() itself
                    // has no re-entry guard of its own - without this, a re-fire mid-pull
                    // would silently start a second, overlapping attempt at the same target.
                    if (!hasPendingCart.value && !pendingQrJoin && transferJob == null) {
                        startPullSession { }
                    }
                }
            }
        }
        viewModelScope.launch {
            // HOST side: only a real, non-empty client list counts as "someone connected."
            wifiDirect.hostHasPeer.collect { hasPeer ->
                if (hasPeer) {
                    NotificationHelper.notifyConnectionStatus(appContext, connected = true, deviceName = "Nearby device")
                }
            }
        }
        viewModelScope.launch {
            fileReceiver.senderConnected.collect { connected ->
                if (connected) NotificationHelper.notifyConnectionStatus(appContext, connected = true, deviceName = "Nearby device")
            }
        }
        viewModelScope.launch {
            receiveProgress.collect { progress ->
                if (!progress.isConnecting && progress.overallTotal > 0 && !progress.isComplete) {
                    NotificationHelper.updateProgress(appContext, isSending = false, progress)
                }
            }
        }
        viewModelScope.launch {
            sendProgress.collect { progress ->
                if (progress.overallTotal > 0 && !progress.isComplete) {
                    NotificationHelper.updateProgress(appContext, isSending = true, progress)
                }
            }
        }
        // Receiving is now always-on for the lifetime of the app process, independent of
        // which screen is open - matches how Xender/Quick Share stay reachable in the
        // background instead of only listening while a specific screen is on top.
        startReceiving()
        refreshMyQrPayload()
        // Reactive rather than a one-shot call at init{}: the persisted preference loads
        // asynchronously from DataStore, and this also handles the user flipping the
        // Settings toggle mid-session without needing a separate observer there too.
        viewModelScope.launch {
            bleFastDiscoveryEnabled.collect { enabled ->
                if (enabled) bleNearby.start() else bleNearby.stop()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        wifiDirect.stop()
        fileReceiver.stop()
        bleNearby.stop()
        SparkTransferService.stopIfIdle(appContext)
    }

    /** Call once BLE permissions are confirmed granted (mirrors [startPeerDiscovery] for
     *  Wi-Fi Direct) - safe to call repeatedly, [BleNearbyManager.start] is idempotent. */
    fun refreshBle() {
        if (bleFastDiscoveryEnabled.value) bleNearby.start()
    }

    /** Bound to the "Fast discovery (Bluetooth)" switch in Settings. Persists the choice and
     *  starts/stops BLE immediately rather than waiting for the next app launch. */
    fun setBleFastDiscoveryEnabled(enabled: Boolean) {
        viewModelScope.launch { storagePrefs.setBleFastDiscoveryEnabled(enabled) }
        if (enabled) bleNearby.start() else bleNearby.stop()
    }

    /**
     * Connects using an address a BLE sighting already resolved, skipping Wi-Fi Direct's own
     * discovery cycle entirely - see [BleNearbyManager] and [WifiDirectManager.connectByAddress].
     * Falls straight through to the same result callback / connectionInfo collector as a
     * normal peer-list tap, so nothing downstream (role self-correction, recent-devices
     * recording, notifications) needs to know which path found the peer.
     */
    fun connectToBleDevice(device: BleNearbyDevice, onStatus: (String) -> Unit) {
        val address = device.wifiP2pAddress
        if (address == null) {
            onStatus("Still identifying this device\u2026")
            return
        }
        wifiDirect.connectByAddress(address, device.name) { ok, msg ->
            onStatus(msg)
            if (ok) armConnectWatchdog()
        }
    }

    /**
     * The one "panic button" reset: tears down whatever connection/pairing state exists
     * (Wi-Fi Direct group or client link, any in-flight send) and returns everything to a
     * clean idle state. This is what was missing before - a failed/half-formed connection
     * used to just linger forever with no way to back out of it short of restarting the app.
     */
    fun resetConnection() {
        transferJob?.cancel()
        transferJob = null
        connectWatchdogJob?.cancel()
        connectTimeoutMessage.value = null
        wifiDirect.stopDiscovery()
        wifiDirect.disconnect()
        wifiDirect.stopGroup()
        targetIp.value = null
        targetName.value = null
        targetSource.value = TargetSource.NONE
        fastConnectStatus.value = null
        fileReceiver.clearLastPeer()
        // NOTE: deliberately not calling SparkTransferService.stopIfIdle() here - receiving
        // is always-on now, so the foreground service must keep running regardless of a
        // send/pairing attempt being reset. It only ever stops in onCleared()/stopReceiving().
        // Receiving itself stays on (it's always-on now) - this only clears an in-progress
        // or stuck pairing/send attempt, not the "am I reachable at all" state.
    }

    // ---------- Device file browsing (real MediaStore, no seeded data) ----------

    fun loadDeviceFiles() {
        viewModelScope.launch {
            isLoadingFiles.value = true
            val files = withContext(Dispatchers.IO) { DeviceFiles.queryAll(appContext) }
            dao.clearAllFiles()
            if (files.isNotEmpty()) dao.insertAllFiles(files)
            isLoadingFiles.value = false
        }
    }

    fun toggleFileSelection(fileId: String, currentSelected: Boolean) {
        viewModelScope.launch { dao.updateFileSelection(fileId, !currentSelected) }
    }

    fun clearSelections() {
        viewModelScope.launch { dao.clearAllSelections() }
    }

    // ---------- Full-device folder browser ----------

    /** Enter a top-level storage root (shown when [browsePathStack] is empty). */
    fun browseIntoRoot(root: StorageRoot) {
        browsePathStack.value = listOf(root.path)
        loadBrowseEntries(root.path)
    }

    /** Descend into a folder row from the current listing. */
    fun browseInto(node: LocalFileNode) {
        if (!node.isDirectory) return
        browsePathStack.value = browsePathStack.value + node.path
        loadBrowseEntries(node.path)
    }

    /** Goes up one level; true if it was able to (false = caller should leave the screen). */
    fun browseUp(): Boolean {
        val stack = browsePathStack.value
        if (stack.isEmpty()) return false
        val next = stack.dropLast(1)
        browsePathStack.value = next
        if (next.isEmpty()) {
            browseEntries.value = emptyList()
        } else {
            loadBrowseEntries(next.last())
        }
        return true
    }

    /** Jumps directly to an ancestor level from a breadcrumb tap, instead of popping one
     *  level at a time - depth 0 is the storage-root list, depth 1 is the first folder in,
     *  and so on. A no-op if already at that depth. */
    fun browseJumpTo(depth: Int) {
        val stack = browsePathStack.value
        if (depth < 0 || depth >= stack.size) return
        val next = stack.take(depth + 1)
        browsePathStack.value = next
        loadBrowseEntries(next.last())
    }

    private fun loadBrowseEntries(path: String) {
        viewModelScope.launch {
            browseLoading.value = true
            browseEntries.value = withContext(Dispatchers.IO) { LocalFileSystem.listChildren(path) }
            browseLoading.value = false
        }
    }

    fun toggleBrowseFile(path: String) {
        val current = browseSelectedFiles.value
        browseSelectedFiles.value = if (path in current) current - path else current + path
        refreshBrowseSelectionSummary()
    }

    fun toggleBrowseFolder(path: String) {
        val current = browseSelectedFolders.value
        browseSelectedFolders.value = if (path in current) current - path else current + path
        refreshBrowseSelectionSummary()
    }

    fun clearBrowseSelection() {
        browseSelectedFiles.value = emptySet()
        browseSelectedFolders.value = emptySet()
        browseSelectionSummary.value = 0 to 0L
    }

    private fun refreshBrowseSelectionSummary() {
        val files = browseSelectedFiles.value
        val folders = browseSelectedFolders.value
        viewModelScope.launch {
            val (count, bytes) = withContext(Dispatchers.IO) {
                var totalCount = files.size
                var totalBytes = files.sumOf { File(it).length() }
                folders.forEach { folderPath ->
                    val (fCount, fBytes) = LocalFileSystem.folderSummary(folderPath)
                    totalCount += fCount
                    totalBytes += fBytes
                }
                totalCount to totalBytes
            }
            browseSelectionSummary.value = count to bytes
        }
    }

    /** Resolves the current browse selection (files + whole folders) into sendable items. */
    private suspend fun resolveBrowseSendables(): List<SendableFile> = withContext(Dispatchers.IO) {
        val fromFiles = browseSelectedFiles.value.map { path ->
            val f = File(path)
            SendableFile(Uri.fromFile(f), f.name, f.length())
        }
        val fromFolders = browseSelectedFolders.value.flatMap { folderPath ->
            val root = File(folderPath)
            LocalFileSystem.collectFilesRecursively(folderPath).map { f ->
                val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                val relativePath = if (rel.isBlank()) root.name else "${root.name}/$rel"
                SendableFile(Uri.fromFile(f), f.name, f.length(), relativePath)
            }
        }
        fromFiles + fromFolders
    }

    // ---------- Wi-Fi Direct: Send flow, primary "nearby devices" option ----------

    fun startPeerDiscovery() = wifiDirect.startDiscovery()
    fun stopPeerDiscovery() = wifiDirect.stopDiscovery()

    fun connectToPeer(device: WifiP2pDevice, onStatus: (String) -> Unit) {
        targetName.value = device.deviceName
        pendingConnectDevice = device
        wifiDirect.connect(device) { ok, message ->
            onStatus(message)
            if (ok) armConnectWatchdog()
        }
    }

    /** How long a connect attempt is allowed to sit unresolved before this gives up on it -
     *  Wi-Fi Direct's own negotiation timeout can otherwise leave the UI showing
     *  "Connecting..." indefinitely with no way out short of force-closing the app. */
    private val CONNECT_WATCHDOG_MS = 15_000L
    private var connectWatchdogJob: kotlinx.coroutines.Job? = null

    /** Surfaced by Send when [armConnectWatchdog] gives up on a stuck attempt - a distinct
     *  signal from [wifiDirectError] since this is "we waited and it never resolved," not an
     *  immediate API-level failure. */
    val connectTimeoutMessage = MutableStateFlow<String?>(null)

    private fun armConnectWatchdog() {
        connectWatchdogJob?.cancel()
        connectTimeoutMessage.value = null
        connectWatchdogJob = viewModelScope.launch {
            kotlinx.coroutines.delay(CONNECT_WATCHDOG_MS)
            if (targetSource.value != TargetSource.WIFI_DIRECT) {
                wifiDirect.disconnect()
                pendingConnectDevice = null
                connectTimeoutMessage.value = "Connection timed out. Move closer and try again."
            }
        }
    }

    /** Devices this app has successfully connected to before, newest first - lets Send pin
     *  a familiar device above the plain discovery list instead of it appearing anonymously
     *  wherever discovery happens to rank it. */
    private val _recentDevices = MutableStateFlow(com.willyshare.willykez.util.RecentDevicesStore.load(appContext))
    val recentDevices: StateFlow<List<com.willyshare.willykez.util.RecentDevicesStore.RecentDevice>> = _recentDevices.asStateFlow()
    private var pendingConnectDevice: WifiP2pDevice? = null

    /** Called from Send's recent-devices list or Settings' trusted-devices row. */
    fun setDeviceTrusted(address: String, trusted: Boolean) {
        com.willyshare.willykez.util.RecentDevicesStore.setTrusted(appContext, address, trusted)
        _recentDevices.value = com.willyshare.willykez.util.RecentDevicesStore.load(appContext)
    }

    // ---------- QR pairing (Xender-style alternate option) ----------

    /** Called on the Receive screen: builds this device's own pairing QR content. */
    fun refreshMyQrPayload() {
        val ip = NetworkUtils.getLocalIpAddress()
        val suffix = UUID.randomUUID().toString().take(4).uppercase()
        val networkName = "DIRECT-sk-SharingPlus$suffix"
        val passphrase = UUID.randomUUID().toString().replace("-", "").take(12)
        // Wi-Fi Direct group creation works on every Android version via the plain
        // (non-band-forced) overload - it's the reliable primary path now, not gated
        // behind "High-speed Mode" or an existing shared Wi-Fi network. The old code only
        // fell back to sharing this device's regular Wi-Fi IP, which is null for anyone
        // who isn't already on a router (i.e. most people who actually need this app).
        wifiDirect.createFastGroup(networkName, passphrase, preferHighSpeed = highSpeedMode.value) { success, message ->
            fastConnectStatus.value = message
            myQrPayload.value = when {
                success -> QrPairing.buildFastConnectPayload(
                    wifiDirect.thisDeviceName.value, ip ?: "0.0.0.0", TRANSFER_PORT, networkName, passphrase
                )
                ip != null -> QrPairing.buildPayload(wifiDirect.thisDeviceName.value, ip, TRANSFER_PORT)
                else -> null
            }
        }
    }

    fun setHighSpeedMode(enabled: Boolean) {
        if (highSpeedMode.value == enabled) return
        highSpeedMode.value = enabled
        // The group itself stays up either way now - only the band preference changes -
        // so just recreate it with the new preference instead of tearing pairing down.
        refreshMyQrPayload()
    }

    /** Called on the Send screen after a successful QR scan of another device's code. */
    fun applyScannedPayload(raw: String): Boolean {
        val parsed = QrPairing.parsePayload(raw) ?: return false
        targetName.value = parsed.deviceName
        // Always set the LAN ip/port bundled in the QR first. If this is a high-speed
        // (Wi-Fi Direct Fast Connect) code, the connectionInfo collector in init{}
        // will overwrite targetIp with the P2P group owner's address - the 5GHz link -
        // once the join below succeeds; on failure we simply keep using this LAN address.
        targetIp.value = parsed.ip
        targetPort.value = parsed.port
        targetSource.value = TargetSource.QR_PAIR
        if (parsed.isFastConnect && wifiDirect.isFastConnectSupported) {
            pendingQrJoin = true
            wifiDirect.joinFastGroup(parsed.fastConnectNetworkName!!, parsed.fastConnectPassphrase!!) { _, message ->
                fastConnectStatus.value = message
                pendingQrJoin = false
            }
        }
        return true
    }

    fun clearTarget() {
        targetIp.value = null
        targetName.value = null
        targetSource.value = TargetSource.NONE
        wifiDirect.disconnect()
    }

    // ---------- Receiving files ----------

    // ---------- Stage 4: pre-transfer match-code confirmation ----------

    /** One pending prompt at a time. [isIncoming] drives which UI PinConfirmationOverlay shows:
     *  - true  = someone connected TO us (we're the acceptor) - ACTIVE prompt, Confirm/Decline,
     *            exactly like Quick Share's "Elia's phone wants to share a file, PIN 1639" gate.
     *  - false = we dialed out (we're the dialer) - PASSIVE display only, since we already
     *            deliberately chose this target; just shows the code + "Waiting..." + Cancel,
     *            matching Quick Share's own sender-side screen in the reference screenshot. */
    data class PendingPinConfirmation(
        val pin: String,
        val peerLabel: String,
        val isIncoming: Boolean,
        val isPullIntent: Boolean
    )

    val pinConfirmation = MutableStateFlow<PendingPinConfirmation?>(null)
    private var pinDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private var activeHandshakeChannel: SocketChannel? = null

    /** Acceptor side only: surfaces the ACTIVE overlay and suspends until the local user taps
     *  Confirm/Decline, or [HANDSHAKE_UI_TIMEOUT_MS] passes with no answer (auto-decline -
     *  never leave a stale prompt blocking a socket thread forever). */
    private suspend fun requestLocalPinConfirm(pin: String, peerLabel: String, isPullIntent: Boolean): Boolean {
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        pinDeferred = deferred
        pinConfirmation.value = PendingPinConfirmation(pin, peerLabel, isIncoming = true, isPullIntent = isPullIntent)
        val result = kotlinx.coroutines.withTimeoutOrNull(HANDSHAKE_UI_TIMEOUT_MS) { deferred.await() } ?: false
        pinConfirmation.value = null
        pinDeferred = null
        return result
    }

    /** Dialer side only: just shows the code passively - no gate, since dialing out already
     *  was this device's own affirmative action (picking a peer, or scanning their QR). */
    private fun showWaitingForPeer(pin: String, peerLabel: String, isPullIntent: Boolean) {
        pinConfirmation.value = PendingPinConfirmation(pin, peerLabel, isIncoming = false, isPullIntent = isPullIntent)
    }

    private fun clearPinPrompt() {
        pinConfirmation.value = null
        activeHandshakeChannel = null
    }

    /** Called from PinConfirmationOverlay's Confirm/Decline buttons (acceptor side only). */
    fun respondToPinConfirmation(accept: Boolean) {
        pinDeferred?.complete(accept)
    }

    /** Called from PinConfirmationOverlay's Cancel button (dialer side only, while passively
     *  waiting). Closing the channel makes the blocking read in performPinHandshake fail
     *  immediately instead of sitting there until the full handshake timeout. */
    fun cancelPendingHandshake() {
        try { activeHandshakeChannel?.close() } catch (_: Exception) {}
        clearPinPrompt()
    }

    fun startReceiving() {
        SparkTransferService.start(appContext)
        fileReceiver.start { savedPath, size -> recordReceivedFile(savedPath, size) }
    }

    fun stopReceiving() {
        fileReceiver.stop()
        SparkTransferService.stopIfIdle(appContext)
    }

    private fun displayNameFromSavedPath(savedPath: String): String =
        if (savedPath.startsWith("content://")) {
            Uri.parse(savedPath).lastPathSegment?.substringAfterLast('/') ?: "received_file"
        } else {
            File(savedPath).name
        }

    // ---------- Sending files ----------

    /** The in-flight send coroutine, if any - kept so [cancelTransferSession] can actually stop it. */
    private var transferJob: kotlinx.coroutines.Job? = null
    /** True for the duration of a QR Fast Connect join attempt (applyScannedPayload to its
     *  own completion callback). Needed because the connectionInfo collector's Client
     *  branch unconditionally overwrites targetSource to WIFI_DIRECT the moment ANY group
     *  forms - including one this device joined via a scanned QR - so targetSource itself
     *  can't be used there to tell a QR join apart from a peer-list connect. Without this,
     *  the auto-pull fix below would race ScanQrBottomSheet's own startPullSession() call
     *  and fire a second, duplicate pull attempt at the same target. */
    private var pendingQrJoin = false

    /**
     * No foreground service, on its own, guarantees the CPU stays awake between network I/O
     * bursts on every OEM skin - some are aggressive enough to let the radio/CPU nap between
     * packets even with a foreground service running, which shows up as a transfer that
     * stalls or crawls once the screen turns off. A partial wake lock held only for the
     * duration of an actual active transfer (never while just idly listening) closes that
     * gap without the battery cost of holding one for the app's entire always-on-receiving
     * lifetime. The 10-minute timeout is a safety cap, not the expected duration - if a
     * transfer is still running past that, [acquireTransferWakeLock] is called again
     * wherever it's still needed, and if release is ever missed due to a bug, this timeout
     * guarantees it can't drain the battery indefinitely.
     */
    private var transferWakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireTransferWakeLock() {
        try {
            if (transferWakeLock?.isHeld == true) return
            val pm = appContext.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager ?: return
            transferWakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SharingPlus:activeTransfer")
            transferWakeLock?.acquire(10 * 60 * 1000L)
        } catch (_: Exception) {
            // Never let wake lock bookkeeping itself take down a transfer.
        }
    }

    private fun releaseTransferWakeLock() {
        try {
            transferWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
    }

    /** The full pending cart right now, from every source (MediaStore picks, folder browser,
     *  and files handed in via another app's share sheet) - used by both the normal push
     *  flow ([startTransferSession]) and the pull-response flow ([handleIncomingPullRequest]). */
    private suspend fun resolveCurrentCart(): Triple<List<FileItemEntity>, List<SendableFile>, List<SendableFile>> {
        val selected = allFiles.value.filter { it.isSelected }
        val fromBrowser = resolveBrowseSendables()
        val fromShareIntent = pendingSharedFiles.value
        return Triple(selected, fromBrowser, fromShareIntent)
    }

    /** [FileItemEntity.category] uses plural on-device-browser labels ("Photos", "Videos",
     *  "Documents", "Apps", "Audio"); [TransferEntity.category] uses the singular vocabulary
     *  [categoryForFile] also produces ("PHOTO", "VIDEO", "DOC", "APP", "AUDIO", "ARCHIVE") -
     *  a plain `.uppercase()` only happens to matches for Audio, so every sent Photo/Video/
     *  Document/App was being logged under the wrong category and silently missing both its
     *  icon and (now) its thumbnail in History. */
    private fun mapPickerCategory(category: String): String = when (category) {
        "Photos" -> "PHOTO"
        "Videos" -> "VIDEO"
        "Documents" -> "DOC"
        "Apps" -> "APP"
        "Audio" -> "AUDIO"
        else -> category.uppercase()
    }

    /** Shared by both send paths: writes history rows for a completed send and clears the cart. */
    private suspend fun recordSentHistory(selected: List<FileItemEntity>, fromBrowser: List<SendableFile>, fromShareIntent: List<SendableFile>) {
        selected.forEach { f ->
            dao.insertTransfer(
                TransferEntity(
                    id = UUID.randomUUID().toString(),
                    fileName = f.name,
                    category = mapPickerCategory(f.category),
                    sizeBytes = f.sizeBytes,
                    timestamp = System.currentTimeMillis(),
                    deviceName = targetName.value ?: "Nearby device",
                    isSend = true,
                    status = "COMPLETED",
                    sourceUri = f.uri
                )
            )
        }
        (fromBrowser + fromShareIntent).forEach { f ->
            dao.insertTransfer(
                TransferEntity(
                    id = UUID.randomUUID().toString(),
                    fileName = f.name,
                    category = categoryForFile(f.name),
                    sizeBytes = f.sizeBytes,
                    timestamp = System.currentTimeMillis(),
                    deviceName = targetName.value ?: "Nearby device",
                    isSend = true,
                    status = "COMPLETED",
                    sourceUri = f.uri.toString()
                )
            )
        }
        dao.clearAllSelections()
        clearBrowseSelection()
        pendingSharedFiles.value = emptyList()
    }

    /**
     * Fires when someone scans this device's QR and connects to *pull* the queued cart,
     * instead of the traditional flow where this device dials out and pushes. Runs
     * synchronously on [FileReceiveServer]'s own background pool thread (see the
     * onPullRequested doc comment on that class) - blocking here is fine, the same way the
     * rest of this file's raw socket I/O already blocks its own worker threads.
     *
     * Reuses the existing [fileSender] instance, so its progress flows into the same
     * [sendProgress] that TransferringScreen already displays - no separate UI path needed.
     *
     * Known limitation: unlike [startTransferSession], this isn't tracked via [transferJob],
     * so [cancelTransferSession] can't currently interrupt an in-flight pull-triggered push.
     * Cancelling would need a cooperative check inside FileSenderClient's write loop, which
     * is a larger change than this pass covers.
     */
    private fun handleIncomingPullRequest(channel: SocketChannel) {
        val (selected, fromBrowser, fromShareIntent) = kotlinx.coroutines.runBlocking { resolveCurrentCart() }
        val fromMediaStore = selected.map { SendableFile(Uri.parse(it.uri), it.name, it.sizeBytes) }
        val sendables = fromMediaStore + fromBrowser + fromShareIntent
        if (sendables.isEmpty()) {
            try { channel.close() } catch (_: Exception) {}
            return
        }
        SparkTransferService.start(appContext)
        acquireTransferWakeLock()
        try {
            val success = fileSender.pushOverAcceptedChannel(channel, sendables)
            if (success) {
                kotlinx.coroutines.runBlocking { recordSentHistory(selected, fromBrowser, fromShareIntent) }
            }
        } finally {
            releaseTransferWakeLock()
        }
    }

    /**
     * Called after successfully scanning another device's QR: connects out to them and
     * *pulls* their queued cart, the mirror image of [startTransferSession]. Progress flows
     * into [receiveProgress] exactly like an ordinary incoming transfer, so ReceiveScreen's
     * existing UI needs no special-casing for this path.
     */
    fun startPullSession(onComplete: (Boolean) -> Unit) {
        val ip = targetIp.value
        if (ip == null) {
            onComplete(false)
            return
        }
        SparkTransferService.start(appContext)
        acquireTransferWakeLock()
        transferJob = viewModelScope.launch {
            var success = false
            try {
                val handshakeOk = performPinHandshake(
                    ip, targetPort.value, wifiDirect.thisDeviceName.value, isPull = true,
                    onChannelReady = { ch -> activeHandshakeChannel = ch },
                    onWaitingForPeer = { pin -> showWaitingForPeer(pin, targetName.value ?: "device", isPullIntent = true) }
                )
                clearPinPrompt()
                success = handshakeOk && withContext(Dispatchers.IO) {
                    fileReceiver.pullFrom(ip, targetPort.value) { savedPath, size ->
                        recordReceivedFile(savedPath, size)
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // User hit Cancel - not an error, just stop quietly.
            } catch (t: Throwable) {
                success = false
            } finally {
                releaseTransferWakeLock()
                transferJob = null
                onComplete(success)
            }
        }
    }

    /** Shared by [startReceiving]'s always-on listener and [startPullSession] - records one
     *  received file into history and notifies, regardless of which path brought it in. */
    private fun recordReceivedFile(savedPath: String, size: Long) {
        val fileName = displayNameFromSavedPath(savedPath)
        viewModelScope.launch {
            dao.insertTransfer(
                TransferEntity(
                    id = UUID.randomUUID().toString(),
                    fileName = fileName,
                    category = categoryForFile(fileName),
                    sizeBytes = size,
                    timestamp = System.currentTimeMillis(),
                    deviceName = targetName.value ?: "Nearby device",
                    isSend = false,
                    status = "COMPLETED",
                    savedPath = savedPath
                )
            )
        }
        NotificationHelper.notifyFileReceived(appContext, fileName)
    }

    fun startTransferSession(onComplete: (Boolean) -> Unit) {
        val ip = targetIp.value
        if (ip == null) {
            onComplete(false)
            return
        }
        SparkTransferService.start(appContext)
        acquireTransferWakeLock()
        transferJob = viewModelScope.launch {
            // Wrapped end-to-end: any unexpected exception here (a bad URI, a database
            // hiccup, a socket dying mid-write) must never crash the app - it should just
            // surface as a failed transfer with an error message on screen.
            var success = false
            try {
                val (selected, fromBrowser, fromShareIntent) = resolveCurrentCart()
                val fromMediaStore = selected.map { SendableFile(Uri.parse(it.uri), it.name, it.sizeBytes) }
                val sendables = fromMediaStore + fromBrowser + fromShareIntent
                val handshakeOk = performPinHandshake(
                    ip, targetPort.value, wifiDirect.thisDeviceName.value, isPull = false,
                    onChannelReady = { ch -> activeHandshakeChannel = ch },
                    onWaitingForPeer = { pin -> showWaitingForPeer(pin, targetName.value ?: "device", isPullIntent = false) }
                )
                clearPinPrompt()
                success = handshakeOk && withContext(Dispatchers.IO) {
                    fileSender.send(ip, sendables)
                }
                if (success) {
                    recordSentHistory(selected, fromBrowser, fromShareIntent)
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // User hit Cancel - not an error, just stop quietly.
            } catch (t: Throwable) {
                success = false
            } finally {
                // Deliberately not stopping the service here - it's the same always-on
                // foreground service keeping receiving alive; a finished/failed send must
                // not tear that down.
                releaseTransferWakeLock()
                transferJob = null
                onComplete(success)
            }
        }
    }

    /** Called from the Cancel action on the transferring screen: actually stops the send instead of just navigating away. */
    fun cancelTransferSession() {
        transferJob?.cancel()
        transferJob = null
        releaseTransferWakeLock()
        // Not stopping the service - same reasoning as above, receiving stays up.
    }

    // ---------- Files shared into Sharing Plus from another app (Gallery, Files, etc.) ----------

    /** Files handed to us via ACTION_SEND / ACTION_SEND_MULTIPLE from another app, awaiting a pick target. */
    val pendingSharedFiles = MutableStateFlow<List<SendableFile>>(emptyList())

    fun setPendingSharedFiles(files: List<SendableFile>) {
        pendingSharedFiles.value = files
    }

    /**
     * True the instant the user has picked *anything* to send - from MediaStore, from the
     * folder browser, or from another app's share sheet - regardless of whether a device is
     * connected yet. This is what makes "pick files first, then connect" possible: the Send
     * screen checks this to decide whether to jump straight to Transferring once a device is
     * found, instead of always routing back through the picker.
     */
    val hasPendingCart: StateFlow<Boolean> = combine(
        allFiles, browseSelectionSummary, pendingSharedFiles
    ) { files, browseSummary, shared ->
        files.any { it.isSelected } || browseSummary.first > 0 || shared.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ---------- History ----------

    fun deleteTransfer(transfer: TransferEntity) {
        viewModelScope.launch { dao.deleteTransfer(transfer) }
    }

    fun clearAllHistory() {
        viewModelScope.launch { dao.clearAllTransfers() }
    }

    private fun categoryForFile(name: String): String = when {
        name.endsWith(".jpg", true) || name.endsWith(".png", true) || name.endsWith(".jpeg", true) -> "PHOTO"
        name.endsWith(".mp4", true) || name.endsWith(".mov", true) || name.endsWith(".mkv", true) -> "VIDEO"
        name.endsWith(".mp3", true) || name.endsWith(".m4a", true) || name.endsWith(".wav", true) -> "AUDIO"
        name.endsWith(".apk", true) -> "APP"
        name.endsWith(".zip", true) || name.endsWith(".rar", true) -> "ARCHIVE"
        else -> "DOC"
    }
}
