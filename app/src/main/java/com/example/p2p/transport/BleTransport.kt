package com.example.p2p.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Production dual-role Bluetooth Low Energy (BLE) transport supporting:
 * - Advertising + GATT Server (Peripheral role)
 * - Scanning + GATT Client (Central role)
 * - MTU negotiation and chunked frame delivery
 * - Serialized GATT operations (one in-flight op per connection)
 *
 * Operates completely offline without any server or Firebase dependency.
 */
class BleTransport(
    context: Context,
    private val localShortTag: ByteArray,
    private val capabilityDetector: CapabilityDetector
) : P2pTransport {

    override val type: TransportType = TransportType.BLE

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reassembler = BleFrameReassembler()

    private val _events = MutableSharedFlow<TransportEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<TransportEvent> = _events.asSharedFlow()

    private val _incomingFrames = MutableSharedFlow<IncomingTransportFrame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var serverTxCharacteristic: BluetoothGattCharacteristic? = null

    private var isScanning = false
    private var isAdvertising = false

    private val discoveredDevices = ConcurrentHashMap<String, BluetoothDevice>()

    // Client connections initiated by us (Central -> Remote Peripheral)
    private data class ClientLink(
        val address: String,
        val gatt: BluetoothGatt,
        val rxChar: BluetoothGattCharacteristic? = null,
        val txChar: BluetoothGattCharacteristic? = null,
        val attPayloadBytes: Int = BleFrameCodec.MIN_ATT_PAYLOAD,
        val opMutex: Mutex = Mutex(),
        val isReady: Boolean = false,
        var readyDeferred: CompletableDeferred<Boolean> = CompletableDeferred(),
        var pendingWriteDeferred: CompletableDeferred<Boolean>? = null,
        var pendingDescriptorDeferred: CompletableDeferred<Boolean>? = null
    )

    // Server connections accepted by our GATT Server (Remote Central -> Us)
    private data class ServerLink(
        val address: String,
        val device: BluetoothDevice,
        val attPayloadBytes: Int = BleFrameCodec.MIN_ATT_PAYLOAD,
        val notificationsEnabled: Boolean = false,
        val notifyMutex: Mutex = Mutex(),
        var pendingNotifyDeferred: CompletableDeferred<Boolean>? = null
    )

    private val clientLinks = ConcurrentHashMap<String, ClientLink>()
    private val serverLinks = ConcurrentHashMap<String, ServerLink>()

    override fun getCapabilities(): CapabilityStatus = capabilityDetector.detectBleCapability()

    override fun receive(): Flow<IncomingTransportFrame> = _incomingFrames.asSharedFlow()

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery(): TransportResult<Unit> {
        val cap = getCapabilities()
        if (cap != CapabilityStatus.SUPPORTED) {
            val msg = when (cap) {
                CapabilityStatus.UNSUPPORTED -> "Bu cihazda Bluetooth Low Energy donanımı desteklenmiyor."
                CapabilityStatus.DISABLED -> "Bluetooth kapalı. Lütfen Bluetooth'u açın."
                CapabilityStatus.PERMISSION_REQUIRED -> "Bluetooth izinleri verilmedi."
                CapabilityStatus.SUPPORTED -> ""
            }
            _events.tryEmit(TransportEvent.TransportError(TransportType.BLE, msg))
            return TransportResult.Failure(msg)
        }

        val adapter = bluetoothAdapter ?: return TransportResult.Failure("Bluetooth adaptörü bulunamadı.")

        // 1. Start GATT Server so remote devices can connect & write to us
        if (!ensureGattServerStarted()) {
            val msg = "BLE GATT sunucusu başlatılamadı."
            _events.tryEmit(TransportEvent.TransportError(TransportType.BLE, msg))
            return TransportResult.Failure(msg)
        }

        // 2. Start BLE Advertising (if supported on this chipset)
        val advCap = capabilityDetector.detectBleAdvertisingCapability()
        if (advCap == CapabilityStatus.SUPPORTED && !isAdvertising) {
            advertiser = adapter.bluetoothLeAdvertiser
            val advSettings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()
            val advData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(OMNIMESH_SERVICE_UUID))
                .addServiceData(ParcelUuid(OMNIMESH_SERVICE_UUID), localShortTag.copyOf(8))
                .build()
            runCatching {
                advertiser?.startAdvertising(advSettings, advData, advertiseCallback)
            }.onFailure {
                Log.w(TAG, "BLE advertising start exception")
            }
        }

        // 3. Start BLE Scan filtered by OmniMesh Service UUID
        if (!isScanning) {
            scanner = adapter.bluetoothLeScanner
            val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(OMNIMESH_SERVICE_UUID))
                .build()
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            runCatching {
                scanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
                isScanning = true
            }.onFailure {
                return TransportResult.Failure("BLE taraması başlatılamadı.")
            }
        }

        _events.tryEmit(
            TransportEvent.DiscoveryStateChanged(
                transportType = TransportType.BLE,
                isScanning = isScanning,
                isAdvertising = isAdvertising,
                message = "BLE yakın cihaz taraması aktif"
            )
        )
        return TransportResult.Success(Unit)
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopDiscovery() {
        if (capabilityDetector.hasBlePermissions()) {
            if (isScanning) {
                runCatching { scanner?.stopScan(scanCallback) }
                isScanning = false
            }
            if (isAdvertising) {
                runCatching { advertiser?.stopAdvertising(advertiseCallback) }
                isAdvertising = false
            }
        }
        _events.tryEmit(
            TransportEvent.DiscoveryStateChanged(
                transportType = TransportType.BLE,
                isScanning = false,
                isAdvertising = false,
                message = "BLE taraması durduruldu"
            )
        )
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(peer: TransportPeer): TransportResult<Unit> {
        if (getCapabilities() != CapabilityStatus.SUPPORTED) {
            return TransportResult.Failure("Bluetooth LE kullanılabilir değil.")
        }
        val normalized = peer.address.uppercase()

        // Reuse existing active server or ready client connection
        serverLinks[normalized]?.takeIf { it.notificationsEnabled }?.let {
            _events.tryEmit(TransportEvent.ConnectionStateChanged(normalized, PeerConnectionStatus.CONNECTED))
            return TransportResult.Success(Unit)
        }
        clientLinks[normalized]?.let { existing ->
            if (existing.rxChar != null && existing.isReady) {
                _events.tryEmit(TransportEvent.ConnectionStateChanged(normalized, PeerConnectionStatus.CONNECTED))
                return TransportResult.Success(Unit)
            }
        }

        val device = discoveredDevices[normalized] ?: runCatching {
            bluetoothAdapter?.getRemoteDevice(normalized)
        }.getOrNull() ?: return TransportResult.Failure("Uzak Bluetooth cihazı bulunamadı.")

        _events.tryEmit(TransportEvent.ConnectionStateChanged(normalized, PeerConnectionStatus.CONNECTING))

        val ready = CompletableDeferred<Boolean>()
        val gatt = device.connectGatt(
            appContext,
            false,
            createClientGattCallback(normalized),
            BluetoothDevice.TRANSPORT_LE
        ) ?: run {
            _events.tryEmit(
                TransportEvent.ConnectionStateChanged(normalized, PeerConnectionStatus.FAILED, "GATT bağlantısı kurulamadı")
            )
            return TransportResult.Failure("GATT bağlantısı kurulamadı.")
        }

        clientLinks[normalized] = ClientLink(
            address = normalized,
            gatt = gatt,
            readyDeferred = ready
        )

        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() } == true
        return if (connected) {
            _events.tryEmit(TransportEvent.ConnectionStateChanged(normalized, PeerConnectionStatus.CONNECTED))
            TransportResult.Success(Unit)
        } else {
            disconnect(normalized)
            _events.tryEmit(
                TransportEvent.ConnectionStateChanged(normalized, PeerConnectionStatus.FAILED, "Bağlantı zaman aşımına uğradı")
            )
            TransportResult.Failure("BLE bağlantısı veya servis keşfi zaman aşımına uğradı.")
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect(peerAddress: String) {
        val normalized = peerAddress.uppercase()
        clientLinks.remove(normalized)?.let { link ->
            link.readyDeferred.complete(false)
            link.pendingWriteDeferred?.complete(false)
            link.pendingDescriptorDeferred?.complete(false)
            if (capabilityDetector.hasBlePermissions()) {
                runCatching {
                    link.gatt.disconnect()
                    link.gatt.close()
                }
            }
        }
        serverLinks.remove(normalized)?.let { sLink ->
            sLink.pendingNotifyDeferred?.complete(false)
            if (capabilityDetector.hasBlePermissions()) {
                runCatching { gattServer?.cancelConnection(sLink.device) }
            }
        }
        reassembler.clearPeer(normalized)
        _events.tryEmit(TransportEvent.ConnectionStateChanged(normalized, PeerConnectionStatus.DISCONNECTED))
    }

    @SuppressLint("MissingPermission")
    override suspend fun send(peerAddress: String, payload: ByteArray): TransportResult<Unit> {
        if (getCapabilities() != CapabilityStatus.SUPPORTED) {
            return TransportResult.Failure("Bluetooth LE kullanılabilir değil.")
        }
        val normalized = peerAddress.uppercase()

        // Prefer Client GATT write if we initiated the link; otherwise use Server GATT notification
        val client = clientLinks[normalized]
        if (client != null && client.rxChar != null) {
            return sendViaClientWrite(client, payload)
        }

        val serverLink = serverLinks[normalized]
        if (serverLink != null && serverLink.notificationsEnabled) {
            return sendViaServerNotify(serverLink, payload)
        }

        return TransportResult.Failure("Cihazla aktif BLE oturum kanalı bulunmuyor.")
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendViaClientWrite(link: ClientLink, payload: ByteArray): TransportResult<Unit> {
        val rxChar = link.rxChar ?: return TransportResult.Failure("RX karakteristiği hazır değil.")
        return link.opMutex.withLock {
            val chunks = BleFrameCodec.chunk(payload, link.attPayloadBytes)
            for (chunk in chunks) {
                val writeDeferred = CompletableDeferred<Boolean>()
                link.pendingWriteDeferred = writeDeferred
                val started = writeCharacteristicCompat(link.gatt, rxChar, chunk)
                if (!started) {
                    link.pendingWriteDeferred = null
                    return@withLock TransportResult.Failure("BLE karakteristik yazma başlatılamadı.")
                }
                val ok = withTimeoutOrNull(CHUNK_OP_TIMEOUT_MS) { writeDeferred.await() } == true
                link.pendingWriteDeferred = null
                if (!ok) {
                    return@withLock TransportResult.Failure("BLE paket parçası gönderilemedi.")
                }
            }
            TransportResult.Success(Unit)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendViaServerNotify(link: ServerLink, payload: ByteArray): TransportResult<Unit> {
        val server = gattServer ?: return TransportResult.Failure("GATT sunucusu aktif değil.")
        val txChar = serverTxCharacteristic ?: return TransportResult.Failure("TX karakteristiği hazır değil.")
        return link.notifyMutex.withLock {
            val chunks = BleFrameCodec.chunk(payload, link.attPayloadBytes)
            for (chunk in chunks) {
                val notifyDeferred = CompletableDeferred<Boolean>()
                link.pendingNotifyDeferred = notifyDeferred
                val started = notifyCharacteristicCompat(server, link.device, txChar, chunk)
                if (!started) {
                    link.pendingNotifyDeferred = null
                    return@withLock TransportResult.Failure("BLE bildirim gönderimi başlatılamadı.")
                }
                val ok = withTimeoutOrNull(CHUNK_OP_TIMEOUT_MS) { notifyDeferred.await() } == true
                link.pendingNotifyDeferred = null
                if (!ok) {
                    return@withLock TransportResult.Failure("BLE bildirim parçası onaylanmadı.")
                }
            }
            TransportResult.Success(Unit)
        }
    }

    @SuppressLint("MissingPermission")
    override fun release() {
        if (capabilityDetector.hasBlePermissions()) {
            if (isScanning) runCatching { scanner?.stopScan(scanCallback) }
            if (isAdvertising) runCatching { advertiser?.stopAdvertising(advertiseCallback) }
            clientLinks.values.forEach { link ->
                runCatching {
                    link.gatt.disconnect()
                    link.gatt.close()
                }
            }
            runCatching { gattServer?.close() }
        }
        isScanning = false
        isAdvertising = false
        clientLinks.clear()
        serverLinks.clear()
        discoveredDevices.clear()
        gattServer = null
        reassembler.clearAll()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun ensureGattServerStarted(): Boolean {
        if (gattServer != null) return true
        val manager = bluetoothManager ?: return false
        val server = manager.openGattServer(appContext, serverGattCallback) ?: return false

        val service = BluetoothGattService(
            OMNIMESH_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val rxCharacteristic = BluetoothGattCharacteristic(
            OMNIMESH_RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val txCharacteristic = BluetoothGattCharacteristic(
            OMNIMESH_TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        txCharacteristic.addDescriptor(cccd)

        service.addCharacteristic(rxCharacteristic)
        service.addCharacteristic(txCharacteristic)

        if (!server.addService(service)) {
            runCatching { server.close() }
            return false
        }

        serverTxCharacteristic = txCharacteristic
        gattServer = server
        return true
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address?.uppercase() ?: return
            discoveredDevices[address] = device

            val serviceData = result.scanRecord?.getServiceData(ParcelUuid(OMNIMESH_SERVICE_UUID))
            val tagHex = serviceData?.joinToString("") { "%02X".format(it) } ?: address.replace(":", "")
            val label = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                ?: "OmniMesh-${tagHex.take(6)}"

            _events.tryEmit(
                TransportEvent.PeerDiscovered(
                    TransportPeer(
                        address = address,
                        displayName = label,
                        shortTagHex = tagHex,
                        rssi = result.rssi,
                        transportType = TransportType.BLE,
                        status = PeerConnectionStatus.DISCOVERED
                    )
                )
            )
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            _events.tryEmit(
                TransportEvent.TransportError(
                    TransportType.BLE,
                    "BLE taraması başarısız (hata kodu: $errorCode)"
                )
            )
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising = true
            _events.tryEmit(
                TransportEvent.DiscoveryStateChanged(
                    transportType = TransportType.BLE,
                    isScanning = isScanning,
                    isAdvertising = true,
                    message = "BLE yayını ve tarama aktif"
                )
            )
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            _events.tryEmit(
                TransportEvent.TransportError(
                    TransportType.BLE,
                    "BLE yayını başlatılamadı (hata kodu: $errorCode)"
                )
            )
        }
    }

    private val serverGattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = device.address?.uppercase() ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                discoveredDevices[addr] = device
                serverLinks.putIfAbsent(addr, ServerLink(address = addr, device = device))
                _events.tryEmit(TransportEvent.ConnectionStateChanged(addr, PeerConnectionStatus.CONNECTING))
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverLinks.remove(addr)?.pendingNotifyDeferred?.complete(false)
                reassembler.clearPeer(addr)
                if (!clientLinks.containsKey(addr)) {
                    _events.tryEmit(TransportEvent.ConnectionStateChanged(addr, PeerConnectionStatus.DISCONNECTED))
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            val addr = device.address?.uppercase() ?: return
            val attPayload = (mtu - 3).coerceIn(BleFrameCodec.MIN_ATT_PAYLOAD, 509)
            serverLinks.computeIfPresent(addr) { _, link -> link.copy(attPayloadBytes = attPayload) }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val addr = device.address?.uppercase() ?: return
            if (descriptor.uuid == CCCD_UUID) {
                val enabled = value != null &&
                    (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                        value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE))
                serverLinks.compute(addr) { _, existing ->
                    (existing ?: ServerLink(addr, device)).copy(notificationsEnabled = enabled)
                }
                if (enabled) {
                    _events.tryEmit(TransportEvent.ConnectionStateChanged(addr, PeerConnectionStatus.CONNECTED))
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val addr = device.address?.uppercase() ?: return
            if (characteristic.uuid == OMNIMESH_RX_CHAR_UUID && value != null) {
                val fullFrame = reassembler.onChunkReceived(addr, value)
                if (fullFrame != null) {
                    _incomingFrames.tryEmit(
                        IncomingTransportFrame(
                            peerAddress = addr,
                            transportType = TransportType.BLE,
                            payload = fullFrame
                        )
                    )
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val addr = device.address?.uppercase() ?: return
            serverLinks[addr]?.pendingNotifyDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun createClientGattCallback(address: String): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val link = clientLinks[address]
                if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                    link?.readyDeferred?.complete(false)
                    link?.pendingWriteDeferred?.complete(false)
                    clientLinks.remove(address)
                    runCatching { gatt.close() }
                    _events.tryEmit(
                        TransportEvent.ConnectionStateChanged(
                            address,
                            PeerConnectionStatus.FAILED,
                            "GATT hatası: $status"
                        )
                    )
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!gatt.requestMtu(PREFERRED_MTU)) {
                        gatt.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    link?.readyDeferred?.complete(false)
                    link?.pendingWriteDeferred?.complete(false)
                    clientLinks.remove(address)
                    runCatching { gatt.close() }
                    reassembler.clearPeer(address)
                    _events.tryEmit(TransportEvent.ConnectionStateChanged(address, PeerConnectionStatus.DISCONNECTED))
                }
            }

            @SuppressLint("MissingPermission")
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                val attPayload = if (status == BluetoothGatt.GATT_SUCCESS) {
                    (mtu - 3).coerceIn(BleFrameCodec.MIN_ATT_PAYLOAD, 509)
                } else {
                    BleFrameCodec.MIN_ATT_PAYLOAD
                }
                clientLinks.computeIfPresent(address) { _, existing ->
                    existing.copy(attPayloadBytes = attPayload)
                }
                gatt.discoverServices()
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val link = clientLinks[address] ?: return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    link.readyDeferred.complete(false)
                    return
                }
                val service = gatt.getService(OMNIMESH_SERVICE_UUID)
                val rxChar = service?.getCharacteristic(OMNIMESH_RX_CHAR_UUID)
                val txChar = service?.getCharacteristic(OMNIMESH_TX_CHAR_UUID)
                if (rxChar == null || txChar == null) {
                    link.readyDeferred.complete(false)
                    return
                }
                clientLinks[address] = link.copy(rxChar = rxChar, txChar = txChar)

                gatt.setCharacteristicNotification(txChar, true)
                val cccd = txChar.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    val started = writeDescriptorCompat(
                        gatt,
                        cccd,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    )
                    if (!started) {
                        link.readyDeferred.complete(false)
                    }
                } else {
                    clientLinks.computeIfPresent(address) { _, current -> current.copy(isReady = true) }
                    link.readyDeferred.complete(true)
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                val link = clientLinks[address] ?: return
                val ok = status == BluetoothGatt.GATT_SUCCESS
                link.pendingDescriptorDeferred?.complete(ok)
                if (descriptor.uuid == CCCD_UUID) {
                    if (ok) {
                        clientLinks.computeIfPresent(address) { _, current -> current.copy(isReady = true) }
                    }
                    link.readyDeferred.complete(ok)
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                clientLinks[address]?.pendingWriteDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            // API 33+ callback
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                handleClientNotification(address, characteristic.uuid, value)
            }

            // API <33 callback
            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    val bytes = characteristic.value ?: return
                    handleClientNotification(address, characteristic.uuid, bytes)
                }
            }
        }
    }

    private fun handleClientNotification(address: String, charUuid: UUID, value: ByteArray) {
        if (charUuid != OMNIMESH_TX_CHAR_UUID) return
        val fullFrame = reassembler.onChunkReceived(address, value) ?: return
        _incomingFrames.tryEmit(
            IncomingTransportFrame(
                peerAddress = address,
                transportType = TransportType.BLE,
                payload = fullFrame
            )
        )
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = data
            gatt.writeCharacteristic(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun notifyCharacteristicCompat(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(
                device,
                characteristic,
                true,
                data
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.value = data
            server.notifyCharacteristicChanged(device, characteristic, true)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    companion object {
        private const val TAG = "OmniMeshBleTransport"
        private const val PREFERRED_MTU = 512
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val CHUNK_OP_TIMEOUT_MS = 5_000L

        val OMNIMESH_SERVICE_UUID: UUID = UUID.fromString("0000FE9A-0000-1000-8000-00805F9B34FB")
        val OMNIMESH_RX_CHAR_UUID: UUID = UUID.fromString("7A4C0001-9D3E-4B18-8E5B-2C1188E0A101")
        val OMNIMESH_TX_CHAR_UUID: UUID = UUID.fromString("7A4C0002-9D3E-4B18-8E5B-2C1188E0A102")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
