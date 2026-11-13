package com.example.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.SocialPost
import com.example.ui.theme.EmeraldVerified
import com.example.ui.viewmodel.PersonalizationUiState
import com.example.ui.viewmodel.UiState
import com.example.ui.viewmodel.YektaViewModel

private enum class FeedFilter(val label: String) {
    ALL("Tümü"),
    FOR_YOU("Senin İçin"),
    PHOTO("Fotoğraf"),
    VIDEO("Video"),
    TEXT("Metin")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: YektaViewModel,
    onNavigateToCreate: () -> Unit
) {
    val postsState by viewModel.postsState.collectAsStateWithLifecycle()
    val personalizationState by viewModel.personalizationState.collectAsStateWithLifecycle()

    var selectedFilter by remember { mutableStateOf(FeedFilter.ALL) }
    var verticalReelsMode by remember { mutableStateOf(false) }
    var inspectedPost by remember { mutableStateOf<SocialPost?>(null) }
    var replyPost by remember { mutableStateOf<SocialPost?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header & Mode Switch
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Yekta",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "1x Özgün Sosyal Akış",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(
                    onClick = { viewModel.generateAnalyticsAndPersonalization() },
                    modifier = Modifier.testTag("personalize_feed_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = "Akışı Kişiselleştir",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = { verticalReelsMode = !verticalReelsMode },
                    modifier = Modifier.testTag("toggle_reels_mode_button")
                ) {
                    Icon(
                        imageVector = if (verticalReelsMode) Icons.Filled.ViewAgenda else Icons.Filled.ViewCarousel,
                        contentDescription = "Görünümü Değiştir",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Format filter strip
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            items(FeedFilter.entries) { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = {
                        selectedFilter = filter
                        if (filter == FeedFilter.FOR_YOU &&
                            personalizationState is PersonalizationUiState.Idle
                        ) {
                            viewModel.generateAnalyticsAndPersonalization()
                        }
                    },
                    label = { Text(filter.label) }
                )
            }
        }

        // Personalization highlight strip
        val pState = personalizationState
        AnimatedVisibility(visible = pState is PersonalizationUiState.Ready) {
            if (pState is PersonalizationUiState.Ready) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Öne Çıkan Temalar: ${pState.result.topicHighlights.joinToString(" · ")}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = pState.result.insightSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        when (val state = postsState) {
            is UiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is UiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            is UiState.Success -> {
                val baseList = state.data
                val filteredPosts = remember(baseList, selectedFilter, personalizationState) {
                    val byFormat = when (selectedFilter) {
                        FeedFilter.ALL -> baseList
                        FeedFilter.PHOTO -> baseList.filter { it.format == "photo" }
                        FeedFilter.VIDEO -> baseList.filter { it.format == "video" }
                        FeedFilter.TEXT -> baseList.filter { it.format == "text" }
                        FeedFilter.FOR_YOU -> {
                            val ranked = (personalizationState as? PersonalizationUiState.Ready)
                                ?.result?.rankedPostIds.orEmpty()
                            if (ranked.isEmpty()) baseList.sortedByDescending { it.uniquenessScore }
                            else baseList.sortedBy { post ->
                                val idx = ranked.indexOf(post.id)
                                if (idx == -1) Int.MAX_VALUE else idx
                            }
                        }
                    }
                    byFormat
                }

                if (filteredPosts.isEmpty()) {
                    EmptyFeedShowcase(onNavigateToCreate = onNavigateToCreate)
                } else if (verticalReelsMode) {
                    VerticalReelsPager(
                        posts = filteredPosts,
                        onLike = { viewModel.likePost(it) },
                        onRepost = { viewModel.repostPost(it) },
                        onOpenReplies = { replyPost = it },
                        onInspectSeal = { inspectedPost = it }
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredPosts, key = { it.id }) { post ->
                            SocialPostCard(
                                post = post,
                                onLike = { viewModel.likePost(post) },
                                onRepost = { viewModel.repostPost(post) },
                                onReply = { replyPost = post },
                                onInspectSeal = { inspectedPost = post }
                            )
                        }
                    }
                }
            }
        }
    }

    inspectedPost?.let { post ->
        ModalBottomSheet(onDismissRequest = { inspectedPost = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Verified,
                        contentDescription = null,
                        tint = EmeraldVerified
                    )
                    Text(
                        text = "1x Özgünlük Doğrulama Mührü",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                Text(
                    text = "Özet: ${post.semanticSummary}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Özgünlük Puanı: %${post.uniquenessScore}",
                            style = MaterialTheme.typography.labelLarge,
                            color = EmeraldVerified
                        )
                        Text(
                            text = "İçerik Parmak İzi:\n${post.contentHash}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Algısal İmza: ${post.perceptualHash}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    replyPost?.let { post ->
        val replies by viewModel.observeReplies(post.id)
            .collectAsStateWithLifecycle(initialValue = emptyList())
        var replyDraft by remember { mutableStateOf("") }

        ModalBottomSheet(onDismissRequest = { replyPost = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Yanıtlar (${replies.size})",
                    style = MaterialTheme.typography.titleLarge
                )
                if (replies.isEmpty()) {
                    Text(
                        text = "İlk yanıtı siz yazın.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(replies, key = { it.id }) { rep ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "${rep.authorName} · @${rep.authorHandle}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = rep.text,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = replyDraft,
                        onValueChange = { replyDraft = it },
                        placeholder = { Text("Yanıt yaz...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    IconButton(
                        onClick = {
                            if (replyDraft.isNotBlank()) {
                                viewModel.submitReply(post, replyDraft)
                                replyDraft = ""
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Yanıt Gönder",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SocialPostCard(
    post: SocialPost,
    onLike: () -> Unit,
    onRepost: () -> Unit,
    onReply: () -> Unit,
    onInspectSeal: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = post.authorName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column {
                        Text(
                            text = post.authorName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "@${post.authorHandle}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 1x Seal Badge
                Surface(
                    color = EmeraldVerified.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.clickable { onInspectSeal() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Verified,
                            contentDescription = "1x Özgün",
                            tint = EmeraldVerified,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "1x · %${post.uniquenessScore}",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldVerified
                        )
                    }
                }
            }

            PostMediaViewport(post = post, modifier = Modifier.fillMaxWidth())

            Text(
                text = post.caption,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (post.tags.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(post.tags) { tag ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "#$tag",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onLike() }
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (post.likeCount > 0) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = "Beğen",
                            tint = if (post.likeCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${post.likeCount}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onReply() }
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ChatBubbleOutline,
                            contentDescription = "Yanıtla",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${post.replyCount}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onRepost() }
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Repeat,
                            contentDescription = "Yeniden Paylaş",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${post.repostCount}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "#${post.contentHash.take(8).uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PostMediaViewport(post: SocialPost, modifier: Modifier = Modifier) {
    if (post.format == "text" && post.mediaDataUri.isBlank()) return

    val mediaUri = post.mediaDataUri
    if (post.format == "photo" && mediaUri.startsWith("data:image")) {
        val bitmap = remember(mediaUri) {
            runCatching {
                val b64 = mediaUri.substringAfter("base64,", "")
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = post.caption,
                contentScale = ContentScale.Crop,
                modifier = modifier
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(16.dp))
            )
            return
        }
    }

    if (post.format == "video" && mediaUri.isNotBlank()) {
        var isPlaying by remember { mutableStateOf(false) }
        Box(
            modifier = modifier
                .aspectRatio(9f / 11f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { isPlaying = !isPlaying },
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(Uri.parse(mediaUri))
                        setOnPreparedListener { mp ->
                            mp.isLooping = true
                            if (isPlaying) start()
                        }
                    }
                },
                update = { vv ->
                    if (isPlaying && !vv.isPlaying) vv.start()
                    else if (!isPlaying && vv.isPlaying) vv.pause()
                },
                modifier = Modifier.fillMaxSize()
            )
            if (!isPlaying) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Videoyu Oynat",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    } else if (mediaUri.isNotBlank()) {
        AsyncImage(
            model = mediaUri,
            contentDescription = post.caption,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(16.dp))
        )
    }
}

@Composable
private fun VerticalReelsPager(
    posts: List<SocialPost>,
    onLike: (SocialPost) -> Unit,
    onRepost: (SocialPost) -> Unit,
    onOpenReplies: (SocialPost) -> Unit,
    onInspectSeal: (SocialPost) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { posts.size })
    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        val post = posts[page]
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        ) {
            if (post.mediaDataUri.isNotBlank()) {
                PostMediaViewport(post = post, modifier = Modifier.fillMaxSize())
            } else {
                Image(
                    painter = painterResource(
                        id = if (page % 2 == 0) R.drawable.img_visual_editorial_1 else R.drawable.img_visual_editorial_2
                    ),
                    contentDescription = post.caption,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.1f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.85f)
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(20.dp)
                    .fillMaxWidth(0.78f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = EmeraldVerified.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.clickable { onInspectSeal(post) }
                ) {
                    Text(
                        text = "1x Özgün · %${post.uniquenessScore}",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldVerified,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Text(
                    text = "@${post.authorHandle}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = post.caption,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(onClick = { onLike(post) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = "Beğen",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text("${post.likeCount}", style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(onClick = { onOpenReplies(post) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.ChatBubbleOutline,
                            contentDescription = "Yanıtlar"
                        )
                        Text("${post.replyCount}", style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(onClick = { onRepost(post) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.Repeat,
                            contentDescription = "Yeniden Paylaş"
                        )
                        Text("${post.repostCount}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFeedShowcase(onNavigateToCreate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_visual_editorial_1),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .clip(RoundedCornerShape(20.dp))
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "İlk 1x Özgün Paylaşımı Oluşturun",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Yekta akışında yalnızca benzersiz fotoğraf, kısa video ve metinler yer alır.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onNavigateToCreate,
            modifier = Modifier.testTag("empty_feed_create_button")
        ) {
            Text("Paylaşım Oluştur")
        }
    }
}
