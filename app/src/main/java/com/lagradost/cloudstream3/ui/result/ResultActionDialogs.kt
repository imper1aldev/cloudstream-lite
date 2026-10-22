package com.lagradost.cloudstream3.ui.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.lite.LiteColors
import com.lagradost.cloudstream3.ui.lite.LiteSpacing
import com.lagradost.cloudstream3.ui.lite.LiteType
import com.lagradost.cloudstream3.ui.lite.liteGlass

@Composable
internal fun ResultActionDialogs(model: ResultViewModel2, canDownload: Boolean, requestDownload: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val popup by model.selectPopup.actionState()
    val progress by model.loadedLinks.actionState()
    popup?.let { selection ->
        // Keep the original indices: SelectArray translates them to action IDs.
        val options = selection.getOptions(context).mapIndexedNotNull { index, label ->
            val action = (selection as? SelectPopup.SelectArray)?.options?.getOrNull(index)?.second
            if (!canDownload && action in setOf(ACTION_DOWNLOAD_EPISODE, ACTION_DOWNLOAD_MIRROR, ACTION_DOWNLOAD_EPISODE_SUBTITLE_MIRROR)) null
            else index to label
        }
        AlertDialog(
            onDismissRequest = { selection.callback(null) },
            title = { Text(selection.getTitle(context)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(options, key = { it.first }) { (index, label) ->
                        TextButton(onClick = {
                            val action = (selection as? SelectPopup.SelectArray)?.options?.getOrNull(index)?.second
                            if (action == ACTION_DOWNLOAD_EPISODE || action == ACTION_DOWNLOAD_MIRROR || action == ACTION_DOWNLOAD_EPISODE_SUBTITLE_MIRROR)
                                requestDownload { selection.callback(index) }
                            else selection.callback(index)
                        }) { Text(label) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selection.callback(null) }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    progress?.let { loading ->
        AlertDialog(
            onDismissRequest = model::cancelLinks,
            title = { Text(stringResource(R.string.loading)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(LiteSpacing.small)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.lite_link_progress, loading.linksLoaded, loading.subsLoaded))
                }
            },
            confirmButton = {
                if (loading.linksLoaded > 0) TextButton(onClick = model::skipLoading) {
                    Text(stringResource(R.string.skip_loading))
                }
            },
            dismissButton = { TextButton(onClick = model::cancelLinks) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The original route chooser connects Cast; playback continues through ACTION_CLICK_DEFAULT. */
@Composable
internal fun ResultCastRouteAction() {
    Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LiteSpacing.base)) {
        AndroidView(
            factory = { context -> MediaRouteButton(context).apply {
                CastButtonFactory.setUpMediaRouteButton(context, this)
                setAlwaysVisible(true)
                contentDescription = context.getString(R.string.lite_cast_action)
            } },
            modifier = Modifier.size(48.dp).liteGlass(CircleShape),
        )
        Text(stringResource(R.string.lite_cast_action), style = LiteType.metadata, color = LiteColors.onSurfaceVariant)
    }
}

@Composable
internal fun ResultSyncDialog(model: SyncViewModel, accounts: List<CurrentSynced>, onDismiss: () -> Unit) {
    val userData by model.userData.actionState()
    val current = (userData as? Resource.Success)?.value
    var status by remember(current?.status) { mutableStateOf(current?.status ?: SyncWatchType.NONE) }
    var score by remember(current?.score) { mutableStateOf(current?.score?.toFloat(10)?.toString().orEmpty()) }
    var episodes by remember(current?.watchedEpisodes) { mutableStateOf(current?.watchedEpisodes?.toString().orEmpty()) }
    var menu by remember { mutableStateOf(false) }
    val rating = score.toFloatOrNull()
    val watched = episodes.toIntOrNull()
    val valid = current != null && (score.isBlank() || (rating != null && rating.isFinite() && rating in 0f..10f)) &&
        (current.watchedEpisodes == null || (watched != null && watched >= 0 && (current.maxEpisodes == null || watched <= current.maxEpisodes!!)))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lite_sync_accounts)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LiteSpacing.small)) {
                Text(accounts.joinToString(" • ") { it.name })
                when (userData) {
                    is Resource.Loading, null -> CircularProgressIndicator()
                    is Resource.Failure -> {
                        Text(stringResource(R.string.error))
                        TextButton(onClick = model::updateMetaAndUser) { Text(stringResource(R.string.retry)) }
                    }
                    is Resource.Success -> {
                        TextButton(onClick = { menu = true }) { Text(stringResource(status.stringRes)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            SyncWatchType.entries.forEach { value ->
                                DropdownMenuItem(text = { Text(stringResource(value.stringRes)) },
                                    onClick = { status = value; menu = false })
                            }
                        }
                        OutlinedTextField(score, { score = it }, label = { Text(stringResource(R.string.lite_sync_rating)) }, singleLine = true)
                        if (current?.watchedEpisodes != null) OutlinedTextField(episodes, { episodes = it },
                            label = { Text(stringResource(R.string.lite_sync_watched)) }, singleLine = true)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                model.setStatus(status.internalId)
                model.setScore(rating?.let { Score.from(it, 10) })
                if (current?.watchedEpisodes != null && watched != null) model.setEpisodes(watched)
                model.publishUserData()
            }) { Text(stringResource(R.string.sort_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun <T> LiveData<T>.actionState(): State<T?> {
    val state = remember(this) { mutableStateOf(value, neverEqualPolicy()) }
    DisposableEffect(this) {
        val observer = Observer<T> { state.value = it }
        observeForever(observer)
        onDispose { removeObserver(observer) }
    }
    return state
}
