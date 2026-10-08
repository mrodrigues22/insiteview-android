@file:OptIn(ExperimentalMaterial3Api::class)

package com.getinsiteview.features.guest

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import com.getinsiteview.api.PublicBuilding
import com.getinsiteview.api.PublicBuildingStatus
import com.getinsiteview.api.VisitVia
import com.getinsiteview.core.ProfessionalNeed
import com.getinsiteview.design.Chip
import com.getinsiteview.design.ChipRow
import com.getinsiteview.design.DisplayText
import com.getinsiteview.design.Eyebrow
import com.getinsiteview.design.IvType
import com.getinsiteview.design.LoadingBar
import com.getinsiteview.design.Palette
import com.getinsiteview.design.PrimaryActionButton
import com.getinsiteview.design.SecondaryActionButton
import com.getinsiteview.features.R
import com.getinsiteview.features.account.SignInPrompt
import com.getinsiteview.features.account.SignInReason
import com.getinsiteview.features.app.BuildingNavigator
import com.getinsiteview.features.app.BuildingRoute
import com.getinsiteview.features.building.BuildingSession
import com.getinsiteview.features.building.StatusLineView
import com.getinsiteview.features.objectcard.ObjectCardContext
import com.getinsiteview.features.objectcard.ObjectCardSheet
import com.getinsiteview.features.ui.IvTopBar
import com.getinsiteview.features.ui.ListRow
import com.getinsiteview.features.ui.RemoteImage
import com.getinsiteview.features.ui.RowIcon
import com.getinsiteview.features.viewer.ModelViewer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * The guest landing (A-03 screen 5): name, builder and verified date, a preview (thumbnail, then
 * the live 3D model), "See in AR" as the largest button, "Explore in 3D" and "What do you need?".
 * A PIN building shows the keypad entry (IOS-M2-02). "Save this building" keeps it in a free
 * account (sign-in first, IOS-M3-08; Android has no App Clip, so this is always the app's
 * section). Members see the status line (IOS-M3-03).
 */
@Composable
fun GuestLandingView(model: GuestFlowModel, navigator: BuildingNavigator) {
    val session = model.session
    val previewKey = rememberSaveable { UUID.randomUUID().toString() }
    val previewID = remember(previewKey) { UUID.fromString(previewKey) }
    // Set just before AR opens: the preview keeps its claim under AR (the scene comes back here
    // when AR lets go of it).
    val arOpening = remember { BooleanHolder() }
    var showsPin by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<String?>(null) }

    fun showAR() {
        arOpening.value = true
        navigator.showAR()
    }

    DisposableEffect(session, previewID) {
        session.claimScene(previewID, BuildingSession.SceneMode.VIEWER)
        onDispose {
            if (!arOpening.value) session.releaseScene(previewID)
        }
    }
    // A search result: its card first, with "Locate in AR" and "Show in 3D" (IOS-M3-04).
    LaunchedEffect(model) {
        val id = model.initialElementID
        if (id != null && !model.openedInitialElement) {
            model.openedInitialElement = true
            selection = id
        }
    }

    Scaffold(
        topBar = { IvTopBar(model.building?.displayCode ?: "", onBack = navigator::back) },
        containerColor = Palette.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Preview(model, previewID)
            Header(model)
            if (session.isPinLocked) {
                PinBanner { showsPin = true }
            }
            Actions(session, onAR = ::showAR, onExplore = { navigator.push(BuildingRoute.Viewer()) })
            Needs(session) { need -> navigator.push(BuildingRoute.Rooms.of(need)) }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LinkRow(Icons.AutoMirrored.Outlined.ListAlt, stringResource(R.string.building_details)) {
                    navigator.push(BuildingRoute.Home)
                }
                LinkRow(Icons.Outlined.Description, stringResource(R.string.documents)) {
                    navigator.push(BuildingRoute.Documents)
                }
            }
            if (session.canSave) {
                SaveBuildingSection(session, model.dependencies.signInPrompt)
            }
        }
    }

    ObjectCardSheet(
        selection = selection,
        onSelectionChange = { selection = it },
        session = session,
        context = ObjectCardContext.LIST,
        onLocateInAR = ::showAR,
        onShowIn3D = { id -> navigator.push(BuildingRoute.Viewer(elementID = id)) },
    )
    if (showsPin) {
        PinEntryView(session) { showsPin = false }
    }
}

