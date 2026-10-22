package com.lagradost.cloudstream3.ui.lite

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class LiteHeroItem(val key: String, val title: String, val imageUrl: String?, val metadata: String?)

@Composable
fun LiteHeroCarousel(
    items: List<LiteHeroItem>,
    fixedHeight: Dp,
    onDetail: (LiteHeroItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val pager = rememberPagerState { items.size }
    val scope = rememberCoroutineScope()
    val position = pager.settledPage.coerceIn(items.indices)
    val lifecycleOwner = LocalLifecycleOwner.current
    var touching by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(position, items.size, touching, visible, lifecycleOwner) {
        if (items.size > 1 && visible && !touching) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // Wait for manual movement to settle without cancelling our own animation.
                snapshotFlow { pager.isScrollInProgress }.first { !it }
                delay(6_000)
                pager.animateScrollToPage((position + 1) % items.size)
            }
        }
    }
    val description = stringResource(R.string.lite_featured_position, position + 1, items.size)
    Column(
        modifier = modifier.fillMaxWidth().pointerInput(Unit) {
            try {
                awaitPointerEventScope {
                    while (true) touching = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
                }
            } finally { touching = false }
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalPager(
            state = pager,
            key = { items[it].key },
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth().height(fixedHeight)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    visible = coordinates.isAttached && bounds.width > 0 && bounds.height >= coordinates.size.height * 0.5f
                }
                .semantics { stateDescription = description },
        ) { index ->
            val item = items[index]
            LiteHero(
                title = item.title,
                imageUrl = item.imageUrl,
                metadata = item.metadata,
                fixedHeight = fixedHeight,
                onDetail = { onDetail(item) },
            )
        }
        if (items.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = LiteSpacing.screenPadding, vertical = LiteSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(Modifier.weight(1f))
                // At most five featured titles are displayed.
                val firstIndicator = (position - 2).coerceIn(0, (items.size - 5).coerceAtLeast(0))
                repeat(minOf(items.size, 5)) { offset ->
                    val selected = firstIndicator + offset == position
                    Spacer(Modifier.width(if (selected) 24.dp else 6.dp).height(6.dp)
                        .background(if (selected) LiteColors.accent else LiteColors.onSurfaceVariant.copy(alpha = 0.35f), CircleShape))
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    enabled = !pager.isScrollInProgress,
                    onClick = { scope.launch { pager.animateScrollToPage((position - 1 + items.size) % items.size) } },
                    modifier = Modifier.size(48.dp).liteGlass(CircleShape),
                ) {
                    Icon(painterResource(R.drawable.ic_baseline_arrow_back_24), stringResource(R.string.lite_previous_featured),
                        tint = LiteColors.onSurface)
                }
                IconButton(
                    enabled = !pager.isScrollInProgress,
                    onClick = { scope.launch { pager.animateScrollToPage((position + 1) % items.size) } },
                    modifier = Modifier.size(48.dp).liteGlass(CircleShape),
                ) {
                    Icon(painterResource(R.drawable.ic_baseline_arrow_forward_24), stringResource(R.string.lite_next_featured),
                        tint = LiteColors.onSurface)
                }
            }
        }
    }
}
