package com.lagradost.cloudstream3.ui.lite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream4.theme.CloudStreamPreviewTheme

@Composable
fun LiteHero(
    title: String,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    metadata: String? = null,
    detailLabel: String = stringResource(R.string.lite_hero_details),
    fixedHeight: Dp? = null,
    onDetail: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (fixedHeight != null) Modifier.height(fixedHeight)
                else Modifier.aspectRatio(16f / 9f)
            )
            .background(LiteColors.background)
            .then(if (onDetail != null) Modifier.clickable(onClick = onDetail) else Modifier)
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(LiteGradients.heroScrim))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to LiteColors.background.copy(alpha = 0.65f),
            0.25f to Color.Transparent,
            0.75f to Color.Transparent,
            1f to LiteColors.background.copy(alpha = 0.65f),
        )))
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(LiteSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(LiteSpacing.small),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = LiteColors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!metadata.isNullOrBlank()) {
                Text(
                    text = metadata,
                    style = LiteType.metadata,
                    color = LiteColors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onDetail != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_outline_info_24), contentDescription = null,
                        tint = LiteColors.onSurfaceVariant, modifier = Modifier.width(18.dp).height(18.dp))
                    Spacer(Modifier.width(LiteSpacing.small))
                    Text(detailLabel, style = LiteType.metadata, color = LiteColors.onSurfaceVariant)
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun LiteHeroPreview() {
    CloudStreamPreviewTheme {
        Box(Modifier.background(LiteColors.background).padding(LiteSpacing.screenPadding)) {
            LiteHero(
                title = "Example hero title that wraps to two lines",
                imageUrl = null,
                metadata = "Action • 2024 • 1h 52m",
                onDetail = {},
            )
        }
    }
}
