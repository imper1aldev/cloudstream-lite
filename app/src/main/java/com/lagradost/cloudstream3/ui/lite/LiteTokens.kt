package com.lagradost.cloudstream3.ui.lite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.theme.CloudStreamPreviewTheme

/**
 * Lite visual tokens (MVP spec §3). Built OVER `CloudStreamTheme`: every color
 * is derived from `MaterialTheme.colorScheme`, so the Settings theme and accent
 * chosen by the user are always respected. No brand color is hard-coded here.
 *
 * glass-lite = translucency + subtle border + gradient scrims. NO real blur
 * (spec §3.2). Consumed by T2.2/T2.3 components and F3/F4 screens.
 */
object LiteColors {
    /** Base background (dark #111111) */
    val background: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background

    /** Card/container surface (spec §3.3 `surface`, dark #161616 = boxItemBackground) */
    val surface: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer

    /** Elevated surface (dark #1C1C20) */
    val surfaceElevated: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface

    /** Highest surface (dark #2B2C30) */
    val surfaceHighest: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant

    val onSurface: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface

    /** Secondary text / icons (dark #9BA0A4); only for text >= 12sp (spec §3.10) */
    val onSurfaceVariant: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

    /** Theme accent from Settings (default #3D50FA) */
    val accent: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary

    /** Progress indicator color = accent (spec §3.3) */
    val progress: Color
        @Composable @ReadOnlyComposable get() = accent

    val onAccent: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onPrimary

    val error: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error

    /**
     * glass-lite surface: base background at 75% (spec: black ~70-80%) for
     * floating top bars / hero button overlays / chips over imagery. No blur.
     */
    val surfaceGlass: Color
        @Composable @ReadOnlyComposable get() = background.copy(alpha = 0.75f)

    /** Glass border, spec 8-12% -> 10%. In dark themes onSurface is the near-white. */
    val glassBorder: Color
        @Composable @ReadOnlyComposable get() = onSurface.copy(alpha = 0.10f)

    /** Default card border, spec §3.4 white 6-8% -> 7% (theme-adaptive via onSurface). */
    val cardBorder: Color
        @Composable @ReadOnlyComposable get() = onSurface.copy(alpha = 0.07f)

    /** Selected/pressed card border: accent 40% (spec §3.4) */
    val selectedBorder: Color
        @Composable @ReadOnlyComposable get() = accent.copy(alpha = 0.40f)

    /** Press overlay: white 6% (spec §3.9; theme-adaptive via onSurface) */
    val pressedOverlay: Color
        @Composable @ReadOnlyComposable get() = onSurface.copy(alpha = 0.06f)
}

/** Gradient scrims (spec §3.5). Purposeful only: legibility over imagery. */
object LiteGradients {
    /**
     * Hero scrim: transparent -> background over the bottom ~70%, so title,
     * metadata and buttons stay AA-legible. Mirrors the existing
     * `background_shadow.xml` (transparent -> primaryBlackBackground) pattern.
     */
    val heroScrim: Brush
        @Composable @ReadOnlyComposable get() = Brush.verticalGradient(
            0.0f to LiteColors.background,
            0.18f to Color.Transparent,
            0.4f to Color.Transparent,
            1.0f to LiteColors.background,
        )

    /** Landscape card scrim (Continue Watching): bottom 40% for the title. */
    val cardScrim: Brush
        @Composable @ReadOnlyComposable get() = Brush.verticalGradient(
            0.6f to Color.Transparent,
            1.0f to LiteColors.background.copy(alpha = 0.90f),
        )
}

/** Corner radii (spec §3.4) */
object LiteRadii {
    /** Poster/cards, 10dp matches existing `rounded_image_radius` (spec 10-12dp) */
    val card = 10.dp

    /** Hero container */
    val hero = 16.dp

    /** Chips (spec 4-8dp) */
    val chip = 8.dp

    /** Buttons (spec 4-8dp) */
    val button = 8.dp

    /** Bottom sheet top corners */
    val sheet = 16.dp

    val cardShape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(card)
    val heroShape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(hero)
    val chipShape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(chip)
    val buttonShape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(button)
    val sheetShape: Shape =
        androidx.compose.foundation.shape.RoundedCornerShape(topStart = sheet, topEnd = sheet)
}

/** Spacing scale, base 4dp (spec §3.4/§3.10) */
object LiteSpacing {
    val base = 4.dp
    val small = 8.dp

    /** Poster grid gaps */
    val gridGap = 12.dp

    /** Screen horizontal padding */
    val screenPadding = 16.dp

