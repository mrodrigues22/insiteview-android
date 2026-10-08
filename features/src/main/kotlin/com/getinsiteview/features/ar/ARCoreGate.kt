package com.getinsiteview.features.ar

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.design.PrimaryActionButton
import com.getinsiteview.design.SecondaryActionButton
import com.getinsiteview.features.R
import com.google.ar.core.ArCoreApk
import kotlinx.coroutines.delay

// Android only: iOS AR needs nothing installed. ARCore ("Google Play Services for AR") may be
// missing or too old on a phone that supports it, and some phones don't support it at all
// (docs/PLAN.md §1: AR is optional; those phones still get the 3D viewer).

/**
 * Shows [content] once ARCore is installed on this phone; otherwise offers to install it
 * (`ArCoreApk.requestInstall`), or says AR isn't available here.
 */
@Composable
fun ARCoreGate(onClose: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var availability by remember { mutableStateOf(ARCoreAvailability.CHECKING) }
    var checks by remember { mutableIntStateOf(0) }
    var installRequested by remember { mutableStateOf(false) }
    var installFailed by remember { mutableStateOf(false) }

    LaunchedEffect(checks) { availability = checkARCore(context) }

    fun requestInstall(userRequested: Boolean) {
        val host = activity ?: return
        try {
            when (ArCoreApk.getInstance().requestInstall(host, userRequested)) {
                ArCoreApk.InstallStatus.INSTALLED -> {
                    installRequested = false
                    availability = ARCoreAvailability.CHECKING
                    checks += 1
                }
                // Google Play opens; the answer comes when this activity resumes.
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> installRequested = true
                else -> Unit
            }
        } catch (_: Exception) {
            // Declined, not compatible after all, or Play unavailable.
            installRequested = false
            installFailed = true
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (installRequested) requestInstall(userRequested = false)
    }

    when (ARExperienceRules.arCoreStep(availability)) {
        ARCoreStep.READY -> content()
        ARCoreStep.CHECKING -> Box(Modifier.fillMaxSize().background(Palette.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Palette.accent)
        }
        ARCoreStep.NEEDS_INSTALL -> ARCoreMessage(
            title = stringResource(R.string.get_google_play_services_for_ar),
            message = stringResource(
                if (installFailed) R.string.google_play_services_for_ar_couldn_t_be_installed else R.string.insite_view_needs_google_play_services_for_ar,
            ),
            primary = stringResource(if (installFailed) R.string.try_again else R.string.install),
            onPrimary = {
                installFailed = false
                requestInstall(userRequested = true)
            },
            onClose = onClose,
        )
        ARCoreStep.UNSUPPORTED -> ARCoreMessage(
            title = stringResource(R.string.ar_isn_t_available_on_this_phone),
            message = stringResource(R.string.this_phone_can_t_show_the_model_in_your_room),
            primary = null,
            onPrimary = {},
            onClose = onClose,
        )
    }
}

/** ARCore's answer, waiting while it's still checking (it asks Google Play the first time). */
private suspend fun checkARCore(context: Context): ARCoreAvailability {
    val core = ArCoreApk.getInstance()
    repeat(CHECK_ATTEMPTS) {
        val availability = try {
            core.checkAvailability(context)
        } catch (_: Exception) {
            return ARCoreAvailability.UNKNOWN_ERROR
        }
        if (!availability.isTransient) return availability.mapped
        delay(CHECK_INTERVAL_MILLIS)
    }
    return ARCoreAvailability.UNKNOWN_ERROR
}

private const val CHECK_ATTEMPTS = 25
private const val CHECK_INTERVAL_MILLIS = 200L

private val ArCoreApk.Availability.mapped: ARCoreAvailability
    get() = when (this) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> ARCoreAvailability.SUPPORTED_INSTALLED
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED, ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> ARCoreAvailability.SUPPORTED_NOT_INSTALLED
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> ARCoreAvailability.UNSUPPORTED
        ArCoreApk.Availability.UNKNOWN_CHECKING -> ARCoreAvailability.CHECKING
        else -> ARCoreAvailability.UNKNOWN_ERROR
    }

/** ARCore missing or unsupported, or the session failed: what happened and the way out (3D stays). */
@Composable
internal fun ARCoreMessage(title: String, message: String, primary: String?, onPrimary: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Icon(Icons.Outlined.ViewInAr, contentDescription = null, tint = Palette.warn, modifier = Modifier.size(72.dp))
        Text(title.uppercase(), style = IvType.display(28.sp), color = Palette.ink, textAlign = TextAlign.Center)
        Text(message, style = IvType.body(17.sp), color = Palette.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (primary != null) PrimaryActionButton(primary, onClick = onPrimary)
            SecondaryActionButton(stringResource(if (primary != null) R.string.not_now else R.string.close), onClick = onClose)
        }
    }
}