/** A flag read in `onDispose`, so not Compose state. */
private class BooleanHolder(var value: Boolean = false)

// Preview

@Composable
private fun Preview(model: GuestFlowModel, previewID: UUID) {
    val session = model.session
    Box(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(240.dp).background(Palette.surface, RectangleShape),
        contentAlignment = Alignment.BottomStart,
    ) {
        val thumbnail = model.building?.thumbnailURL
        if (session.isArchitectureReady) {
            ModelViewer(
                scene = session.scene,
                isOwner = session.sceneOwner?.id == previewID,
                revision = session.sceneRevision,
                fitRequest = 0,
                isInteractive = false,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (thumbnail != null) {
            RemoteImage(thumbnail, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().background(Palette.surface)) }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Home, contentDescription = null, tint = Palette.line, modifier = Modifier.size(48.dp))
            }
        }
        if (session.phase == BuildingSession.Phase.Connecting || session.phase == BuildingSession.Phase.Loading) {
            LoadingBar(
                fraction = session.progress.fraction,
                caption = stringResource(R.string.loading_the_model),
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth()
                    .background(Palette.surface.copy(alpha = 0.85f), RectangleShape)
                    .padding(12.dp),
            )
        }
    }
}

// Header

@Composable
private fun Header(model: GuestFlowModel) {
    val session = model.session
    val locale = currentLocale()
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val building = model.building
        if (building != null) {
            DisplayText(building.name, size = 34.sp)
            val address = listOfNotNull(building.addressLine, building.city).joinToString(", ")
            if (address.isNotEmpty()) {
                Text(address, style = IvType.body(), color = Palette.muted)
            }
            BuilderLine(building, locale)
            val line = session.statusLine
            if (session.isMember && line != null) {
                StatusLineView(line)
            }
            if (building.status == PublicBuildingStatus.EXPIRED || session.visit?.readOnly == true) {
                IconLabel(Icons.Outlined.Archive, stringResource(R.string.read_only_archive), Palette.warn)
            }
            val expires = session.visit?.expiresAt
            if (session.visit?.via == VisitVia.LINK && expires != null) {
                IconLabel(
                    Icons.Outlined.Link,
                    stringResource(R.string.shared_access_until_x, formatDate(expires, "dMMMjmm", locale)),
                    Palette.muted,
                )
            }
        } else {
            DisplayText(session.name, size = 34.sp)
        }
    }
}

@Composable
private fun BuilderLine(building: PublicBuilding, locale: Locale) {
    val name = building.builder.name
    val date = building.verifiedAt?.let { formatDate(it, "MMMyyyy", locale) }
    val text = when {
        name != null && date != null -> stringResource(R.string.built_by_x_verified_x, name, date)
        name != null -> stringResource(R.string.built_by_x, name)
        date != null -> stringResource(R.string.verified_x, date)
        else -> return
    }
    Text(text, style = IvType.body(15.sp), color = Palette.muted)
}

@Composable
private fun IconLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, style = IvType.body(13.sp), color = color)
    }
}

/** A PIN building opened without the PIN shows architecture only. */
@Composable
private fun PinBanner(enterPin: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(Palette.warnSoft, RectangleShape).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = Palette.warn)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.pipes_and_wiring_are_hidden_until_the_building_s_pin_is_ente),
                style = IvType.body(15.sp),
                color = Palette.ink,
            )
            TextButton(onClick = enterPin, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(stringResource(R.string.enter_pin), style = IvType.body(15.sp, FontWeight.SemiBold), color = Palette.accent)
            }
        }
    }
}

// Actions

