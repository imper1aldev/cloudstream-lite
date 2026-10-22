package com.lagradost.cloudstream3.ui.lite

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream4.theme.CloudStreamPreviewTheme

/**
 * Lite state components (MVP spec §4: loading / empty / error). Data-agnostic:
 * every string is supplied by the caller, so Home/Details (F3) map whatever
 * model they already carry and this file never invents fields or domains.
 *
 * Skeleton "shimmer": the legacy layouts use a flat static placeholder
 * (`@color/grayShimmer` #DCDCDC, loading_poster.xml) with no animation. Here we
 * mirror the *intent* (placeholder blocks) but add a subtle alpha pulse. It is
 * deliberately cheap: the animated value is read inside [graphicsLayer], so the
 * read is deferred to the draw phase and never recomposes the tree.
 */

/** Skeleton placeholder block with a subtle token-coloured shimmer pulse. */
@Composable
fun LiteSkeleton(
    modifier: Modifier = Modifier,
    shape: Shape = LiteRadii.cardShape,
) {
    val transition = rememberInfiniteTransition(label = "liteSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liteSkeletonAlpha",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(LiteColors.surfaceHighest)
            // Read inside the layer block => draw-phase only, no recomposition.
            .graphicsLayer { this.alpha = alpha }
    )
}

/**
 * Empty state: title + optional description + an optional action slot the caller
 * fills (e.g. a retry or "browse" button). No defaults for copy — the caller owns
 * the wording.
 */
@Composable
fun LiteEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LiteSpacing.screenPadding, vertical = LiteSpacing.rowSpacing),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LiteSpacing.small),
    ) {
        Text(
            text = title,
            style = LiteType.rowTitle,
            color = LiteColors.onSurface,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Text(
                text = description,
                style = LiteType.description,
                color = LiteColors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        content?.invoke()
    }
}

/**
 * Error state: message + an accent Retry button. A single [clickable] bounds the
 * callback to exactly one invocation per press (no duplicated listeners).
 */
@Composable
fun LiteErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryLabel: String = stringResource(R.string.retry),
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LiteSpacing.screenPadding, vertical = LiteSpacing.rowSpacing),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LiteSpacing.small),
    ) {
        Text(
            text = message,
            style = LiteType.description,
            color = LiteColors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Box(
            modifier = Modifier
                .clip(LiteRadii.buttonShape)
                .background(LiteColors.accent)
                .clickable(onClick = onRetry)
                .defaultMinSize(minHeight = LiteSpacing.minTouchTarget)
                .padding(horizontal = LiteSpacing.screenPadding, vertical = LiteSpacing.small),
        ) {
            Text(
                text = retryLabel,
                style = LiteType.action,
                color = LiteColors.onAccent,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Previews (private; simulated values only, never wired to navigation).
// ---------------------------------------------------------------------------

@PreviewLightDark
@Composable
private fun LiteSkeletonPreview() {
    CloudStreamPreviewTheme {
        Column(
            Modifier
                .background(LiteColors.background)
                .padding(LiteSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(LiteSpacing.gridGap),
        ) {
            LiteSkeleton(
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(2f / 3f),
            )
            LiteSkeleton(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LiteSpacing.rowTitleHeight),
                shape = LiteRadii.buttonShape,
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun LiteEmptyStatePreview() {
    CloudStreamPreviewTheme {
        Column(Modifier.background(LiteColors.background)) {
            LiteEmptyState(
                title = "Nothing here yet",
                description = "Install an extension to start browsing.",
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun LiteErrorStatePreview() {
    CloudStreamPreviewTheme {
        Column(Modifier.background(LiteColors.background)) {
            LiteErrorState(
                message = "Could not load content.",
                onRetry = {},
            )
        }
    }
}
