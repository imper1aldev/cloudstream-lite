package com.lagradost.cloudstream3.ui.lite

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle

/**
 * Lite typography roles (MVP spec §3.7). Pure re-mapping of the existing
 * `AppFont` (productsans) M3 typography already provided by `CloudStreamTheme`
 * — no new fonts, no size overrides, stable by role not by density.
 *
 * Line limits/ellipsizing are applied at the call site (hero title: maxLines 2,
 * ellipsis; plot: max 4 lines + expand on tap, spec §3.10).
 */
object LiteType {
    /** Hero title (max 2 lines + ellipsis at call site) */
    val heroTitle: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.headlineMedium

    /** Row titles on Home */
    val rowTitle: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)

    /** Details screen title */
    val detailsTitle: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.headlineSmall

    /** Details screen title, large variant */
    val detailsTitleLarge: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.headlineMedium

    /** Metadata (year, duration, genre): pair with [LiteColors.onSurfaceVariant] */
    val metadata: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodySmall

    /** Plot / description body */
    val description: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyMedium

    /** Buttons / actions */
    val action: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelLarge
}
