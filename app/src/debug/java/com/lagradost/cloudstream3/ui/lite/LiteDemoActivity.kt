package com.lagradost.cloudstream3.ui.lite

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.theme.CloudStreamTheme

/**
 * DEV-ONLY demo host for the Lite components (T2.2 content, T2.3 states).
 *
 * Lives in the `debug` source set and is registered ONLY from
 * `app/src/debug/AndroidManifest.xml`, so the release build never sees it and
 * it is never part of the app's navigation graph. It exists purely so the
 * components can be rendered and screenshot on a device (see T2.2/T2.3 evidence).
 *
 * Launch on the prereleaseDebug flavour:
 *   adb shell am start -n \
 *     com.lagradost.cloudstream3.prerelease.debug/com.lagradost.cloudstream3.ui.lite.LiteDemoActivity
 */
class LiteDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CloudStreamTheme {
                LiteDemoScreen()
            }
        }
    }
}

@Composable
private fun LiteDemoScreen() {
    val items = remember { List(30) { it } }
    Column(
        Modifier
            .fillMaxSize()
            .background(LiteColors.background)
            .verticalScroll(rememberScrollState())
            .padding(top = LiteSpacing.screenPadding)
    ) {
        // --- States (T2.3): shown first so all three are visible on launch ---
        LiteRowHeader(
            title = "Skeleton",
            modifier = Modifier.padding(horizontal = LiteSpacing.screenPadding),
        )
        Row(
            Modifier.padding(horizontal = LiteSpacing.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(LiteSpacing.gridGap),
        ) {
            repeat(6) {
                LiteSkeleton(
                    modifier = Modifier
                        .width(120.dp)
                        .aspectRatio(2f / 3f),
                )
            }
        }

        LiteEmptyState(
            title = "Nothing here yet",
            description = "Install an extension to start browsing.",
            modifier = Modifier.padding(top = LiteSpacing.rowSpacing),
        )

        LiteErrorState(
            message = "Could not load content.",
            onRetry = { Log.i("LiteDemo", "retry") },
            modifier = Modifier.padding(top = LiteSpacing.rowSpacing),
        )

        // --- Content components (T2.2) ---
        LiteRow(
            title = "Posters (30)",
            items = items,
            key = { it },
            modifier = Modifier.padding(top = LiteSpacing.rowSpacing),
        ) { i ->
            LitePosterCard(
                imageUrl = null,
                contentDescription = "Poster $i",
                modifier = Modifier.width(120.dp),
                onClick = {},
            )
        }
        LiteRow(
            title = "Continue watching (30)",
            items = items,
            key = { it },
            modifier = Modifier.padding(top = LiteSpacing.rowSpacing, bottom = LiteSpacing.rowSpacing),
        ) { i ->
            LiteLandscapeCard(
                imageUrl = null,
                title = "Item $i",
                modifier = Modifier.width(200.dp),
                progress = (i % 5) / 5f,
                onClick = {},
            )
        }
    }
}
