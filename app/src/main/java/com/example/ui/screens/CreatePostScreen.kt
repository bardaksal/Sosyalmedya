package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.rememberAsyncImagePainter
import com.example.crypto.ContentHasher
import com.example.ui.theme.AmberAlert
import com.example.ui.theme.CrimsonBlock
import com.example.ui.theme.EmeraldVerified
import com.example.ui.viewmodel.VerificationUiState
import com.example.ui.viewmodel.YektaViewModel

@Composable
fun CreatePostScreen(
    viewModel: YektaViewModel,
    onPostPublished: () -> Unit
) {
    val context = LocalContext.current
    val verificationState by viewModel.verificationState.collectAsStateWithLifecycle()

    var selectedFormat by remember { mutableStateOf("text") }
    var caption by remember { mutableStateOf("") }
    var tagsInput by remember { mutableStateOf("") }
    var selectedMediaUri by remember { mutableStateOf<Uri?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedMediaUri = uri
            viewModel.resetVerification()
        }
    }

    val liveHashPreview = remember(caption, selectedMediaUri) {
        if (caption.isBlank()) "—" else ContentHasher.sha256Hex(caption + (selectedMediaUri?.toString() ?: "")).take(24)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "1x Özgün Paylaşım",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Yayınlamadan önce içerik parmak izi ve anlamsal özgünlük kontrolü uygulanır.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Format Selector (Photo / Video / Text)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedFormat == "text",
                onClick = {
                    selectedFormat = "text"
                    selectedMediaUri = null
                    viewModel.resetVerification()
                },
                label = { Text("Metin") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ShortText, contentDescription = null) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = selectedFormat == "photo",
                onClick = {
                    selectedFormat = "photo"
                    selectedMediaUri = null
                    viewModel.resetVerification()
                },
                label = { Text("Fotoğraf") },
                leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = selectedFormat == "video",
                onClick = {
                    selectedFormat = "video"
                    selectedMediaUri = null
                    viewModel.resetVerification()
                },
                label = { Text("Video") },
                leadingIcon = { Icon(Icons.Filled.VideoLibrary, contentDescription = null) },
                modifier = Modifier.weight(1f)
            )
        }

        if (selectedFormat != "text") {
            OutlinedButton(
                onClick = {
                    val mediaType = if (selectedFormat == "photo") {
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    } else {
                        ActivityResultContracts.PickVisualMedia.VideoOnly
                    }
                    photoPicker.launch(PickVisualMediaRequest(mediaType))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("pick_media_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = if (selectedFormat == "photo") Icons.Filled.Image else Icons.Filled.VideoLibrary,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (selectedMediaUri == null) {
                        if (selectedFormat == "photo") "Galeriden Fotoğraf Seç" else "Galeriden Kısa Video Seç"
                    } else {
                        "Seçilen Medyayı Değiştir"
                    }
                )
            }

            selectedMediaUri?.let { uri ->
                if (selectedFormat == "photo") {
                    Image(
                        painter = rememberAsyncImagePainter(uri),
                        contentDescription = "Seçilen fotoğraf",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .clip(RoundedCornerShape(16.dp))
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Video dosyası seçildi: ${uri.lastPathSegment ?: "Medya"}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = caption,
            onValueChange = {
                caption = it
                viewModel.resetVerification()
            },
            label = { Text("Paylaşım İçeriği") },
            placeholder = { Text("Özgün düşüncenizi veya açıklamanızı yazın...") },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("post_caption_input"),
            shape = RoundedCornerShape(16.dp)
        )

        OutlinedTextField(
            value = tagsInput,
            onValueChange = { tagsInput = it },
            label = { Text("Etiketler (virgülle ayırın)") },
            placeholder = { Text("tasarım, fotoğraf, şehir") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        )

        // Live Fingerprint Card
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        text = "Anlık İçerik Parmak İzi",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = liveHashPreview,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Button(
            onClick = {
                viewModel.verifyDraftUniqueness(
                    context = context,
                    caption = caption,
                    format = selectedFormat,
                    mediaUri = selectedMediaUri
                )
            },
            enabled = verificationState !is VerificationUiState.Analyzing && caption.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("verify_uniqueness_button"),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (verificationState is VerificationUiState.Analyzing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Özgünlük Doğrulanıyor...")
            } else {
                Text("1x Özgünlük Doğrulamasını Başlat")
            }
        }

        AnimatedVisibility(visible = verificationState !is VerificationUiState.Idle) {
            when (val vState = verificationState) {
                is VerificationUiState.Approved -> {
                    val r = vState.result
                    Surface(
                        color = EmeraldVerified.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, EmeraldVerified.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = EmeraldVerified)
                                Text(
                                    text = "Onaylandı · %${r.uniquenessScore} Özgün",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = EmeraldVerified,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            LinearProgressIndicator(
                                progress = { r.uniquenessScore / 100f },
                                color = EmeraldVerified,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            Text(text = r.reason, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "Kayıt Kimliği: #${r.contentHash.take(16).uppercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = {
                                    val customTags = tagsInput.split(",")
                                        .map { it.trim() }
                                        .filter { it.isNotBlank() }
                                    viewModel.publishVerifiedPost(
                                        context = context,
                                        format = selectedFormat,
                                        caption = caption,
                                        mediaUri = selectedMediaUri,
                                        verification = r,
                                        customTags = customTags
                                    )
                                    caption = ""
                                    tagsInput = ""
                                    selectedMediaUri = null
                                    onPostPublished()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EmeraldVerified,
                                    contentColor = MaterialTheme.colorScheme.onSecondary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("publish_post_button"),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("1x Olarak Yayınla", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                is VerificationUiState.Blocked -> {
                    val r = vState.result
                    Surface(
                        color = CrimsonBlock.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, CrimsonBlock.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Filled.Block, contentDescription = null, tint = CrimsonBlock)
                                Text(
                                    text = "Mükerrer İçerik Engellendi (%${r.similarityPercent} Benzerlik)",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CrimsonBlock,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            LinearProgressIndicator(
                                progress = { r.similarityPercent / 100f },
                                color = AmberAlert,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            Text(text = r.reason, style = MaterialTheme.typography.bodyMedium)
                            if (!r.matchedPostId.isNullOrBlank()) {
                                Text(
                                    text = "Eşleşen Kayıt: ${r.matchedPostId}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                is VerificationUiState.Error -> {
                    Surface(
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = vState.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
                else -> Unit
            }
        }
    }
}
