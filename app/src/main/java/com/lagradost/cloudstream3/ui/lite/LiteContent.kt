package com.lagradost.cloudstream3.ui.lite

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.annotation.DrawableRes
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream4.theme.CloudStreamPreviewTheme

/**
 * Lite content components (MVP spec §4.1/§4.2): poster card, landscape
 * (continue-watching) card, and the row scaffolding that hosts them.
 *
 * Data-agnostic by design: every component takes plain values + content slots,
 * so Home/Details (F3) can map whatever model they already carry and this file
 * never knows about domains or invents model fields.
 *
 * Image loading reuses the app's existing Coil setup (`coil3.compose.AsyncImage`,
 * already used by AccountScreen/SubtitlesScreen/SettingsFragmentScreen) — no new
 * dependency, no global ImageLoader wiring.
 */

/** Press-scale + click, shared by both cards. `onClick == null` -> non-interactive. */
@Composable
private fun liteCardInteraction(
    interaction: MutableInteractionSource,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) LiteInteraction.PRESSED_SCALE else 1f,
        label = "liteCardPress",
    )
    return Modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(
            if (onClick != null) {
                Modifier.combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
            } else {
                Modifier
            }
        )
}

/**
 * 2:3 poster with token radius/border and a 0.98 press scale (spec §3.4/§3.9).
 * `imageUrl == null` -> the token surface colour shows through (graceful
 * placeholder; Coil handles the loading/error painter).
 */
@Composable
fun LitePosterCard(
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier.then(liteCardInteraction(interaction, onClick, onLongClick)),
        verticalArrangement = Arrangement.spacedBy(LiteSpacing.small),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(LiteRadii.cardShape).background(LiteColors.surface)
                .liteCardBorder(selected = selected),
            contentAlignment = Alignment.Center,
        ) {
            var artworkFailed by remember(imageUrl) { androidx.compose.runtime.mutableStateOf(false) }
            if (imageUrl.isNullOrBlank() || artworkFailed) {
                Text(
                    text = contentDescription.orEmpty(),
                    style = LiteType.rowTitle,
                    color = LiteColors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(LiteSpacing.small),
                )
            }
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { artworkFailed = true },
                onSuccess = { artworkFailed = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!contentDescription.isNullOrBlank()) {
            Text(
                text = contentDescription,
                style = LiteType.action,
                color = LiteColors.onSurface,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 16:9 landscape card for Continue Watching (spec §4.1): title pinned over
 * [LiteGradients.cardScrim] so it stays AA-legible on any artwork, plus an
 * optional [progress] bar (0..1) tinted with [LiteColors.progress].
 */
@Composable
fun LiteLandscapeCard(
    imageUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = title,
    progress: Float? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    subtitle: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(LiteRadii.cardShape)
            .background(LiteColors.surface)
            .liteCardBorder(selected = selected)
            .then(liteCardInteraction(interaction, onClick, onLongClick))
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(LiteGradients.cardScrim))
        Column(
            modifier = Modifier.align(Alignment.BottomStart)
                .padding(horizontal = LiteSpacing.small)
                .padding(bottom = LiteSpacing.small),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = LiteType.action, color = LiteColors.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = LiteType.metadata, color = LiteColors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (progress != null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(LiteColors.surfaceHighest)
            ) {
                // Plain Box bar instead of LinearProgressIndicator: no M3 API
                // version churn, and full control over the 3dp token height.
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(LiteColors.progress)
                )
            }
        }
    }
}

/** Row title (spec §4.2) with an optional trailing slot (e.g. "see all"). */
@Composable
fun LiteRowHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(LiteSpacing.rowTitleHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = LiteType.rowTitle,
            color = LiteColors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/**
 * Horizontal content row (spec §4.2): header + LazyRow with stable [key]s and
 * token paddings/gaps. Generic over item type; caller supplies the card slot.
 */
@Composable
fun <T> LiteRow(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    loading: Boolean = false,
    error: Boolean = false,
    pageKey: Any = items.size,
    onTitleClick: (() -> Unit)? = null,
    item: @Composable (T) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LiteRowHeader(
            title = title,
            modifier = Modifier.padding(horizontal = LiteSpacing.screenPadding)
                .then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier),
            trailing = trailing,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = LiteSpacing.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(LiteSpacing.gridGap),
        ) {
            items(items = items, key = key) { entry -> item(entry) }
            if (onLoadMore != null) {
                // ponytail: sentinel item; composes only at the row end and fires once
                // per end-reach (stable key => no re-fire while it stays visible).
                item(key = "__lite_load_more__") {
                    if (error) androidx.compose.material3.TextButton(onClick = onLoadMore) {
                        Text(androidx.compose.ui.res.stringResource(com.lagradost.cloudstream3.R.string.retry))
                    } else {
                    if (!loading) LaunchedEffect(pageKey) { onLoadMore() }
                    LiteSkeleton(
                        modifier = Modifier
                            .width(120.dp)
                            .aspectRatio(2f / 3f)
                    )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews (private; simulated values only, never wired to navigation).
// ---------------------------------------------------------------------------

@PreviewLightDark
@Composable
private fun LitePosterCardPreview() {
    CloudStreamPreviewTheme {
        Box(Modifier.background(LiteColors.background).padding(LiteSpacing.screenPadding)) {
            LitePosterCard(
                imageUrl = null,
                contentDescription = "Example poster",
                modifier = Modifier.width(120.dp),
                onClick = {},
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun LiteLandscapeCardPreview() {
    CloudStreamPreviewTheme {
        Row(
            Modifier.background(LiteColors.background).padding(LiteSpacing.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(LiteSpacing.gridGap),
        ) {
            LiteLandscapeCard(
                imageUrl = null,
                title = "Long example title that ellipsizes",
                modifier = Modifier.width(200.dp),
                progress = 0.4f,
                onClick = {},
            )
            LiteLandscapeCard(
                imageUrl = null,
                title = "No progress",
                modifier = Modifier.width(200.dp),
                onClick = {},
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun LiteRowPreview() {
    CloudStreamPreviewTheme {
        val sample = remember { List(6) { it } }
        Column(Modifier.background(LiteColors.background).padding(vertical = LiteSpacing.screenPadding)) {
            LiteRow(
                title = "Continue watching",
                items = sample,
                key = { it },
            ) { i ->
                LiteLandscapeCard(
                    imageUrl = null,
                    title = "Item $i",
                    modifier = Modifier.width(200.dp),
                    progress = (i + 1) / 6f,
                    onClick = {},
                )
            }
        }
    }
}


/** Secondary actions keep a recognizable icon, a readable label and a 48dp target. */
@Composable
fun LiteIconAction(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    accessibilityLabel: String = label,
    adaptiveWidth: Boolean = false,
) {
    Column(
        modifier = modifier.then(if (adaptiveWidth) Modifier.widthIn(min = 76.dp).width(IntrinsicSize.Min) else Modifier.width(76.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LiteSpacing.base),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp).liteGlass(CircleShape)
                .semantics { this.selected = selected },
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = accessibilityLabel,
                tint = if (selected) LiteColors.accent else LiteColors.onSurface,
            )
        }
        Text(
            text = label,
            style = LiteType.metadata,
            color = LiteColors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
