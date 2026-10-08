package com.getinsiteview.features.building

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getinsiteview.core.BuildingStatusLine
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.features.R

/**
 * A member's status line for a building (A-02, IOS-M3-03): "Trial · 9 days left", "Active until
 * 2036", "Paused · Activate on the web". Plain text: billing happens on the web, so there is no
 * link or button here (master PLAN §11).
 */
@Composable
fun StatusLineView(line: BuildingStatusLine, modifier: Modifier = Modifier) {
    val color = if (line.isAttention) Palette.warn else Palette.muted
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(line.icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(statusLineText(line), style = IvType.body(15.sp), color = color)
    }
}

/** The line's words (also used by the app's Buildings rows). */
@Composable
fun statusLineText(line: BuildingStatusLine): String = when (line) {
    BuildingStatusLine.NoModel -> stringResource(R.string.no_model_yet)
    BuildingStatusLine.NotLive -> stringResource(R.string.not_live_yet)
    is BuildingStatusLine.Trial -> {
        val days = line.daysLeft
        when {
            days == null -> stringResource(R.string.trial)
            days <= 0 -> stringResource(R.string.trial_ends_today)
            else -> pluralStringResource(R.plurals.trial_n_day_left, days, days)
        }
    }
    is BuildingStatusLine.Active -> line.untilYear?.let { stringResource(R.string.active_until_x, it.toString()) }
        ?: stringResource(R.string.active)
    BuildingStatusLine.Paused -> stringResource(R.string.paused_activate_on_the_web)
    BuildingStatusLine.Expired -> stringResource(R.string.expired_read_only)
    is BuildingStatusLine.Other -> line.raw
}

private val BuildingStatusLine.icon: ImageVector
    get() = when (this) {
        BuildingStatusLine.NoModel -> Icons.Outlined.Inbox
        BuildingStatusLine.NotLive -> Icons.Outlined.AccessTime
        is BuildingStatusLine.Trial -> Icons.Outlined.HourglassEmpty
        is BuildingStatusLine.Active -> Icons.Outlined.Verified
        BuildingStatusLine.Paused -> Icons.Outlined.PauseCircle
        BuildingStatusLine.Expired -> Icons.Outlined.Archive
        is BuildingStatusLine.Other -> Icons.Outlined.Info
    }
