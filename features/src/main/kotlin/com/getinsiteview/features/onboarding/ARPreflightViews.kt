package com.getinsiteview.features.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getinsiteview.core.ARExplainerFrame
import com.getinsiteview.core.ARPreflightStep
import com.getinsiteview.core.CameraAccess
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.design.PrimaryActionButton
import com.getinsiteview.design.SecondaryActionButton
import com.getinsiteview.features.R
import com.getinsiteview.features.ar.ARExperienceRules
import kotlinx.coroutines.launch

/**
 * The camera permission right now (iOS `CameraAccess.current`), from `checkSelfPermission` and the
 * rationale flag ([ARExperienceRules.cameraAccess]).
 *
 * @param askedThisScreen a permission request was answered on this screen.
 */
fun currentCameraAccess(context: Context, activity: Activity?, askedThisScreen: Boolean): CameraAccess {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val rationale = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA) } ?: false
    return ARExperienceRules.cameraAccess(granted = granted, showsRationale = rationale, askedThisScreen = askedThisScreen)
}

/** Animations are off (iOS Reduce Motion): the system's animator duration scale is 0. */
fun isReducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** The app's page in the system settings, where the camera permission is turned back on. */
fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // No settings app (some managed devices): nothing to open.
    }
}

/**
 * The first-time "Point · Align · Explore" explainer (A-05, IOS-M2-07): three short frames,
 * skippable, with the symbol animations off when animations are off.
 *
 * @param done called when it's finished or skipped.
 */
@Composable
fun ARExplainerView(done: () -> Unit) {
    val context = LocalContext.current
    val reduceMotion = remember { isReducedMotion(context) }
    val frames = ARExplainerFrame.entries
    val pager = rememberPagerState(pageCount = { frames.size })
    val scope = rememberCoroutineScope()
    val frame = frames[pager.currentPage]
    Column(
        Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = done) {
                Text(stringResource(R.string.skip), color = Palette.accent, style = IvType.body(17.sp))
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            ExplainerPage(frames[page], isCurrent = page == pager.currentPage, reduceMotion = reduceMotion)
        }
        PageDots(count = frames.size, current = pager.currentPage)
        PrimaryActionButton(
            text = stringResource(if (frame.next == null) R.string.start else R.string.next),
            onClick = {
                val next = frame.next
                if (next == null) {
                    done()
                } else {
                    scope.launch { if (reduceMotion) pager.scrollToPage(next.ordinal) else pager.animateScrollToPage(next.ordinal) }
                }
            },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun ExplainerPage(frame: ARExplainerFrame, isCurrent: Boolean, reduceMotion: Boolean) {
    val (icon, title, message) = when (frame) {
        ARExplainerFrame.POINT -> Triple(
            Icons.Outlined.QrCodeScanner, R.string.point,
            R.string.point_your_camera_at_the_insite_view_plate_on_the_wall_or_at,
        )
        ARExplainerFrame.ALIGN -> Triple(
            Icons.Outlined.Layers, R.string.align,
            R.string.hold_still_for_a_moment_while_the_model_lines_up_with_your_r,
        )
        ARExplainerFrame.EXPLORE -> Triple(
            Icons.Outlined.Visibility, R.string.explore,
            R.string.see_the_pipes_and_wiring_inside_the_walls_tap_one_to_find_ou,
        )
    }
    val titleText = stringResource(title)
    val messageText = stringResource(message)
    Column(
        Modifier.fillMaxSize().clearAndSetSemantics { contentDescription = "$titleText. $messageText" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.height(140.dp), contentAlignment = Alignment.Center) {
            PulsingIcon(icon, pulsing = isCurrent && !reduceMotion)
        }
        Text(titleText.uppercase(), style = IvType.display(34.sp), color = Palette.ink, textAlign = TextAlign.Center)
        Text(
            messageText,
            style = IvType.body(17.sp),
            color = Palette.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

/** iOS `.symbolEffect(.pulse, options: .repeating)`. */
@Composable
private fun PulsingIcon(icon: ImageVector, pulsing: Boolean) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
            label = "pulse",
        )
        value
    } else {
        1f
    }
    Icon(icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(88.dp).alpha(alpha))
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        repeat(count) { index ->
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (index == current) Palette.ink else Palette.line, CircleShape),
            )
        }
    }
}

/**
 * Before the system prompt: why the camera (A-05). Also the denied state, with a link to the app's
 * settings ("Don't ask again" on Android).
 */
@Composable
fun CameraPermissionView(step: ARPreflightStep, requestAccess: () -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val denied = step as? ARPreflightStep.CameraDenied
    Column(
        Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Icon(
            if (denied != null) Icons.Outlined.NoPhotography else Icons.Outlined.PhotoCamera,
            contentDescription = null,
            tint = if (denied != null) Palette.warn else Palette.accent,
            modifier = Modifier.size(72.dp),
        )
        Text(
            stringResource(if (denied != null) R.string.camera_access_is_off else R.string.allow_the_camera).uppercase(),
            style = IvType.display(28.sp),
            color = Palette.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(
                when {
                    denied == null -> R.string.insite_view_shows_the_building_s_pipes_and_wiring_over_what
                    denied.canOpenSettings -> R.string.turn_on_camera_for_insite_view_in_settings_to_see_the_model
                    else -> R.string.the_camera_is_restricted_on_this_phone_you_can_still_explore
                },
            ),
            style = IvType.body(17.sp),
            color = Palette.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (denied != null) {
                if (denied.canOpenSettings) {
                    PrimaryActionButton(stringResource(R.string.open_settings), onClick = { openAppSettings(context) })
                }
                SecondaryActionButton(stringResource(R.string.not_now), onClick = close)
            } else {
                PrimaryActionButton(stringResource(R.string.continue_action), onClick = requestAccess)
                SecondaryActionButton(stringResource(R.string.not_now), onClick = close)
            }
        }
    }
}
