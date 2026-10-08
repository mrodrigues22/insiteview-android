package com.getinsiteview.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette

// iOS `ContentUnavailableView` in Compose: the centred "nothing here" view of the tabs and the
// scanner. List sections and rows are :features' (`com.getinsiteview.features.ui`).

/**
 * iOS `ContentUnavailableView`: an icon, a title, a description and actions, centred. Scrolls
 * when it doesn't fit (large text). Inside a lazy list, pass [fillsScreen] = false.
 */
@Composable
fun ContentUnavailable(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: (@Composable () -> Unit)? = null,
    fillsScreen: Boolean = true,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val container = if (fillsScreen) modifier.fillMaxSize().verticalScroll(rememberScrollState()) else modifier.fillMaxWidth()
    Box(container, contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) {
                CompositionLocalProvider(LocalContentColor provides Palette.muted) { icon() }
                Spacer(Modifier.size(4.dp))
            }
            Text(title, style = IvType.body(22.sp, FontWeight.Bold), color = Palette.ink, textAlign = TextAlign.Center)
            if (description != null) {
                Text(description, style = IvType.body(15.sp), color = Palette.muted, textAlign = TextAlign.Center)
            }
            if (actions != null) {
                Spacer(Modifier.size(8.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = actions,
                )
            }
        }
    }
}

/** The large icon of a [ContentUnavailable]. */
@Composable
fun UnavailableIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp))
}