    /** Vertical separation between rows */
    val rowSpacing = 32.dp

    /** Row title height */
    val rowTitleHeight = 32.dp

    /** Minimum touch target for every control (spec §3.10) */
    val minTouchTarget = 48.dp
}

/** Press/selection constants (spec §3.4/§3.9) */
object LiteInteraction {
    /** Pressed scale (spec 0.97-0.98) */
    const val PRESSED_SCALE = 0.98f
}

/** glass-lite surface: translucent background + 1dp subtle border. No blur. */
@Composable
fun Modifier.liteGlass(shape: Shape = LiteRadii.cardShape): Modifier =
    this
        .clip(shape)
        .background(LiteColors.surfaceGlass)
        .border(1.dp, LiteColors.glassBorder, shape)

/** Default Lite card outline (1dp, 7% onSurface; accent 40% when selected). */
@Composable
fun Modifier.liteCardBorder(
    shape: Shape = LiteRadii.cardShape,
    selected: Boolean = false,
): Modifier =
    this.border(
        1.dp,
        if (selected) LiteColors.selectedBorder else LiteColors.cardBorder,
        shape,
    )

@PreviewLightDark
@Composable
private fun LiteTokensPreview() {
    CloudStreamPreviewTheme {
        Column(
            Modifier
                .fillMaxSize()
                .background(LiteColors.background)
                .padding(LiteSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(LiteSpacing.rowSpacing),
        ) {
            // Color swatches
            Row(horizontalArrangement = Arrangement.spacedBy(LiteSpacing.small)) {
                @Composable
                fun Swatch(color: Color, border: Boolean = false) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(LiteRadii.chipShape)
                            .background(color)
                            .then(
                                if (border) Modifier.border(
                                    1.dp,
                                    LiteColors.cardBorder,
                                    LiteRadii.chipShape
                                ) else Modifier
                            )
                    )
                }
                Swatch(LiteColors.background, border = true)
                Swatch(LiteColors.surface)
                Swatch(LiteColors.surfaceElevated)
                Swatch(LiteColors.surfaceHighest)
                Swatch(LiteColors.accent)
                Swatch(LiteColors.error)
            }

            // glass-lite over fake imagery + card with scrim
            Row(horizontalArrangement = Arrangement.spacedBy(LiteSpacing.gridGap)) {
                Box(
                    Modifier
                        .size(120.dp, 80.dp)
                        .clip(LiteRadii.cardShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(LiteColors.accent, LiteColors.surfaceHighest)
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.liteGlass(LiteRadii.chipShape).padding(LiteSpacing.small)) {
                        Text("glass-lite", color = LiteColors.onSurface, style = LiteType.metadata)
                    }
                }
                Box(
                    Modifier
                        .size(120.dp, 80.dp)
                        .clip(LiteRadii.cardShape)
                        .background(LiteColors.surfaceElevated)
                        .liteCardBorder(),
                ) {
                    Box(Modifier.fillMaxSize().background(LiteGradients.cardScrim))
                    Text(
                        "Card scrim",
                        Modifier.align(Alignment.BottomStart).padding(LiteSpacing.small),
                        color = LiteColors.onSurface,
                        style = LiteType.metadata,
                    )
                }
            }

            // Typography roles
            Column(verticalArrangement = Arrangement.spacedBy(LiteSpacing.base)) {
                Text("Hero title", color = LiteColors.onSurface, style = LiteType.heroTitle)
                Text("Row title", color = LiteColors.onSurface, style = LiteType.rowTitle)
                Text("Description body", color = LiteColors.onSurface, style = LiteType.description)
                Text(
                    "Metadata secondary",
                    color = LiteColors.onSurfaceVariant,
                    style = LiteType.metadata,
                )
                Box(
                    Modifier
                        .clip(LiteRadii.buttonShape)
                        .background(LiteColors.accent)
                        .padding(horizontal = LiteSpacing.screenPadding, vertical = LiteSpacing.small)
                ) {
                    Text("Action", color = LiteColors.onAccent, style = LiteType.action)
                }
            }

            // Hero scrim demo over accent gradient
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(LiteRadii.heroShape)
                    .background(Brush.horizontalGradient(listOf(LiteColors.accent, LiteColors.error)))
            ) {
                Box(Modifier.fillMaxSize().background(LiteGradients.heroScrim))
                Text(
                    "Hero scrim",
                    Modifier.align(Alignment.BottomStart).padding(LiteSpacing.small),
                    color = LiteColors.onSurface,
                    style = LiteType.rowTitle,
                )
            }
        }
    }
}
