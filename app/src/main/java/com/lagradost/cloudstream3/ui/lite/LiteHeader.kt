package com.lagradost.cloudstream3.ui.lite

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.findNavController
import com.lagradost.cloudstream3.R

@Composable
fun LiteHeader(title: String, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = LiteSpacing.screenPadding),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = LiteType.rowTitle, color = LiteColors.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onSearch, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.search_icon), stringResource(R.string.lite_search_catalog),
                tint = LiteColors.onSurface)
        }
    }
}

fun openLiteSearch(view: View) {
    view.findNavController().navigate(R.id.navigation_search, Bundle().apply {
        putBoolean("lite_focus_search", true)
    })
}
