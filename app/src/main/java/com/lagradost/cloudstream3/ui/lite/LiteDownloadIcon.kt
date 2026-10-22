package com.lagradost.cloudstream3.ui.lite

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LiteDownloadIcon(id: Int, status: DownloadType?, onClick: () -> Unit, showLabel: Boolean = false) {
    val context = LocalContext.current
    var progress by remember(id) { mutableStateOf<Pair<Long, Long>?>(null) }
    LaunchedEffect(id) {
        progress = withContext(Dispatchers.IO) {
            VideoDownloadManager.getDownloadFileInfo(context, id)?.let { it.fileLength to it.totalBytes }
        }
    }
    DisposableEffect(id) {
        val observer: (Triple<Int, Long, Long>) -> Unit = { if (it.first == id) progress = it.second to it.third }
        VideoDownloadManager.downloadProgressEvent += observer
        onDispose { VideoDownloadManager.downloadProgressEvent -= observer }
    }
    val active = status == DownloadType.IsDownloading || status == DownloadType.IsPending
    val icon = when (status) {
        DownloadType.IsDone -> R.drawable.ic_baseline_check_24
        DownloadType.IsPaused -> R.drawable.ic_baseline_pause_24
        DownloadType.IsFailed -> R.drawable.ic_baseline_warning_24
        else -> R.drawable.netflix_download
    }
    val label = stringResource(when (status) {
        DownloadType.IsDone -> R.string.downloaded
        DownloadType.IsPaused -> R.string.download_paused
        DownloadType.IsDownloading, DownloadType.IsPending -> R.string.downloading
        DownloadType.IsFailed -> R.string.download_failed
        else -> R.string.download
    })
    val percentage = progress?.takeIf { it.second > 0 }?.let { ((it.first.toDouble() / it.second) * 100).toInt().coerceIn(0, 100) }
    val accessibilityLabel = if (percentage != null && (active || status == DownloadType.IsPaused))
        stringResource(R.string.lite_download_progress, label, percentage) else label
    val control: @Composable () -> Unit = {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp).liteGlass(androidx.compose.foundation.shape.CircleShape)) {
            Box(contentAlignment = Alignment.Center) {
                if (active || status == DownloadType.IsPaused) {
                    val total = progress?.second ?: 0
                    if (total > 0) CircularProgressIndicator(
                        progress = { ((progress?.first ?: 0).toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
                    else if (active) CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 3.dp)
                }
                Icon(painterResource(icon), accessibilityLabel, modifier = Modifier.size(24.dp),
                    tint = when (status) {
                        DownloadType.IsDone -> LiteColors.accent
                        DownloadType.IsFailed -> MaterialTheme.colorScheme.error
                        else -> LiteColors.onSurface
                    })
            }
        }
    }
    if (showLabel) Column(
        modifier = Modifier.widthIn(min = 76.dp).width(IntrinsicSize.Min),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LiteSpacing.base),
    ) {
        control()
        Text(stringResource(R.string.download), style = LiteType.metadata,
            color = LiteColors.onSurfaceVariant, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    } else control()
}
