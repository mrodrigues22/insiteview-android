@file:OptIn(ExperimentalMaterial3Api::class)

package com.getinsiteview.features.guest

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import com.getinsiteview.api.GuestProblem
import com.getinsiteview.api.PinPad
import com.getinsiteview.design.DisplayText
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.features.R
import com.getinsiteview.features.building.BuildingSession
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The PIN keypad's model (IOS-M2-02): typing, sending with `POST /v1/visit/unlock`, and the
 * wrong-PIN and lockout states from [PinPad].
 */
class PinEntryModel(private val session: BuildingSession, private val scope: CoroutineScope) {
    var pad by mutableStateOf(PinPad())
        private set
    var isUnlocked by mutableStateOf(false)
        private set

    /** Bumped on each wrong PIN, for the shake and the error haptic. */
    var wrongCount by mutableIntStateOf(0)
        private set

    fun type(digit: Int) {
        val typed = pad.type(digit)
        pad = typed.pad
        val pin = typed.pin ?: return
        scope.launch { submit(pin) }
    }

    fun deleteLast() {
        pad = pad.deleteLast()
    }

    fun refresh(at: Instant) {
        pad = pad.refresh(at)
    }

    /** Access ended (e.g. the building was paused): the keypad closes over the state screen. */
    val accessEnded: Boolean get() = session.accessProblem != null

    private suspend fun submit(pin: String) {
        try {
            session.unlock(pin)
            isUnlocked = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pad = pad.failed(e)
            if (pad.status is PinPad.Status.Wrong) wrongCount += 1
        }
    }
}

/**
 * "Enter the building's PIN": four dots, a number pad, attempts left and the lockout time. A
 * full-height sheet (iOS `.presentationDetents([.large])`).
 */
@Composable
fun PinEntryView(session: BuildingSession, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember(session) { PinEntryModel(session, scope) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current
    val shake = remember { Animatable(0f) }

    LaunchedEffect(model.wrongCount) {
        if (model.wrongCount == 0) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        shake.animateTo(0f, keyframes {
            durationMillis = 280
            10f at 60
            -10f at 140
            5f at 220
            0f at 280
        })
    }
    LaunchedEffect(model.isUnlocked) {
        if (model.isUnlocked) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onDismiss()
        }
    }
    LaunchedEffect(model.accessEnded) {
        if (model.accessEnded) onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Palette.background) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Palette.accent) }
            }
            Column(
                Modifier.padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(36.dp))
                DisplayText(stringResource(R.string.enter_the_building_s_pin), size = 26.sp)
                Text(
                    stringResource(R.string.the_builder_or_owner_can_give_it_to_you_pipes_and_wiring_sho),
                    style = IvType.body(15.sp),
                    color = Palette.muted,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.size(28.dp))
            Dots(model.pad.digits.length, Modifier.offset(x = shake.value.dp))
            Spacer(Modifier.size(28.dp))
            PinStatus(model)
            Spacer(Modifier.size(28.dp))
            Keypad(model)
        }
    }
}

@Composable
private fun Dots(count: Int, modifier: Modifier) {
    val description = stringResource(R.string.n_of_n_digits_entered, count, PinPad.LENGTH)
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        repeat(PinPad.LENGTH) { index ->
            Box(
                Modifier
                    .size(16.dp)
                    .background(if (index < count) Palette.ink else Color.Transparent, CircleShape)
                    .border(1.5.dp, Palette.ink, CircleShape),
            )
        }
    }
}

@Composable
private fun PinStatus(model: PinEntryModel) {
    var now by remember { mutableStateOf(Instant.now()) }
    // iOS `TimelineView(.periodic(by: 1))`: the lockout counts down every second.
    LaunchedEffect(model) {
        while (true) {
            delay(1000)
            now = Instant.now()
            model.refresh(now)
        }
    }
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current).get(0) ?: Locale.ROOT
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        val warn = Palette.warn
        when (val status = model.pad.status) {
            PinPad.Status.Entering -> Text(" ")
            PinPad.Status.Checking -> CircularProgressIndicator(Modifier.size(24.dp), color = Palette.accent, strokeWidth = 2.dp)
            is PinPad.Status.Wrong -> {
                val left = status.attemptsLeft
                val text = if (left != null) {
                    pluralStringResource(R.plurals.wrong_pin_n_try_left, left, left)
                } else {
                    stringResource(R.string.wrong_pin_try_again)
                }
                Text(text, color = warn, style = IvType.body(16.sp), textAlign = TextAlign.Center)
            }
            is PinPad.Status.Locked -> {
                val seconds = model.pad.secondsUntilUnlock(now)
                if (seconds != null) {
                    Text(
                        stringResource(R.string.too_many_wrong_pins_try_again_in_x, formatLockout(seconds, locale)),
                        color = warn, style = IvType.body(16.sp), textAlign = TextAlign.Center,
                    )
                } else {
                    Text(" ")
                }
            }
            is PinPad.Status.Failed -> Text(
                if (status.problem == GuestProblem.Offline) {
                    stringResource(R.string.you_re_offline_connect_and_try_again)
                } else {
                    stringResource(R.string.something_went_wrong_try_again)
                },
                color = warn, style = IvType.body(16.sp), textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Keypad(model: PinEntryModel) {
    val isBusy = model.pad.status == PinPad.Status.Checking
    val haptics = LocalHapticFeedback.current
    fun tap(action: () -> Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
        action()
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.alpha(if (isBusy) 0.5f else 1f)) {
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                for (column in 1..3) {
                    val digit = row * 3 + column
                    Key(digit, enabled = !isBusy) { tap { model.type(digit) } }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Spacer(Modifier.size(72.dp))
            Key(0, enabled = !isBusy) { tap { model.type(0) } }
            IconButton(onClick = { tap { model.deleteLast() } }, enabled = !isBusy, modifier = Modifier.size(72.dp)) {
                Icon(
                    Icons.AutoMirrored.Outlined.Backspace,
                    contentDescription = stringResource(R.string.delete),
                    tint = Palette.ink,
                )
            }
        }
    }
}

@Composable
private fun Key(digit: Int, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            .background(Palette.surface, RectangleShape)
            .border(1.dp, Palette.line, RectangleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(digit.toString(), style = IvType.mono(28.sp), color = Palette.ink)
    }
}

/**
 * "2 minutes, 30 seconds": hours, minutes and seconds, at most two units, written out (iOS
 * `Duration.formatted(.units(allowed: [.hours, .minutes, .seconds], width: .wide, maximumUnitCount: 2))`).
 */
internal fun formatLockout(seconds: Int, locale: Locale): String {
    val measures = lockoutUnits(seconds).map { (unit, value) ->
        Measure(
            value,
            when (unit) {
                LockoutUnit.HOURS -> MeasureUnit.HOUR
                LockoutUnit.MINUTES -> MeasureUnit.MINUTE
                LockoutUnit.SECONDS -> MeasureUnit.SECOND
            },
        )
    }
    return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE).formatMeasures(*measures.toTypedArray())
}
