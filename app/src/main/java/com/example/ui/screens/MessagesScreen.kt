package com.example.ui.screens

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
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.crypto.YektaCryptoEngine
import com.example.data.model.PublicProfile
import com.example.ui.theme.EmeraldVerified
import com.example.ui.viewmodel.UiState
import com.example.ui.viewmodel.YektaViewModel

@Composable
fun MessagesScreen(viewModel: YektaViewModel) {
    val hwState by viewModel.p2pHardwareState.collectAsStateWithLifecycle()
    val profilesState by viewModel.publicProfilesState.collectAsStateWithLifecycle()
    val messagesState by viewModel.messagesState.collectAsStateWithLifecycle()

    var selectedPeer by remember { mutableStateOf<PublicProfile?>(null) }
    var preferOffline by remember { mutableStateOf(true) }
    var draftText by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.proximityManager.refreshHardwareStatus()
        if (results.values.any { it }) {
            viewModel.proximityManager.startDiscoveryAndBroadcast()
        }
    }

    val localNodeHex = remember { viewModel.cryptoEngine.getKademliaNodeHexId() }

    if (selectedPeer != null) {
        val peer = selectedPeer!!
        val allMessages = (messagesState as? UiState.Success)?.data.orEmpty()
        val conversation = remember(allMessages, peer.userId) {
            allMessages.filter {
                (it.senderId == viewModel.currentUserId && it.recipientId == peer.userId) ||
                    (it.senderId == peer.userId && it.recipientId == viewModel.currentUserId)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedPeer = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                        }
                        Column {
                            Text(
                                text = if (peer.userId == viewModel.currentUserId) {
                                    "${peer.displayName} (Kişisel Kasa)"
                                } else {
                                    peer.displayName
                                },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Anahtar: ${peer.publicKeyFingerprint.take(12)} · Katmanlı Şifreli",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldVerified
                            )
                        }
                    }
                    FilterChip(
                        selected = preferOffline,
                        onClick = { preferOffline = !preferOffline },
                        label = { Text(if (preferOffline) "Yakın Ağ" else "Aktarım") }
                    )
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
                                    text = "Uçtan Uca Şifreli Kanal Hazır",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Bu sohbete yazılan her ileti gönderilmeden önce cihazınızda şifrelenir ve çok katmanlı rota bilgisiyle korunur.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(conversation, key = { it.id }) { msg ->
                        val isMine = msg.senderId == viewModel.currentUserId
                        val decrypted = remember(msg.id, msg.ciphertext) {
                            viewModel.decryptMessageText(msg)
                        }
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
                                        text = decrypted,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${msg.onionHopsCount} Katman · ${
                                            when (msg.transportMode) {
                                                "ble_p2p" -> "Bluetooth LE"
                                                "wifi_direct" -> "Wi-Fi Direct"
                                                else -> "Şifreli Aktarım"
                                            }
                                        }",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            viewModel.sendEncryptedMessage(peer, draftText, preferOffline)
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
                    text = "Şifreli İletişim & Yakın Ağ",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Bluetooth LE, Wi-Fi Direct ve şifreli aktarım ile merkezi sunucu gerektirmeyen gizlilik odaklı mesajlaşma.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Hardware Radio & P2P Control Card
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
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.WifiTethering,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text("Yakın Donanım Radyosu", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = hwState.statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(
                            label = if (hwState.bluetoothEnabled) "Bluetooth Açık" else if (hwState.bluetoothSupported) "Bluetooth Kapalı" else "Bluetooth Yok",
                            active = hwState.bluetoothEnabled
                        )
                        StatusChip(
                            label = if (hwState.wifiP2pSupported) "Wi-Fi Direct Hazır" else "Wi-Fi Direct Yok",
                            active = hwState.wifiP2pSupported
                        )
                    }

                    Text(
                        text = "Yerel Düğüm Kimliği: #${localNodeHex.take(16).uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                if (viewModel.proximityManager.hasRequiredPermissions()) {
                                    viewModel.proximityManager.startDiscoveryAndBroadcast()
                                } else {
                                    permissionLauncher.launch(viewModel.proximityManager.requiredPermissions())
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("scan_nearby_nodes_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (hwState.isScanning) "Taranıyor..." else "Düğüm Tara")
                        }
                        if (hwState.isScanning) {
                            OutlinedButton(
                                onClick = { viewModel.proximityManager.stopDiscovery() },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Durdur")
                            }
                        }
                    }

                    if (hwState.discoveredPeers.isNotEmpty()) {
                        Text(
                            text = "Keşfedilen Yakın Cihazlar (${hwState.discoveredPeers.size})",
                            style = MaterialTheme.typography.labelLarge
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(hwState.discoveredPeers, key = { it.id }) { peer ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(peer.label, style = MaterialTheme.typography.labelLarge)
                                        Text(
                                            text = "${peer.transport} · ${peer.signalStrengthDbm} dBm",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "Halka: #${peer.xorDistanceRank}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
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
                Icon(Icons.Filled.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = "Doğrulanmış Ağ Üyeleri",
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }

        when (val pState = profilesState) {
            is UiState.Loading -> {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            is UiState.Error -> {
                item {
                    Text(pState.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is UiState.Success -> {
                val sortedPeers = pState.data.sortedBy { peer ->
                    YektaCryptoEngine.computeXorDistance(localNodeHex, peer.nodeHexId).bucketIndex
                }
                if (sortedPeers.isEmpty()) {
                    item {
                        Text(
                            text = "Henüz kayıtlı ağ üyesi bulunamadı.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(sortedPeers, key = { it.userId }) { peer ->
                        val distance = remember(peer.nodeHexId, localNodeHex) {
                            YektaCryptoEngine.computeXorDistance(localNodeHex, peer.nodeHexId)
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                                .clickable { selectedPeer = peer }
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
                                            text = if (peer.userId == viewModel.currentUserId) {
                                                "${peer.displayName} (Kişisel Şifreli Kasa)"
                                            } else {
                                                peer.displayName
                                            },
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "@${peer.handle} · Anahtar #${peer.publicKeyFingerprint.take(8)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Surface(
                                    color = EmeraldVerified.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "Mesafe ${distance.bucketIndex}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = EmeraldVerified,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
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
