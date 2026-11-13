package com.example.p2p.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Wi-Fi Direct transport implementation for Phase 1:
 * Supports genuine hardware capability check and peer discovery via WifiP2pManager,
 * while honestly reporting that socket-level data transfer is deferred to a later phase.
 */
class WifiDirectTransport(
    context: Context,
    private val capabilityDetector: CapabilityDetector
) : P2pTransport {

    override val type: TransportType = TransportType.WIFI_DIRECT

    private val appContext = context.applicationContext
    private val wifiP2pManager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    private val _events = MutableSharedFlow<TransportEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<TransportEvent> = _events.asSharedFlow()

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) return
            val ch = channel ?: return
            if (!capabilityDetector.hasWifiDirectPermissions()) return
            wifiP2pManager?.requestPeers(ch) { peers ->
                peers.deviceList.forEach { dev ->
                    _events.tryEmit(
                        TransportEvent.PeerDiscovered(
                            TransportPeer(
                                address = dev.deviceAddress.uppercase(),
                                displayName = dev.deviceName.ifBlank { "Wi-Fi Direct Cihazı" },
                                shortTagHex = dev.deviceAddress.replace(":", ""),
                                rssi = -55,
                                transportType = TransportType.WIFI_DIRECT
                            )
                        )
                    )
                }
            }
        }
    }

    override fun getCapabilities(): CapabilityStatus = capabilityDetector.detectWifiDirectCapability()

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery(): TransportResult<Unit> {
        val cap = getCapabilities()
        if (cap != CapabilityStatus.SUPPORTED) {
            return TransportResult.Failure("Wi-Fi Direct kullanılamıyor ($cap).")
        }
        val manager = wifiP2pManager ?: return TransportResult.Failure("WifiP2pManager bulunamadı.")
        if (channel == null) {
            channel = manager.initialize(appContext, appContext.mainLooper, null)
        }
        if (!receiverRegistered) {
            val filter = IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        }
        channel?.let { ch ->
            manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _events.tryEmit(
                        TransportEvent.DiscoveryStateChanged(
                            TransportType.WIFI_DIRECT,
                            isScanning = true,
                            isAdvertising = false,
                            message = "Wi-Fi Direct taraması başlatıldı"
                        )
                    )
                }

                override fun onFailure(reason: Int) {
                    _events.tryEmit(
                        TransportEvent.TransportError(
                            TransportType.WIFI_DIRECT,
                            "Wi-Fi Direct taraması başlatılamadı (kod: $reason)"
                        )
                    )
                }
            })
        }
        return TransportResult.Success(Unit)
    }

    override suspend fun stopDiscovery() {
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        channel?.let { ch ->
            runCatching { wifiP2pManager?.stopPeerDiscovery(ch, null) }
        }
    }

    override suspend fun connect(peer: TransportPeer): TransportResult<Unit> =
        TransportResult.Failure("Wi-Fi Direct soket aktarımı sonraki fazda eklenecektir; BLE kullanın.")

    override suspend fun disconnect(peerAddress: String) = Unit

    override suspend fun send(peerAddress: String, payload: ByteArray): TransportResult<Unit> =
        TransportResult.Failure("Wi-Fi Direct soket aktarımı sonraki fazda eklenecektir; BLE kullanın.")

    override fun receive(): Flow<IncomingTransportFrame> = emptyFlow()

    override fun release() {
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }
}

/**
 * Wi-Fi Aware (NAN) transport adapter with honest runtime capability detection.
 * Never returns fake success on unsupported hardware.
 */
class WifiAwareTransport(
    private val capabilityDetector: CapabilityDetector
) : P2pTransport {
    override val type: TransportType = TransportType.WIFI_AWARE
    override val events: Flow<TransportEvent> = emptyFlow()

    override fun getCapabilities(): CapabilityStatus = capabilityDetector.detectWifiAwareCapability()

    override suspend fun startDiscovery(): TransportResult<Unit> {
        val cap = getCapabilities()
        return if (cap == CapabilityStatus.UNSUPPORTED) {
            TransportResult.Failure("UNSUPPORTED: Bu cihaz Wi-Fi Aware donanımını desteklemiyor.")
        } else {
            TransportResult.Failure("Wi-Fi Aware veri oturumu sonraki fazda uygulanacaktır.")
        }
    }

    override suspend fun stopDiscovery() = Unit
    override suspend fun connect(peer: TransportPeer): TransportResult<Unit> = startDiscovery()
    override suspend fun disconnect(peerAddress: String) = Unit
    override suspend fun send(peerAddress: String, payload: ByteArray): TransportResult<Unit> = startDiscovery()
    override fun receive(): Flow<IncomingTransportFrame> = emptyFlow()
    override fun release() = Unit
}

/**
 * Placeholder transport contract for future Internet relay phase.
 * Intentionally unavailable in Phase 1 (strictly offline P2P).
 */
class InternetTransport : P2pTransport {
    override val type: TransportType = TransportType.INTERNET
    override val events: Flow<TransportEvent> = emptyFlow()

    override fun getCapabilities(): CapabilityStatus = CapabilityStatus.UNSUPPORTED

    override suspend fun startDiscovery(): TransportResult<Unit> =
        TransportResult.Failure("İnternet aktarımı Faz 1'de devre dışıdır.")

    override suspend fun stopDiscovery() = Unit
    override suspend fun connect(peer: TransportPeer): TransportResult<Unit> = startDiscovery()
    override suspend fun disconnect(peerAddress: String) = Unit
    override suspend fun send(peerAddress: String, payload: ByteArray): TransportResult<Unit> = startDiscovery()
    override fun receive(): Flow<IncomingTransportFrame> = emptyFlow()
    override fun release() = Unit
}
