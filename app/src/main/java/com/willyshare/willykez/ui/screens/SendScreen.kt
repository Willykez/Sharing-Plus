package com.willyshare.willykez.ui.screens

import android.Manifest
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.willyshare.willykez.ui.AuroraBackground
import com.willyshare.willykez.ui.InPageHeader
import com.willyshare.willykez.ui.PulseViewModel
import com.willyshare.willykez.ui.RadarPulseRing
import com.willyshare.willykez.ui.SleekFloatingPillButton
import com.willyshare.willykez.ui.TargetSource
import com.willyshare.willykez.ui.theme.SleekCard
import com.willyshare.willykez.ui.theme.SleekOnSurface
import com.willyshare.willykez.ui.theme.SleekOnSurfaceVariant
import com.willyshare.willykez.ui.theme.SleekOutline
import com.willyshare.willykez.ui.theme.SleekPrimary
import com.willyshare.willykez.ui.theme.SleekPrimaryContainer
import com.willyshare.willykez.util.WifiEnableHelper
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale

@OptIn(ExperimentalPermissionsApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    viewModel: PulseViewModel,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val permissionsState = rememberMultiplePermissionsState(requiredPermissions)

    val peers by viewModel.discoveredDevices.collectAsState()
    // BLE-only sightings: spotted over Bluetooth in under a second, but Wi-Fi Direct's own
    // discoverPeers() hasn't (yet, or ever, on some OEM stacks) surfaced them itself. Once a
    // device shows up in `peers` too, it's dropped from here so it isn't listed twice.
    val bleDevices by viewModel.nearbyBleDevices.collectAsState()
    val bleActive by viewModel.bleNearby.isActive.collectAsState()
    val bleFastDiscoveryEnabled by viewModel.bleFastDiscoveryEnabled.collectAsState()
    val wifiDirectAddresses = remember(peers) { peers.map { it.deviceAddress }.toSet() }
    val bleOnlyDevices = remember(bleDevices, wifiDirectAddresses) {
        bleDevices.filter { it.wifiP2pAddress != null && it.wifiP2pAddress !in wifiDirectAddresses }
    }
    val connectTimeoutMessage by viewModel.connectTimeoutMessage.collectAsState()
    val recentDevices by viewModel.recentDevices.collectAsState()
    val recentAddresses = remember(recentDevices) { recentDevices.map { it.address }.toSet() }
    val trustedAddresses = remember(recentDevices) { recentDevices.filter { it.trusted }.map { it.address }.toSet() }
    val sortedPeers = remember(peers, recentAddresses) {
        peers.sortedByDescending { it.deviceAddress in recentAddresses }
    }
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val targetIp by viewModel.targetIp.collectAsState()
    val targetSource by viewModel.targetSource.collectAsState()
    val hasPendingCart by viewModel.hasPendingCart.collectAsState()
    val senderConnected by viewModel.senderConnected.collectAsState()
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var connectingTo by remember { mutableStateOf<String?>(null) }
    var wifiEnabled by remember { mutableStateOf(WifiEnableHelper.isWifiEnabled(context)) }
    var bluetoothEnabled by remember { mutableStateOf(com.willyshare.willykez.util.BluetoothEnableHelper.isBluetoothEnabled(context)) }
    var showQrSheet by remember { mutableStateOf(false) }
    // Guards the auto-reconnect effect below so it only ever fires once per screen visit -
    // without this, if the user manually backs out of an auto-started connection, the exact
    // same trusted device reappearing in the next discovery tick would just re-trigger it.
    var autoReconnectAttempted by remember { mutableStateOf(false) }

    // "Recently connected" quick-reconnect, for trusted devices specifically: skip the tap
    // entirely and dial the moment a device this user has explicitly trusted comes back into
    // range, rather than making them find and tap it again in the list every time.
    LaunchedEffect(sortedPeers, trustedAddresses) {
        if (autoReconnectAttempted || targetIp != null || connectingTo != null) return@LaunchedEffect
        val trustedPeer = sortedPeers.firstOrNull { it.deviceAddress in trustedAddresses } ?: return@LaunchedEffect
        autoReconnectAttempted = true
        connectingTo = trustedPeer.deviceAddress
        viewModel.connectToPeer(trustedPeer) { msg -> statusMessage = msg }
    }

    // Re-check whenever the screen resumes (e.g. coming back from the Wi-Fi panel/Settings,
    // or dismissing the Bluetooth enable dialog).
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                wifiEnabled = WifiEnableHelper.isWifiEnabled(context)
                bluetoothEnabled = com.willyshare.willykez.util.BluetoothEnableHelper.isBluetoothEnabled(context)
                if (bluetoothEnabled) viewModel.refreshBle()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ACTION_REQUEST_ENABLE's system dialog turns Bluetooth on directly - the result callback
    // just needs to refresh the banner/BLE state, since the adapter is already on by the time
    // this fires (or the user declined and it's still off).
    val bluetoothEnableLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        bluetoothEnabled = com.willyshare.willykez.util.BluetoothEnableHelper.isBluetoothEnabled(context)
        if (bluetoothEnabled) viewModel.refreshBle()
    }

    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        // Android's discoverPeers() is a ONE-SHOT call - the OS silently stops scanning
        // after roughly two minutes with no callback telling the app it happened. Without
        // this loop, isDiscovering would keep showing "Scanning..." indefinitely while
        // nothing was actually happening, and any device that walked into range after that
        // ~2 minute window would simply never be found. Re-issuing the call periodically
        // is the standard workaround; it's a cheap no-op if a scan is already in progress.
        if (permissionsState.allPermissionsGranted) {
            viewModel.refreshBle()
            while (true) {
                viewModel.startPeerDiscovery()
                kotlinx.coroutines.delay(25_000)
            }
        }
    }

    LaunchedEffect(connectTimeoutMessage) {
        connectTimeoutMessage?.let {
            connectingTo = null
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopPeerDiscovery() }
    }

    LaunchedEffect(targetIp, targetSource) {
        if (targetIp != null && targetSource == TargetSource.WIFI_DIRECT) {
            // "Pick files first" flow: the cart already has something queued (picked from
            // Choose Files, the folder browser, or a share-sheet hand-off) - skip the picker
            // entirely and go straight to sending, exactly like Quick Share does once a
            // target is found. Otherwise, this is the original "connect first" flow: go pick
            // files now that we know who we're sending to.
            onNavigate(if (hasPendingCart) "transfer" else "select")
        }
    }

    LaunchedEffect(senderConnected, hasPendingCart, targetIp) {
        // Covers the Group Owner role-flip: this device tapped a peer expecting to end up
        // as the Client (and get targetIp set above), but Wi-Fi Direct's negotiation can
        // still land it as Group Owner instead. When that happens targetIp never gets set
        // here, so without this, the screen just sits on the peer list looking stuck with
        // zero feedback - the connection actually succeeded, it just formed the "other way
        // around." senderConnected firing while we still have a cart and never got our own
        // targetIp is the signal that someone (now the real Client) connected to us and, per
        // the matching fix in PulseViewModel, is about to pull our queued cart - so follow
        // the exact same path MyQrScreen uses and go watch it happen.
        if (senderConnected && hasPendingCart && targetIp == null) {
            onNavigate("transfer")
        }
    }

    Scaffold(
        containerColor = Color.Transparent
    ) { innerPadding ->
        AuroraBackground(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    InPageHeader(
                        title = "Send",
                        subtitle = if (isDiscovering) "Scanning for nearby devices\u2026" else "Nearby devices",
                        showBack = true,
                        onBack = { onNavigate("dashboard") }
                    )
                    if (!permissionsState.allPermissionsGranted) {
                        PermissionRationaleCard(
                            showSettingsHint = permissionsState.permissions.any { it.status.shouldShowRationale },
                            onRequest = { permissionsState.launchMultiplePermissionRequest() }
                        )
                        return@Column
                    }

                    if (!wifiEnabled) {
                        WifiOffBanner(
                            onEnable = { context.startActivity(WifiEnableHelper.requestEnable(context)) }
                        )
                    }
                    // Shown only once Wi-Fi is already on - Wi-Fi Direct is the radio that's
                    // actually required for a transfer, so that banner takes priority; showing
                    // both stacked when neither is on would bury the more important one. Also
                    // hidden entirely if the person turned off "Fast discovery (Bluetooth)" in
                    // Settings - no point prompting to enable a radio the app won't even use.
                    if (wifiEnabled && bleFastDiscoveryEnabled && !bluetoothEnabled) {
                        BluetoothOffBanner(
                            onEnable = { bluetoothEnableLauncher.launch(com.willyshare.willykez.util.BluetoothEnableHelper.requestEnableIntent()) }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            // Two rings at different speeds - the fast, tight one reflects BLE
                            // sensing (near-instant), the slower wide one is Wi-Fi Direct's own
                            // discovery cycle. Two speeds read as "two radios searching"
                            // without needing any extra text.
                            if (bleActive) {
                                RadarPulseRing(50, 0, durationMillis = 1400)
                            }
                            if (isDiscovering) {
                                RadarPulseRing(76, 0)
                                RadarPulseRing(58, 700)
                            }
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(SleekPrimaryContainer)
                                    .border(1.5.dp, SleekPrimary.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isDiscovering) {
                                    CircularProgressIndicator(color = SleekPrimary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                                } else {
                                    Icon(com.willyshare.willykez.ui.PulseIcons.Broadcasting, contentDescription = null, tint = SleekPrimary, modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            val totalCount = peers.size + bleOnlyDevices.size
                            Text(
                                text = if (totalCount == 0) (if (isDiscovering) "Scanning\u2026" else "No devices yet") else "$totalCount device${if (totalCount == 1) "" else "s"} nearby",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                color = SleekOnSurface
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = com.willyshare.willykez.ui.PulseIcons.Broadcasting,
                                    contentDescription = null,
                                    tint = SleekOnSurfaceVariant,
                                    modifier = Modifier.size(11.dp)
                                )
                                if (bleActive) {
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Icon(
                                        imageVector = com.willyshare.willykez.ui.PulseIcons.Bluetooth,
                                        contentDescription = null,
                                        tint = SleekOnSurfaceVariant,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (bleActive) "Bluetooth + Wi-Fi Direct \u00B7 visible nearby" else "Wi-Fi Direct \u00B7 broadcasting as visible",
                                    fontSize = 11.sp,
                                    color = SleekOnSurfaceVariant
                                )
                            }
                        }
                    }

                    if (connectTimeoutMessage != null) {
                        ConnectTimeoutCard(
                            message = connectTimeoutMessage!!,
                            onShowQr = { onNavigate("my_qr") },
                            onDismiss = { viewModel.connectTimeoutMessage.value = null }
                        )
                    } else {
                        statusMessage?.let {
                            Text(
                                text = it,
                                fontSize = 13.sp,
                                color = SleekOnSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (peers.isEmpty() && bleOnlyDevices.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (isDiscovering) "Looking for nearby devices\u2026" else "No devices found yet",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekOnSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Make sure Wi-Fi and Bluetooth are on, and the receiver has Sharing Plus open on the Receive screen.",
                                fontSize = 12.sp,
                                color = SleekOnSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.startPeerDiscovery() },
                                shape = RoundedCornerShape(999.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary)
                            ) {
                                Text("Scan again", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        // One unified list, not two - Wi-Fi Direct-resolved devices (trusted
                        // first) followed by anything only BLE has found so far. A per-row
                        // badge communicates which radio found each device instead of a
                        // section split, matching how Quick Share never exposes "which radio"
                        // as a separate list.
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(sortedPeers, key = { it.deviceAddress }) { device ->
                                PeerRow(
                                    device = device,
                                    isConnecting = connectingTo == device.deviceAddress,
                                    isRecent = device.deviceAddress in recentAddresses,
                                    isTrusted = device.deviceAddress in trustedAddresses,
                                    onToggleTrust = if (device.deviceAddress in recentAddresses) {
                                        { viewModel.setDeviceTrusted(device.deviceAddress, device.deviceAddress !in trustedAddresses) }
                                    } else null,
                                    modifier = Modifier.animateItem(),
                                    onClick = {
                                        connectingTo = device.deviceAddress
                                        viewModel.connectToPeer(device) { msg -> statusMessage = msg }
                                    }
                                )
                            }
                            items(bleOnlyDevices, key = { it.bleAddress }) { device ->
                                BlePeerRow(
                                    device = device,
                                    isConnecting = connectingTo == device.bleAddress,
                                    modifier = Modifier.animateItem(),
                                    onClick = {
                                        connectingTo = device.bleAddress
                                        viewModel.connectToBleDevice(device) { msg -> statusMessage = msg }
                                    }
                                )
                            }
                            item { Spacer(modifier = Modifier.height(90.dp)) }
                        }
                    }
                }

                if (permissionsState.allPermissionsGranted) {
                    SleekFloatingPillButton(
                        text = "Show my QR",
                        icon = Icons.Default.QrCode2,
                        onClick = { showQrSheet = true },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                    )
                }
            }
        }
    }

    if (showQrSheet) {
        MyQrBottomSheet(
            viewModel = viewModel,
            onDismiss = { showQrSheet = false },
            onNavigate = onNavigate
        )
    }
}

/** The three/four-state connection badge (recommendation: a visible dot + label per row
 *  instead of only RSSI or a bare "Connecting..." string) - shared by [PeerRow] and
 *  [BlePeerRow] so a Wi-Fi Direct-resolved device and a BLE-only sighting read as the same
 *  kind of status, just with a different source noted after the dash. */
private enum class PeerBadgeState(val label: String, val color: @Composable () -> Color) {
    SPOTTED("Spotted", { SleekOnSurfaceVariant }),
    NEARBY("Nearby", { SleekOnSurfaceVariant }),
    BUSY("Busy right now", { Color(0xFFB26A00) }),
    CONNECTING("Connecting\u2026", { SleekPrimary }),
}

@Composable
private fun PeerStatusRow(state: PeerBadgeState, source: String) {
    val dotColor = state.color()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = "${state.label} \u00B7 $source",
            fontSize = 11.sp,
            color = if (state == PeerBadgeState.CONNECTING) SleekPrimary else SleekOnSurfaceVariant
        )
    }
}

