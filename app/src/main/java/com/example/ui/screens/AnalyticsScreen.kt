package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.SocialPost
import com.example.ui.theme.AmberAlert
import com.example.ui.theme.CyanPulse
import com.example.ui.theme.EmeraldVerified
import com.example.ui.theme.VioletAccent
import com.example.ui.viewmodel.PersonalizationUiState
import com.example.ui.viewmodel.UiState
import com.example.ui.viewmodel.YektaViewModel

@Composable
fun AnalyticsScreen(viewModel: YektaViewModel) {
    val context = LocalContext.current
    val postsState by viewModel.postsState.collectAsStateWithLifecycle()
    val reportsState by viewModel.reportsState.collectAsStateWithLifecycle()
    val messagesState by viewModel.messagesState.collectAsStateWithLifecycle()
    val blockedCount by viewModel.blockedDuplicateCount.collectAsStateWithLifecycle()
    val userAccountState by viewModel.userAccount.collectAsStateWithLifecycle()
    val personalizationState by viewModel.personalizationState.collectAsStateWithLifecycle()

    val posts = (postsState as? UiState.Success)?.data.orEmpty()
    val reports = (reportsState as? UiState.Success)?.data.orEmpty()
    val messages = (messagesState as? UiState.Success)?.data.orEmpty()
    val account = (userAccountState as? UiState.Success)?.data
    val enabledWidgets = account?.dashboardWidgets ?: listOf(
        "uniqueness_rate", "similarity_chart", "network_nodes", "cipher_messages"
    )

    var showCustomize by remember { mutableStateOf(false) }

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(viewModel.buildExportCsv().toByteArray())
                }
            }
        }
    }

    val jsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(viewModel.buildExportJson().toByteArray())
                }
            }
        }
    }

    val avgUniqueness = remember(posts) {
        if (posts.isEmpty()) 100 else posts.map { it.uniquenessScore }.average().toInt()
    }

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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Kontrol Paneli & Raporlar",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "İçerik özgünlüğü, etkileşim eğilimleri ve dışa aktarılabilir raporlar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showCustomize = !showCustomize }) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = "Paneli Özelleştir",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (showCustomize && account != null) {
            item {
                val allOptions = listOf(
                    "uniqueness_rate" to "Özgünlük Oranı",
                    "similarity_chart" to "Eğilim Grafiği",
                    "network_nodes" to "Format Dağılımı",
                    "cipher_messages" to "Şifreli Trafik"
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Görünür Kartları Seçin", style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            allOptions.take(2).forEach { (key, label) ->
                                val selected = key in enabledWidgets
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        val next = if (selected) enabledWidgets - key else enabledWidgets + key
                                        viewModel.updatePlanAndWidgets(
                                            account.displayName, account.bio, account.planTier, next
                                        )
                                    },
                                    label = { Text(label) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            allOptions.drop(2).forEach { (key, label) ->
                                val selected = key in enabledWidgets
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        val next = if (selected) enabledWidgets - key else enabledWidgets + key
                                        viewModel.updatePlanAndWidgets(
                                            account.displayName, account.bio, account.planTier, next
                                        )
                                    },
                                    label = { Text(label) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // KPI Row
        if ("uniqueness_rate" in enabledWidgets || "cipher_messages" in enabledWidgets) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if ("uniqueness_rate" in enabledWidgets) {
                        KpiMetricCard(
                            title = "Ortalama Özgünlük",
                            value = "%$avgUniqueness",
                            subtitle = "${posts.size} onaylı · $blockedCount engellendi",
                            accentColor = EmeraldVerified,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if ("cipher_messages" in enabledWidgets) {
                        KpiMetricCard(
                            title = "Şifreli İletiler",
                            value = "${messages.size}",
                            subtitle = "Uçtan uca korumalı",
                            accentColor = CyanPulse,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Interactive Line/Area Chart
        if ("similarity_chart" in enabledWidgets) {
            item {
                UniquenessTrendChartCard(posts = posts)
            }
        }

        // Format Distribution Ring Chart
        if ("network_nodes" in enabledWidgets) {
            item {
                FormatBreakdownCard(posts = posts)
            }
        }

        // Report Generation & Export Controls
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
                    Text(
                        text = "Rapor Oluştur & Dışa Aktar",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Button(
                        onClick = { viewModel.generateAnalyticsAndPersonalization() },
                        enabled = personalizationState !is PersonalizationUiState.Loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("generate_report_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (personalizationState is PersonalizationUiState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Rapor Hazırlanıyor...")
                        } else {
                            Icon(Icons.Filled.Analytics, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Yeni Analiz Raporu Oluştur")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { csvLauncher.launch("yekta_analiz_raporu.csv") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_csv_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("CSV İndir")
                        }
                        OutlinedButton(
                            onClick = { jsonLauncher.launch("yekta_analiz_raporu.json") },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_json_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("JSON İndir")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Yekta Analiz Raporu")
                                putExtra(Intent.EXTRA_TEXT, viewModel.buildExportCsv())
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Raporu Paylaş"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Raporu Paylaş")
                    }
                }
            }
        }

        item {
            Text(
                text = "Kaydedilen Raporlar (${reports.size})",
                style = MaterialTheme.typography.titleLarge
            )
        }

        if (reports.isEmpty()) {
            item {
                Text(
                    text = "Henüz kaydedilmiş rapor yok. Yukarıdan yeni bir analiz raporu oluşturabilirsiniz.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(reports, key = { it.id }) { report ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(report.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Onaylı: ${report.uniqueApproved} · Engellenen: ${report.duplicatesBlocked} · Ort. Benzerlik: %${report.avgSimilarityScore}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = report.recommendationsSummary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KpiMetricCard(
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.displaySmall, color = accentColor)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UniquenessTrendChartCard(posts: List<SocialPost>) {
    val scores = remember(posts) {
        if (posts.isEmpty()) listOf(100f)
        else posts.take(12).reversed().map { it.uniquenessScore.toFloat() }
    }
    var selectedIdx by remember(scores) { mutableIntStateOf(scores.lastIndex.coerceAtLeast(0)) }

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
                Text("Özgünlük Puanı Eğilimi", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Seçili: %${scores.getOrElse(selectedIdx) { 100f }.toInt()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanPulse
                )
            }

            val lineColor = CyanPulse
            val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .pointerInput(scores) {
                        detectTapGestures { offset ->
                            if (scores.size > 1) {
                                val step = size.width.toFloat() / (scores.size - 1)
                                selectedIdx = ((offset.x / step).toInt()).coerceIn(0, scores.lastIndex)
                            }
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                val topPad = 14.dp.toPx()
                val bottomPad = 14.dp.toPx()
                val usableH = (h - topPad - bottomPad).coerceAtLeast(1f)

                // Grid lines
                for (i in 0..2) {
                    val y = topPad + (usableH * i / 2f)
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
                }

                if (scores.size == 1) {
                    val y = topPad + usableH * (1f - scores[0] / 100f)
                    drawCircle(lineColor, radius = 7.dp.toPx(), center = Offset(w / 2f, y))
                } else {
                    val stepX = w / (scores.size - 1)
                    val points = scores.mapIndexed { idx, value ->
                        val x = idx * stepX
                        val y = topPad + usableH * (1f - (value.coerceIn(0f, 100f) / 100f))
                        Offset(x, y)
                    }

                    val fillPath = Path().apply {
                        moveTo(points.first().x, h - bottomPad)
                        points.forEach { lineTo(it.x, it.y) }
                        lineTo(points.last().x, h - bottomPad)
                        close()
                    }
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            listOf(lineColor.copy(alpha = 0.32f), Color.Transparent)
                        )
                    )

                    val strokePath = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        for (i in 1 until points.size) {
                            lineTo(points[i].x, points[i].y)
                        }
                    }
                    drawPath(
                        path = strokePath,
                        color = lineColor,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )

                    points.forEachIndexed { idx, pt ->
                        val r = if (idx == selectedIdx) 7.dp.toPx() else 4.dp.toPx()
                        drawCircle(
                            color = if (idx == selectedIdx) EmeraldVerified else lineColor,
                            radius = r,
                            center = pt
                        )
                    }
                }
            }

            Text(
                text = "Noktalara dokunarak gönderi bazlı özgünlük değerini inceleyin.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FormatBreakdownCard(posts: List<SocialPost>) {
    val photoCount = posts.count { it.format == "photo" }
    val videoCount = posts.count { it.format == "video" }
    val textCount = posts.count { it.format == "text" }
    val total = (photoCount + videoCount + textCount).coerceAtLeast(1)

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Canvas(modifier = Modifier.size(100.dp)) {
                val stroke = 14.dp.toPx()
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(stroke / 2f, stroke / 2f)

                if (posts.isEmpty()) {
                    drawArc(
                        color = CyanPulse.copy(alpha = 0.28f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                } else {
                    val slices = listOf(
                        photoCount to CyanPulse,
                        videoCount to VioletAccent,
                        textCount to EmeraldVerified
                    )
                    var start = -90f
                    slices.forEach { (count, color) ->
                        val sweep = (count.toFloat() / total) * 360f
                        if (sweep > 0f) {
                            drawArc(
                                color = color,
                                startAngle = start,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = stroke)
                            )
                            start += sweep
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Format Dağılımı", style = MaterialTheme.typography.titleMedium)
                LegendRow(color = CyanPulse, label = "Fotoğraf", count = photoCount)
                LegendRow(color = VioletAccent, label = "Kısa Video", count = videoCount)
                LegendRow(color = EmeraldVerified, label = "Metin Akışı", count = textCount)
            }
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(3.dp))
        )
        Text(
            text = "$label: $count",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
