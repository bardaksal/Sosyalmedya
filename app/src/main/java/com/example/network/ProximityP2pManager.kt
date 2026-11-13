package com.example.network

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.example.crypto.YektaCryptoEngine
import com.example.data.model.NearbyPeerNode
import com.example.data.model.P2pHardwareState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.charset.StandardCharsets
import java.util.UUID

class ProximityP2pManager(
    private val context: Context,
    private val localNodeHexId: String
) {
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val wifiP2pManager: WifiP2pManager? =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var wifiP2pChannel: WifiP2pManager.Channel? = null

    private var leScanner: BluetoothLeScanner? = null
    private var leAdvertiser: BluetoothLeAdvertiser? = null
    private var wifiReceiverRegistered = false

    private val _state = MutableStateFlow(
        P2pHardwareState(
            bluetoothSupported = bluetoothAdapter != null,
            bluetoothEnabled = bluetoothAdapter?.isEnabled == true,
            wifiP2pSupported = wifiP2pManager != null &&
                appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
            permissionsGranted = hasRequiredPermissions()
        )
    )
    val state: StateFlow<P2pHardwareState> = _state.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceAddress = result.device?.address ?: return
            @SuppressLint("MissingPermission")
            val name = result.scanRecord?.deviceName ?: "Yekta Düğümü"
            val serviceBytes = result.scanRecord?.getServiceData(ParcelUuid(YEKTA_SERVICE_UUID))
            val peerNodeHex = serviceBytes?.let { String(it, StandardCharsets.UTF_8) }
                ?: YektaCryptoEngine.deriveNodeHexFromUid(deviceAddress)
            val distance = YektaCryptoEngine.computeXorDistance(localNodeHexId, peerNodeHex)
            val peer = NearbyPeerNode(
                id = deviceAddress,
                label = name,
                transport = "Bluetooth LE",
                signalStrengthDbm = result.rssi,
                nodeHexId = peerNodeHex,
                xorDistanceRank = distance.bucketIndex
            )
            _state.update { current ->
                val updated = (current.discoveredPeers.filterNot { it.id == peer.id } + peer)
                    .sortedBy { it.xorDistanceRank }
                current.copy(discoveredPeers = updated)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _state.update {
                it.copy(
                    isScanning = false,
                    statusText = "Bluetooth LE taraması başlatılamadı (kod: $errorCode)"
                )
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            _state.update {
                it.copy(
                    isAdvertising = true,
                    statusText = "Yakın ağ yayını aktif"
                )
            }
        }

        override fun onStartFailure(errorCode: Int) {
            _state.update {
                it.copy(
                    isAdvertising = false,
                    statusText = "Yayın başlatılamadı (kod: $errorCode)"
                )
            }
        }
    }

    private val wifiP2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) {
                val channel = wifiP2pChannel ?: return
                if (!hasRequiredPermissions()) return
                wifiP2pManager?.requestPeers(channel) { peers ->
                    val wifiNodes = peers.deviceList.map { dev ->
                        val hex = YektaCryptoEngine.deriveNodeHexFromUid(dev.deviceAddress)
                        val dist = YektaCryptoEngine.computeXorDistance(localNodeHexId, hex)
                        NearbyPeerNode(
                            id = dev.deviceAddress,
                            label = dev.deviceName.ifBlank { "Wi-Fi Direct Düğümü" },
                            transport = "Wi-Fi Direct",
                            signalStrengthDbm = -52,
                            nodeHexId = hex,
                            xorDistanceRank = dist.bucketIndex
                        )
                    }
                    _state.update { current ->
                        val merged = (current.discoveredPeers.filterNot { it.transport == "Wi-Fi Direct" } + wifiNodes)
                            .sortedBy { it.xorDistanceRank }
                        current.copy(discoveredPeers = merged)
                    }
                }
            }
        }
    }

    fun requiredPermissions(): Array<String> {
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.distinct().toTypedArray()
    }

    fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(appContext, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun refreshHardwareStatus() {
        _state.update {
            it.copy(
                bluetoothSupported = bluetoothAdapter != null,
                bluetoothEnabled = bluetoothAdapter?.isEnabled == true,
                wifiP2pSupported = wifiP2pManager != null &&
                    appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
                permissionsGranted = hasRequiredPermissions()
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscoveryAndBroadcast() {
        refreshHardwareStatus()
        if (!hasRequiredPermissions()) {
            _state.update { it.copy(statusText = "Yakın cihaz erişim izni gerekli") }
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _state.update {
                it.copy(
                    bluetoothEnabled = false,
                    statusText = if (adapter == null) {
                        "Bu cihazda Bluetooth donanımı bulunmuyor; şifreli bulut aktarım kanalı aktif."
                    } else {
                        "Bluetooth kapalı; donanımı açarak çevrimdışı yakın düğümleri tarayabilirsiniz."
                    }
                )
            }
        } else {
            leScanner = adapter.bluetoothLeScanner
            leAdvertiser = adapter.bluetoothLeAdvertiser

            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(YEKTA_SERVICE_UUID))
                .build()
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            leScanner?.startScan(listOf(filter), scanSettings, scanCallback)

            val advSettings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setConnectable(true)
                .build()
            val nodeTagBytes = localNodeHexId.take(12).toByteArray(StandardCharsets.UTF_8)
            val advData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(YEKTA_SERVICE_UUID))
                .addServiceData(ParcelUuid(YEKTA_SERVICE_UUID), nodeTagBytes)
                .build()
            leAdvertiser?.startAdvertising(advSettings, advData, advertiseCallback)

            _state.update {
                it.copy(
                    isScanning = true,
                    statusText = "Bluetooth LE ve Wi-Fi Direct taraması sürüyor..."
                )
            }
        }

        if (wifiP2pManager != null) {
            if (wifiP2pChannel == null) {
                wifiP2pChannel = wifiP2pManager.initialize(appContext, appContext.mainLooper, null)
            }
            if (!wifiReceiverRegistered) {
                val filter = IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(wifiP2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    appContext.registerReceiver(wifiP2pReceiver, filter)
                }
                wifiReceiverRegistered = true
            }
            wifiP2pChannel?.let { channel ->
                wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) = Unit
                })
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        if (hasRequiredPermissions()) {
            runCatching { leScanner?.stopScan(scanCallback) }
            runCatching { leAdvertiser?.stopAdvertising(advertiseCallback) }
        }
        if (wifiReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(wifiP2pReceiver) }
            wifiReceiverRegistered = false
        }
        _state.update {
            it.copy(
                isScanning = false,
                isAdvertising = false,
                statusText = "Tarama durduruldu"
            )
        }
    }

    companion object {
        val YEKTA_SERVICE_UUID: UUID = UUID.fromString("0000FE9A-0000-1000-8000-00805F9B34FB")
    }
}