@Composable
private fun PeerRow(
    device: WifiP2pDevice,
    isConnecting: Boolean,
    isRecent: Boolean = false,
    isTrusted: Boolean = false,
    onToggleTrust: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val accentColor = SleekPrimary
    val rowBg by androidx.compose.animation.animateColorAsState(
        targetValue = if (isConnecting) SleekPrimaryContainer.copy(alpha = 0.5f) else SleekCard,
        label = "peer_row_bg"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(rowBg)
            .drawBehind {
                drawLine(
                    color = accentColor.copy(alpha = 0.5f),
                    start = androidx.compose.ui.geometry.Offset(size.width * 0.05f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width * 0.4f, 0f),
                    strokeWidth = 2.dp.toPx()
                )
            }
            .clickable(enabled = !isConnecting) { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(SleekPrimaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(com.willyshare.willykez.ui.PulseIcons.Device, contentDescription = null, tint = SleekPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.deviceName.ifBlank { "Unknown device" }, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SleekOnSurface)
                    if (isRecent && !isConnecting) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(SleekPrimary.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("RECENT", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = SleekPrimary, letterSpacing = 0.5.sp)
                        }
                    }
                }
                PeerStatusRow(
                    state = if (isConnecting) PeerBadgeState.CONNECTING else PeerBadgeState.NEARBY,
                    source = "Wi-Fi Direct"
                )
            }
        }
        if (isConnecting) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = SleekPrimary)
        } else if (onToggleTrust != null) {
            Icon(
                imageVector = if (isTrusted) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = if (isTrusted) "Trusted - tap to remove" else "Tap to trust this device",
                tint = if (isTrusted) SleekPrimary else SleekOnSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(22.dp)
                    .clickable { onToggleTrust() }
            )
        }
    }
}