@Composable
private fun Actions(session: BuildingSession, onAR: () -> Unit, onExplore: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryActionButton(stringResource(R.string.see_in_ar), onClick = onAR)
        val plate = session.scannedPlate
        if (plate != null) {
            // Plate invocation (A-04): AR opens on "Point at the plate you scanned".
            Text(
                stringResource(R.string.point_your_camera_at_plate_n_to_line_the_model_up_with_your, plate),
                style = IvType.body(13.sp),
                color = Palette.muted,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SecondaryActionButton(stringResource(R.string.explore_in_3d), onClick = onExplore)
    }
}

// What do you need?

@Composable
private fun Needs(session: BuildingSession, open: (ProfessionalNeed) -> Unit) {
    val manifest = session.manifest
    val available = manifest?.let { m -> ProfessionalNeed.available(m.systems.map { it.key }) } ?: ProfessionalNeed.entries
    if (available.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(stringResource(R.string.what_do_you_need), Modifier.padding(horizontal = 16.dp))
        ChipRow {
            for (need in available) {
                Chip(title = needTitle(need), isSelected = false, onClick = { open(need) })
            }
        }
    }
}

/** A "What do you need?" chip's title (also the Rooms list's title). */
@Composable
fun needTitle(need: ProfessionalNeed): String = stringResource(
    when (need) {
        ProfessionalNeed.ELECTRICAL -> R.string.electrical
        ProfessionalNeed.PLUMBING -> R.string.plumbing
        ProfessionalNeed.HVAC -> R.string.hvac
        ProfessionalNeed.STRUCTURE -> R.string.structure
        ProfessionalNeed.ARCHITECTURE -> R.string.architecture
        ProfessionalNeed.OTHER -> R.string.other
    },
)

@Composable
private fun LinkRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit) {
    ListRow(onClick = onClick, chevron = true) {
        RowIcon(icon)
        Text(title, style = IvType.body(), color = Palette.ink)
    }
}

/**
 * "Save this building → free account" (IOS-M3-08): signed out, the app's sign-in sheet opens;
 * then the building is saved and shows up in Buildings.
 */
@Composable
fun SaveBuildingSection(session: BuildingSession, prompt: SignInPrompt) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(session) { session.refreshSaveState() }
    val state = session.saveState
    if (state == BuildingSession.SaveState.MINE) return
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(Palette.surface, RectangleShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state == BuildingSession.SaveState.SAVED) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.saved_to_your_buildings), style = IvType.body(15.sp, FontWeight.Medium), color = Palette.accent)
            }
        } else {
            Text(stringResource(R.string.keep_this_building), style = IvType.headline, color = Palette.ink)
            Text(stringResource(R.string.save_it_to_a_free_account_on_any_phone), style = IvType.body(15.sp), color = Palette.muted)
            if (state == BuildingSession.SaveState.SAVING) {
                Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Palette.accent, strokeWidth = 2.dp)
                }
            } else {
                SecondaryActionButton(stringResource(R.string.save_this_building), onClick = {
                    scope.launch { save(session, prompt) }
                })
            }
            if (state == BuildingSession.SaveState.FAILED) {
                Text(stringResource(R.string.couldn_t_save_the_building_try_again), style = IvType.body(13.sp), color = Palette.warn)
            }
        }
    }
}

private suspend fun save(session: BuildingSession, prompt: SignInPrompt) {
    if (!session.isSignedIn()) {
        if (!prompt.requestSignIn(SignInReason.SaveBuilding(session.name))) return
        session.refreshSaveState()
    }
    if (session.saveState == BuildingSession.SaveState.SAVED || session.saveState == BuildingSession.SaveState.MINE) return
    session.save()
}

@Composable
internal fun currentLocale(): Locale =
    ConfigurationCompat.getLocales(LocalConfiguration.current).get(0) ?: Locale.ROOT

/** A date in the locale's order for a CLDR skeleton ("MMMyyyy" → "Oct 2026"), in the device's zone. */
internal fun formatDate(instant: Instant, skeleton: String, locale: Locale): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault()).format(instant)
}
