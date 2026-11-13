package com.example.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.auth.signOutUser
import com.example.ui.theme.AmberAlert
import com.example.ui.theme.EmeraldVerified
import com.example.ui.viewmodel.UiState
import com.example.ui.viewmodel.YektaViewModel

@Composable
fun ProfileScreen(
    viewModel: YektaViewModel,
    isDarkTheme: Boolean,
    onToggleTheme: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    val accountState by viewModel.userAccount.collectAsStateWithLifecycle()
    val account = (accountState as? UiState.Success)?.data

    var displayName by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var selectedPlan by remember { mutableStateOf("ucretsiz") }

    LaunchedEffect(account) {
        if (account != null) {
            displayName = account.displayName
            bio = account.bio
            selectedPlan = account.planTier
        }
    }

    val keyFingerprint = remember { viewModel.cryptoEngine.getPublicKeyFingerprint() }
    val nodeHex = remember { viewModel.cryptoEngine.getKademliaNodeHexId() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = displayName.ifBlank { "Y" }.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Column {
                        Text(
                            text = displayName.ifBlank { "Yekta Üyesi" },
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = "@${account?.handle ?: "yekta"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Cryptographic Identity Card
        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Kriptografik Kimlik & Düğüm", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = "Cihaz Anahtar Parmak İzi:\n$keyFingerprint",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldVerified
                    )
                    Text(
                        text = "Ağ Düğüm Kimliği: #${nodeHex.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Profile Edit
        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Profil Bilgileri", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Görünen Ad") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = bio,
                        onValueChange = { bio = it },
                        label = { Text("Biyografi") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val widgets = account?.dashboardWidgets ?: listOf(
                                "uniqueness_rate", "similarity_chart", "network_nodes", "cipher_messages"
                            )
                            viewModel.updatePlanAndWidgets(displayName, bio, selectedPlan, widgets)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("save_profile_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Değişiklikleri Kaydet")
                    }
                }
            }
        }

        // Subscription & Monetization Plans
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Yekta Üyelik & Kurumsal Planlar", style = MaterialTheme.typography.titleLarge)
                PlanOptionCard(
                    title = "Yekta Ücretsiz",
                    price = "₺0 / Sınırsız",
                    features = "Tüm sosyal akışlar, 1x özgünlük doğrulaması, şifreli mesajlaşma ve yakın ağ erişimi %100 ücretsiz.",
                    selected = selectedPlan == "ucretsiz",
                    accent = EmeraldVerified,
                    onClick = {
                        selectedPlan = "ucretsiz"
                        val widgets = account?.dashboardWidgets ?: emptyList()
                        viewModel.updatePlanAndWidgets(displayName, bio, "ucretsiz", widgets)
                    }
                )
                PlanOptionCard(
                    title = "Bireysel Pro",
                    price = "Premium Abonelik",
                    features = "Özel analiz arşivleri, gelişmiş zamanlanmış dışa aktarma, öncelikli 1x rozet vitrini.",
                    selected = selectedPlan == "bireysel_pro",
                    accent = MaterialTheme.colorScheme.primary,
                    icon = Icons.Filled.WorkspacePremium,
                    onClick = {
                        selectedPlan = "bireysel_pro"
                        val widgets = account?.dashboardWidgets ?: emptyList()
                        viewModel.updatePlanAndWidgets(displayName, bio, "bireysel_pro", widgets)
                    }
                )
                PlanOptionCard(
                    title = "Kurumsal & Sponsorlu Alan",
                    price = "Kurumsal Lisans",
                    features = "Şirketlere özel doğrulanmış topluluk kanalları, marka içerik koruması ve sponsorlu içerik yerleşimi.",
                    selected = selectedPlan == "kurumsal",
                    accent = AmberAlert,
                    icon = Icons.Filled.Business,
                    onClick = {
                        selectedPlan = "kurumsal"
                        val widgets = account?.dashboardWidgets ?: emptyList()
                        viewModel.updatePlanAndWidgets(displayName, bio, "kurumsal", widgets)
                    }
                )
            }
        }

        // Appearance Toggle
        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (isDarkTheme) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Koyu Tema", style = MaterialTheme.typography.titleMedium)
                    }
                    Switch(
                        checked = isDarkTheme,
                        onCheckedChange = onToggleTheme
                    )
                }
            }
        }

        // Sign Out
        item {
            OutlinedButton(
                onClick = { signOutUser(credentialManager, scope) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("sign_out_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Çıkış Yap")
            }
        }
    }
}

@Composable
private fun PlanOptionCard(
    title: String,
    price: String,
    features: String,
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.CheckCircle,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text(price, style = MaterialTheme.typography.labelLarge, color = accent)
            }
            Text(
                text = features,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
