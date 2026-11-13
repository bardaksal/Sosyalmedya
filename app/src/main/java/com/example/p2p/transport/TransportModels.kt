package com.example.p2p.transport

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow

enum class TransportType {
    BLE,
    WIFI_DIRECT,
    WIFI_AWARE,
    INTERNET
}

enum class CapabilityStatus {
    SUPPORTED,
    UNSUPPORTED,
    DISABLED,
    PERMISSION_REQUIRED
}

data class DeviceTransportCapabilities(
    val ble: CapabilityStatus,
    val bleAdvertising: CapabilityStatus,
    val wifiDirect: CapabilityStatus,
    val wifiAware: CapabilityStatus,
    val internet: CapabilityStatus = CapabilityStatus.UNSUPPORTED
)

enum class PeerConnectionStatus {
    DISCOVERED,
    CONNECTING,
    CONNECTED,
    SECURE_SESSION_ESTABLISHED,
    DISCONNECTED,
    FAILED
}

data class TransportPeer(
    val address: String,
    val displayName: String,
    val shortTagHex: String,
    val rssi: Int,
    val transportType: TransportType,
    val status: PeerConnectionStatus = PeerConnectionStatus.DISCOVERED,
    val lastSeenAt: Long = System.currentTimeMillis()
)

data class IncomingTransportFrame(
    val peerAddress: String,
    val transportType: TransportType,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingTransportFrame) return false
        return peerAddress == other.peerAddress &&
            transportType == other.transportType &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = peerAddress.hashCode()
        result = 31 * result + transportType.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

sealed interface TransportEvent {
    data class DiscoveryStateChanged(
        val transportType: TransportType,
        val isScanning: Boolean,
        val isAdvertising: Boolean,
        val message: String
    ) : TransportEvent

    data class PeerDiscovered(val peer: TransportPeer) : TransportEvent
    data class ConnectionStateChanged(
        val peerAddress: String,
        val status: PeerConnectionStatus,
        val reason: String? = null
    ) : TransportEvent

    data class TransportError(
        val transportType: TransportType,
        val reason: String
    ) : TransportEvent
}

sealed interface TransportResult<out T> {
    data class Success<T>(val value: T) : TransportResult<T>
    data class Failure(val reason: String) : TransportResult<Nothing>
}

interface P2pTransport {
    val type: TransportType
    val events: Flow<TransportEvent>
    fun getCapabilities(): CapabilityStatus
    suspend fun startDiscovery(): TransportResult<Unit>
    suspend fun stopDiscovery()
    suspend fun connect(peer: TransportPeer): TransportResult<Unit>
    suspend fun disconnect(peerAddress: String)
    suspend fun send(peerAddress: String, payload: ByteArray): TransportResult<Unit>
    fun receive(): Flow<IncomingTransportFrame>
    fun release()
}

class CapabilityDetector(private val context: Context) {
    private val appContext = context.applicationContext

    fun detectAll(): DeviceTransportCapabilities {
        return DeviceTransportCapabilities(
            ble = detectBleCapability(),
            bleAdvertising = detectBleAdvertisingCapability(),
            wifiDirect = detectWifiDirectCapability(),
            wifiAware = detectWifiAwareCapability(),
            internet = CapabilityStatus.UNSUPPORTED // Intentionally disabled in Phase 1
        )
    }

    fun detectBleCapability(): CapabilityStatus {
        val pm = appContext.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return CapabilityStatus.UNSUPPORTED
        }
        val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: return CapabilityStatus.UNSUPPORTED
        if (!hasBlePermissions()) {
            return CapabilityStatus.PERMISSION_REQUIRED
        }
        return if (adapter.isEnabled) CapabilityStatus.SUPPORTED else CapabilityStatus.DISABLED
    }

    fun detectBleAdvertisingCapability(): CapabilityStatus {
        val base = detectBleCapability()
        if (base != CapabilityStatus.SUPPORTED) return base
        val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val advertiser = btManager?.adapter?.bluetoothLeAdvertiser
        return if (advertiser != null) CapabilityStatus.SUPPORTED else CapabilityStatus.UNSUPPORTED
    }

    fun detectWifiDirectCapability(): CapabilityStatus {
        val pm = appContext.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            return CapabilityStatus.UNSUPPORTED
        }
        val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: return CapabilityStatus.UNSUPPORTED
        // Manager exists and feature is declared
        manager.hashCode()
        if (!hasWifiDirectPermissions()) {
            return CapabilityStatus.PERMISSION_REQUIRED
        }
        return CapabilityStatus.SUPPORTED
    }

    fun detectWifiAwareCapability(): CapabilityStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return CapabilityStatus.UNSUPPORTED
        }
        val pm = appContext.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            return CapabilityStatus.UNSUPPORTED
        }
        val awareManager = appContext.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
            ?: return CapabilityStatus.UNSUPPORTED
        return if (awareManager.isAvailable) {
            CapabilityStatus.SUPPORTED
        } else {
            CapabilityStatus.DISABLED
        }
    }

    fun requiredBlePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun hasBlePermissions(): Boolean {
        return requiredBlePermissions().all { perm ->
            ContextCompat.checkSelfPermission(appContext, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasWifiDirectPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.all { perm ->
            ContextCompat.checkSelfPermission(appContext, perm) == PackageManager.PERMISSION_GRANTED
        }
    }
}
