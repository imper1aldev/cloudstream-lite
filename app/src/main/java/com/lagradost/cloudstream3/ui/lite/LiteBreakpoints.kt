package com.lagradost.cloudstream3.ui.lite

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Lite adaptation breakpoints (MVP spec §3.11). The repo has no
 * `WindowSizeClass`/`sw600dp` qualifiers, so adaptation is defined by window
 * width in dp + orientation, using only `LocalConfiguration` — no new deps.
 * Consumed by F3/F4 (Home/Details adaptation, spec §7).
 */
enum class LiteWidthClass {
    /** < 600dp — phones */
    Compact,

    /** 600-839dp — small tablets / large landscape phones */
    Medium,

    /** >= 840dp — tablets */
    Expanded,
}

fun liteWidthClassOf(widthDp: Int): LiteWidthClass = when {
    widthDp < 600 -> LiteWidthClass.Compact
    widthDp < 840 -> LiteWidthClass.Medium
    else -> LiteWidthClass.Expanded
}

@Immutable
data class LiteBreakpoint(
    val widthClass: LiteWidthClass,
    val isLandscape: Boolean,
) {
    val isCompact: Boolean get() = widthClass == LiteWidthClass.Compact
    val isExpanded: Boolean get() = widthClass == LiteWidthClass.Expanded
}

@Composable
fun rememberLiteBreakpoint(): LiteBreakpoint {
    val config = LocalConfiguration.current
    return remember(config.screenWidthDp, config.orientation) {
        LiteBreakpoint(
            widthClass = liteWidthClassOf(config.screenWidthDp),
            isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE,
        )
    }
}