/** A device spotted over BLE whose Wi-Fi Direct address is already known - tapping dials
 *  [PulseViewModel.connectToBleDevice] directly, skipping `discoverPeers()` entirely. If the
 *  GATT read hasn't resolved an address yet, this stays disabled rather than dialing nothing. */
@Composable
private fun BlePeerRow(
    device: com.willyshare.willykez.net.BleNearbyDevice,
    isConnecting: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val canConnect = device.wifiP2pAddress != null && device.readyToReceive
    val badgeState = when {
        isConnecting -> PeerBadgeState.CONNECTING
        device.wifiP2pAddress == null -> PeerBadgeState.SPOTTED
        !device.readyToReceive -> PeerBadgeState.BUSY
        else -> PeerBadgeState.NEARBY
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isConnecting) SleekPrimaryContainer.copy(alpha = 0.5f) else SleekCard)
            .border(1.dp, SleekOutline.copy(alpha = 0.3f), shape)
            .clickable(enabled = !isConnecting && canConnect) { onClick() }
            .padding(12.dp)
            .alpha(if (canConnect || isConnecting) 1f else 0.55f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(SleekPrimaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isConnecting) com.willyshare.willykez.ui.PulseIcons.BluetoothConnected else com.willyshare.willykez.ui.PulseIcons.BluetoothSearching,
                    contentDescription = null,
                    tint = SleekPrimary,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(device.name.ifBlank { "Unknown device" }, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SleekOnSurface)
                PeerStatusRow(state = badgeState, source = "Bluetooth")
            }
        }
        if (isConnecting) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = SleekPrimary)
        }
    }
}

