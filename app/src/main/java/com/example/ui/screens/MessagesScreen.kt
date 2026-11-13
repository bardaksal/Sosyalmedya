package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.p2p.OmniMeshP2pEngine
import com.example.p2p.PeerUiModel
import com.example.p2p.crypto.SessionState
import com.example.p2p.data.MessageDeliveryState
import com.example.p2p.data.MessageDirection
import com.example.p2p.transport.CapabilityStatus
import com.example.p2p.transport.PeerConnectionStatus
import com.example.p2p.transport.TransportType
import com.example.ui.theme.AmberAlert
import com.example.ui.theme.EmeraldVerified
import com.example.ui.viewmodel.YektaViewModel

private data class SelectedPeerTarget(
    val deviceId: String?,
    val address: String?,
    val displayName: String,
    val fingerprint: String?
)

@Composable
fun MessagesScreen(viewModel: YektaViewModel) {
    val engineState by viewModel.omniMeshState.collectAsStateWithLifecycle()
    val savedPeers by viewModel.savedP2pPeers.collectAsStateWithLifecycle()
    val allMessages by viewModel.allP2pMessages.collectAsStateWithLifecycle()

    var selectedPeer by remember { mutableStateOf<SelectedPeerTarget?>(null) }
    var draftText by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.refreshP2pCapabilities()
        if (results.values.any { it }) {
            viewModel.startP2pDiscovery()
        }
    }

    // Merge discovered live peers with previously verified Room peers
    val mergedPeers = remember(engineState.peers, savedPeers) {
        val byAddress = LinkedHashMap<String, PeerUiModel>()
        engineState.peers.forEach { live ->
            byAddress[live.address.uppercase()] = live
        }
        savedPeers.forEach { saved ->
            val addr = saved.bleAddress.uppercase()
            val alreadyLive = byAddress.values.any { it.deviceId == saved.deviceId || (addr.isNotBlank() && it.address == addr) }
            if (!alreadyLive) {
                val key = addr.ifBlank { saved.deviceId }
                byAddress[key] = PeerUiModel(
                    address = saved.bleAddress,
                    deviceId = saved.deviceId,
                    displayName = saved.displayName,
                    fingerprint = saved.publicKeyFingerprint,
                    rssi = saved.lastRssi,
                    transportType = TransportType.BLE,
                    connectionStatus = PeerConnectionStatus.DISCONNECTED,
                    sessionState = SessionState.CLOSED,
                    statusDetail = "Kayıtlı cihaz (çevrimdışı)"
                )
            }
        }
        byAddress.values.toList()
    }

    if (selectedPeer != null) {
        BackHandler { selectedPeer = null }
        val target = selectedPeer!!

        val livePeer = mergedPeers.firstOrNull {
            (target.deviceId != null && it.deviceId == target.deviceId) ||
                (!target.address.isNullOrBlank() && it.address.equals(target.address, ignoreCase = true))
        }
        val activeDeviceId = livePeer?.deviceId ?: target.deviceId
        val activeAddress = livePeer?.address?.takeIf { it.isNotBlank() } ?: target.address
        val sessionState = livePeer?.sessionState
            ?: viewModel.omniMeshEngine.getSessionStateForPeer(activeAddress, activeDeviceId)
        val statusLabel = livePeer?.statusDetail ?: when (sessionState) {
            SessionState.ESTABLISHED -> "Güvenli oturum kuruldu"
            SessionState.HANDSHAKING -> "Bağlanıyor"
            SessionState.FAILED -> "Başarısız"
            SessionState.CLOSED -> "Bağlantı kesildi"
            SessionState.UNINITIALIZED -> "Hazır"
        }

        val conversation = remember(allMessages, activeDeviceId, activeAddress) {
            allMessages.filter { msg ->
                (activeDeviceId != null && msg.peerDeviceId == activeDeviceId) ||
                    (!activeAddress.isNullOrBlank() && msg.peerDeviceId.equals(activeAddress, ignoreCase = true))
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Chat Header
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(onClick = { selectedPeer = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                        }
                        Column {
                            Text(
                                text = target.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val fpText = (livePeer?.fingerprint ?: target.fingerprint)?.take(12)
                            Text(
                                text = if (fpText != null) "$statusLabel · #$fpText" else statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sessionState == SessionState.ESTABLISHED) EmeraldVerified else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (sessionState != SessionState.ESTABLISHED && !activeAddress.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = { viewModel.connectToP2pPeer(activeAddress) },
                            modifier = Modifier.testTag("connect_peer_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Bağlan")
                        }
                    }
                }
            }

            // Messages List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (conversation.isEmpty()) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Security,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Doğrudan Çevrimdışı Kanal",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "İnternet veya sunucu olmadan Bluetooth LE üzerinden doğrudan güvenli oturum kurularak şifreli mesajlaşılır.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(conversation, key = { it.messageId }) { msg ->
                        val isMine = msg.direction == MessageDirection.OUTGOING.name
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                        ) {
                            Surface(
                                color = if (isMine) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth(0.82f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = msg.plaintext,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = messageStateLabel(msg.state, msg.errorReason),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when (msg.state) {
                                            MessageDeliveryState.DELIVERED.name -> EmeraldVerified
                                            MessageDeliveryState.FAILED.name,
                                            MessageDeliveryState.EXPIRED.name -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = draftText,
                    onValueChange = { draftText = it },
                    placeholder = { Text("Şifreli mesaj yazın...") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("encrypted_message_input"),
                    shape = RoundedCornerShape(16.dp)
                )
                IconButton(
                    onClick = {
                        if (draftText.isNotBlank()) {
                            viewModel.sendDirectP2pMessage(
                                peerDeviceId = activeDeviceId,
                                peerAddress = activeAddress,
                                plaintext = draftText
                            )
                            draftText = ""
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .testTag("send_encrypted_message_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Şifreli Gönder",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Çevrimdışı P2P Mesajlaşma",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "İnternet veya merkezi sunucu olmadan Bluetooth LE ile doğrudan şifreli iletişim.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Hardware Radio & Capability Control Card
        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WifiTethering,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text("Donanım ve Bağlantı Durumu", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = engineState.statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    val caps = engineState.capabilities
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            StatusChip(
                                label = "BLE: ${OmniMeshP2pEngine.capabilityToTurkish(caps.ble)}",
                                active = caps.ble == CapabilityStatus.SUPPORTED
                            )
                        }
                        item {
                            StatusChip(
                                label = "Wi-Fi Direct: ${OmniMeshP2pEngine.capabilityToTurkish(caps.wifiDirect)}",
                                active = caps.wifiDirect == CapabilityStatus.SUPPORTED
                            )
                        }
                        item {
                            StatusChip(
                                label = "Wi-Fi Aware: ${OmniMeshP2pEngine.capabilityToTurkish(caps.wifiAware)}",
                                active = caps.wifiAware == CapabilityStatus.SUPPORTED
                            )
                        }
                    }

                    Text(
                        text = "Cihaz Kimliği: ${engineState.localIdentity.deviceId.take(18)} · Anahtar #${engineState.localIdentity.publicKeyFingerprint.take(10)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (!engineState.lastSecurityNotice.isNullOrBlank()) {
                        Surface(
                            color = AmberAlert.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = engineState.lastSecurityNotice.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberAlert,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                if (viewModel.capabilityDetector.hasBlePermissions()) {
                                    viewModel.startP2pDiscovery()
                                } else {
                                    permissionLauncher.launch(viewModel.capabilityDetector.requiredBlePermissions())
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("scan_nearby_nodes_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (engineState.isScanning) "Taranıyor..." else "Cihaz Tara ve Yayınla")
                        }
                        if (engineState.isScanning || engineState.isAdvertising) {
                            OutlinedButton(
                                onClick = { viewModel.stopP2pDiscovery() },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Durdur")
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.BluetoothConnected, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = "Yakındaki ve Kayıtlı Cihazlar (${mergedPeers.size})",
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }

        if (mergedPeers.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Henüz yakın cihaz bulunamadı. İki cihazda da Bluetooth'u açıp 'Cihaz Tara ve Yayınla' butonuna basın.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(mergedPeers, key = { it.address.ifBlank { it.deviceId ?: it.displayName } }) { peer ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .clickable {
                            selectedPeer = SelectedPeerTarget(
                                deviceId = peer.deviceId,
                                address = peer.address.takeIf { it.isNotBlank() },
                                displayName = peer.displayName,
                                fingerprint = peer.fingerprint
                            )
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Column {
                                Text(
                                    text = peer.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = buildString {
                                        append("BLE · ${peer.rssi} dBm")
                                        peer.fingerprint?.let { append(" · #${it.take(8)}") }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        val isSecure = peer.sessionState == SessionState.ESTABLISHED
                        Surface(
                            color = if (isSecure) EmeraldVerified.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = peer.statusDetail ?: OmniMeshP2pEngine.statusToTurkish(peer.connectionStatus),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSecure) EmeraldVerified else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun messageStateLabel(state: String, errorReason: String?): String = when (state) {
    MessageDeliveryState.CREATED.name -> "Oluşturuldu"
    MessageDeliveryState.CONNECTING.name -> "Bağlanıyor"
    MessageDeliveryState.SENT.name -> "Şifreli mesaj gönderildi"
    MessageDeliveryState.DELIVERED.name -> "İletildi"
    MessageDeliveryState.EXPIRED.name -> "Süresi doldu"
    MessageDeliveryState.FAILED.name -> if (errorReason.isNullOrBlank()) "Başarısız" else "Başarısız: $errorReason"
    else -> state
}

@Composable
private fun StatusChip(label: String, active: Boolean) {
    Surface(
        color = if (active) EmeraldVerified.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) EmeraldVerified else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}
