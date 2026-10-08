@file:OptIn(ExperimentalMaterial3Api::class)

package com.getinsiteview.features.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getinsiteview.core.CatalogColor
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.design.toColor
import com.getinsiteview.features.R

// Building blocks for the list screens (iOS `List` with sections, `LabeledContent`, the
// navigation bar): BuildingHome, Rooms, Documents, the object card and Diagnostics.

/** A screen's top bar: a title (model data, so a plain string), back, and trailing actions. */
@Composable
fun IvTopBar(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, style = IvType.headline, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Palette.background,
            titleContentColor = Palette.ink,
            navigationIconContentColor = Palette.ink,
            actionIconContentColor = Palette.ink,
        ),
    )
}

/** A section heading (iOS `Section("…")`). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = IvType.eyebrow,
        color = Palette.muted,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

/** A section footer: small muted text under a section. */
@Composable
fun SectionFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = IvType.body(13.sp),
        color = Palette.muted,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/**
 * A list row on the surface colour; [onClick] makes it tappable, [chevron] shows it pushes a
 * screen (iOS `NavigationLink`).
 */
@Composable
fun ListRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    chevron: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().background(Palette.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .let { if (onClick != null) it.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else it }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
            if (chevron) {
                Spacer(Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.muted)
            }
        }
        HorizontalDivider(color = Palette.line, thickness = 0.5.dp, modifier = Modifier.padding(start = 16.dp))
    }
}

/** iOS `LabeledContent`: a label on the left, the value on the right. */
@Composable
fun LabeledRow(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    ListRow(modifier) {
        Text(label, style = IvType.body(), color = Palette.ink, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.weight(1f))
        Box(contentAlignment = Alignment.CenterEnd) { value() }
    }
}

/** A system colour dot. */
@Composable
fun ColorDot(color: CatalogColor, size: Int = 12) {
    ColorDot(color.toColor(), size)
}

@Composable
fun ColorDot(color: Color, size: Int = 12) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

/** A row's leading icon column (iOS `Label`'s icon). */
@Composable
fun RowIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color = Palette.accent) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.width(24.dp))
}
