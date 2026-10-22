package com.lagradost.cloudstream3.ui.lite

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse

@Composable
fun litePosterWidth() = when (rememberLiteBreakpoint().widthClass) {
    LiteWidthClass.Compact -> 120.dp
    LiteWidthClass.Medium -> 130.dp
    LiteWidthClass.Expanded -> 140.dp
}

@Composable
fun <T> LiveData<T>.liteState(initial: T? = null): State<T?> {
    val state = remember(this) { mutableStateOf(value ?: initial) }
    DisposableEffect(this) {
        val observer = Observer<T> { state.value = it }
        observeForever(observer)
        onDispose { removeObserver(observer) }
    }
    return state
}

@Composable
fun LiteGrid(
    items: List<SearchResponse>,
    onClick: (SearchResponse) -> Unit,
    modifier: Modifier = Modifier,
    hasNext: Boolean = false,
    loading: Boolean = false,
    error: Boolean = false,
    pageKey: Any = items.size,
    onLoadMore: () -> Unit = {},
    onLongClick: ((SearchResponse) -> Unit)? = null,
    state: LazyGridState = rememberLazyGridState(),
) {
    val width = litePosterWidth()
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Adaptive(width + LiteSpacing.gridGap),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(LiteSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalArrangement = Arrangement.spacedBy(LiteSpacing.gridGap),
    ) {
        items(items, key = { it.url }) { item ->
            Box(contentAlignment = Alignment.TopCenter) {
                LitePosterCard(item.posterUrl, item.name, Modifier.width(width), onClick = { onClick(item) },
                    onLongClick = onLongClick?.let { action -> { action(item) } })
            }
        }
        if (hasNext || loading || error) item(span = { GridItemSpan(maxLineSpan) }, key = "more") {
            if (error) TextButton(onClick = onLoadMore) { Text(stringResource(R.string.retry)) }
            else {
                if (hasNext && !loading) LaunchedEffect(pageKey) { onLoadMore() }
                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        }
    }
}
