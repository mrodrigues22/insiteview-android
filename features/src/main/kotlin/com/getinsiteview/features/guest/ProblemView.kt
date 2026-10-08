package com.getinsiteview.features.guest

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getinsiteview.api.GuestProblem
import com.getinsiteview.core.PlayStoreLink
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.design.PrimaryActionButton
import com.getinsiteview.features.R
import java.net.URI

/**
 * What "Update" does on "Update required": open the app's Google Play page (iOS: the App Store
 * page, or in the App Clip the full app's overlay; Android has no Clip, docs/PLAN.md §6).
 *
 * @param playStore `null` until `PLAY_STORE_PACKAGE` is set: the app then shows no button.
 */
data class AppUpdate(val playStore: PlayStoreLink? = null)

/** Set at the app's root from [com.getinsiteview.features.app.AppDependencies.appUpdate]. */
val LocalAppUpdate = staticCompositionLocalOf { AppUpdate() }

/** What the guest state screen offers under its message. */
enum class ProblemAction {
    /** "Update": the app is too old. */
    UPDATE,

    /** "Try again", when it could help and the screen can retry. */
    RETRY,
    NONE;

    companion object {
        fun of(problem: GuestProblem, canRetry: Boolean): ProblemAction = when {
            problem == GuestProblem.UpdateRequired -> UPDATE
            canRetry && problem.isRetryable -> RETRY
            else -> NONE
        }
    }
}

/**
 * The guest state screens (docs/PLAN.md §3 "Guest flows", IOS-M2-02): paused, not live yet, link
 * expired or revoked, not found, offline, update required. Friendly words, no IFC terms, "Try
 * again" when it could help and "Update" when the app is too old. (A PIN building opens with
 * architecture only and the keypad instead.)
 */
@Composable
fun ProblemView(problem: GuestProblem, modifier: Modifier = Modifier, retry: (() -> Unit)? = null) {
    val appUpdate = LocalAppUpdate.current
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(problem.icon, contentDescription = null, tint = Palette.muted, modifier = Modifier.size(48.dp))
        Text(
            stringResource(problem.titleRes),
            style = IvType.body(22.sp, androidx.compose.ui.text.font.FontWeight.Bold),
            color = Palette.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(problem.messageRes),
            style = IvType.body(15.sp),
            color = Palette.muted,
            textAlign = TextAlign.Center,
        )
        when (ProblemAction.of(problem, canRetry = retry != null)) {
            ProblemAction.UPDATE -> {
                // No button before the app has a Play listing.
                val link = appUpdate.playStore
                if (link != null) {
                    Button(
                        onClick = { openPlayStore(context, link) },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.onAccent),
                    ) {
                        Text(stringResource(R.string.update))
                    }
                }
            }
            ProblemAction.RETRY -> PrimaryActionButton(
                text = stringResource(R.string.try_again),
                onClick = { retry?.invoke() },
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
            ProblemAction.NONE -> Unit
        }
    }
}

/** The Play Store app, else the same page on the web. */
internal fun openPlayStore(context: Context, link: PlayStoreLink) {
    if (!openURL(context, link.appURL)) openURL(context, link.webURL)
}

/** Opens a URL with whatever app handles it; returns whether one did. */
internal fun openURL(context: Context, url: URI): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}

private val GuestProblem.icon: ImageVector
    get() = when (this) {
        GuestProblem.NotFound -> Icons.AutoMirrored.Outlined.HelpOutline
        GuestProblem.NotLive -> Icons.Outlined.AccessTime
        GuestProblem.Paused -> Icons.Outlined.PauseCircle
        GuestProblem.LinkExpired, GuestProblem.LinkRevoked -> Icons.Outlined.Link
        GuestProblem.PinRequired, is GuestProblem.PinInvalid, is GuestProblem.PinLocked -> Icons.Outlined.Lock
        is GuestProblem.RateLimited -> Icons.Outlined.HourglassEmpty
        GuestProblem.Offline -> Icons.Outlined.CloudOff
        GuestProblem.UpdateRequired -> Icons.Outlined.SystemUpdate
        GuestProblem.Unavailable -> Icons.Outlined.WarningAmber
    }

private val GuestProblem.titleRes: Int
    get() = when (this) {
        GuestProblem.NotFound -> R.string.we_couldn_t_find_this_building
        GuestProblem.NotLive -> R.string.this_building_isn_t_live_yet
        GuestProblem.Paused -> R.string.this_digital_twin_is_paused
        GuestProblem.LinkExpired -> R.string.this_link_has_expired
        GuestProblem.LinkRevoked -> R.string.this_link_was_turned_off
        GuestProblem.PinRequired, is GuestProblem.PinInvalid, is GuestProblem.PinLocked -> R.string.this_building_needs_a_pin
        is GuestProblem.RateLimited -> R.string.too_many_tries
        GuestProblem.Offline -> R.string.you_re_offline
        GuestProblem.UpdateRequired -> R.string.update_required
        GuestProblem.Unavailable -> R.string.something_went_wrong
    }

private val GuestProblem.messageRes: Int
    get() = when (this) {
        GuestProblem.NotFound -> R.string.check_the_code_on_the_plate_and_try_again
        GuestProblem.NotLive -> R.string.the_builder_hasn_t_published_it_yet_try_again_later
        GuestProblem.Paused -> R.string.the_builder_needs_to_reactivate_it_before_it_can_be_opened
        GuestProblem.LinkExpired, GuestProblem.LinkRevoked -> R.string.ask_the_person_who_shared_it_for_a_new_link
        GuestProblem.PinRequired, is GuestProblem.PinInvalid -> R.string.ask_the_builder_or_owner_for_the_building_s_pin_then_try_aga
        is GuestProblem.PinLocked -> R.string.too_many_wrong_pins_were_entered_try_again_later
        is GuestProblem.RateLimited -> R.string.wait_a_moment_then_try_again
        GuestProblem.Offline -> R.string.connect_to_the_internet_to_open_this_building
        GuestProblem.UpdateRequired -> R.string.this_version_of_insite_view_can_t_open_this_building_update
        GuestProblem.Unavailable -> R.string.try_again_in_a_moment
    }