/** Shown when [PulseViewModel]'s connect watchdog gives up on a stuck attempt - surfaces the
 *  proven QR-pairing fallback right here instead of just saying "try again" and leaving the
 *  person to rediscover that path on their own. */
@Composable
private fun ConnectTimeoutCard(message: String, onShowQr: () -> Unit, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(shape)
            .background(Color(0xFFB26A00).copy(alpha = 0.10f))
            .border(1.dp, Color(0xFFB26A00).copy(alpha = 0.3f), shape)
            .padding(14.dp)
    ) {
        Text(message, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SleekOnSurface)
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onShowQr,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Filled.QrCode2, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Show my QR instead", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = SleekOnSurfaceVariant),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Dismiss", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun WifiOffBanner(onEnable: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SleekCard)
            .border(1.dp, SleekOutline.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Wi-Fi is off", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SleekOnSurface)
            Text(
                "Wi-Fi Direct needs Wi-Fi turned on to find nearby devices.",
                fontSize = 11.sp,
                color = SleekOnSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Button(
            onClick = onEnable,
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary)
        ) {
            Text("Enable", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BluetoothOffBanner(onEnable: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SleekCard)
            .border(1.dp, SleekOutline.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Bluetooth is off", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SleekOnSurface)
            Text(
                "Turn it on to spot nearby devices instantly, before Wi-Fi Direct catches up.",
                fontSize = 11.sp,
                color = SleekOnSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Button(
            onClick = onEnable,
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary)
        ) {
            Text("Enable", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PermissionRationaleCard(showSettingsHint: Boolean, onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(com.willyshare.willykez.ui.PulseIcons.TargetPin, contentDescription = null, tint = SleekPrimary, modifier = Modifier.size(40.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Nearby device permission needed",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = SleekOnSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "Sharing Plus needs this permission to discover nearby devices over Wi-Fi Direct.",
            fontSize = 13.sp,
            color = SleekOnSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Bluetooth lets Sharing Plus find nearby devices instantly instead of waiting on Wi-Fi scanning.",
            fontSize = 13.sp,
            color = SleekOnSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRequest,
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary)
        ) {
            Text(if (showSettingsHint) "Grant permission" else "Allow", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
