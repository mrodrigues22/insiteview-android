package com.getinsiteview.features.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getinsiteview.core.ProfessionalNeed
import com.getinsiteview.design.Chip
import com.getinsiteview.design.ChipRow
import com.getinsiteview.design.IvType
import com.getinsiteview.design.LoadingBar
import com.getinsiteview.design.Palette
import com.getinsiteview.design.toColor
import com.getinsiteview.features.R
import com.getinsiteview.features.app.BuildingNavigator
import com.getinsiteview.features.building.BuildingSession
import com.getinsiteview.features.guest.AccessGate
import com.getinsiteview.features.objectcard.ObjectCardContext
import com.getinsiteview.features.objectcard.ObjectCardSheet
import com.getinsiteview.features.ui.IvTopBar
import com.getinsiteview.core.AnalyticsEvent
import com.getinsiteview.modelkit.SubsystemKey
import java.util.UUID
import kotlin.math.roundToInt

/** What the 3D viewer opens on: a trade's filters, a set of systems, a storey, a room, an element. */
data class ViewerFocus(
    /** "What do you need?": preselects systems and subsystems. */
    val need: ProfessionalNeed? = null,
    val systems: List<String>? = null,
    val storeyID: String? = null,
    /** A room from the Rooms list: the camera focuses on it (IOS-M2-08). */
    val roomID: String? = null,
    /** "Show in 3D" from search or the equipment list: the camera on it, its card open (IOS-M3-05). */
    val elementID: String? = null,
)

/**
 * Viewer3D (IOS-M1-08, M2-08, M3-05): orbit camera, storey picker, system chips, the focused room
 * or element, tap → object card ("Locate in AR" from there), and AR with the same focus.
 */
@Composable
fun Viewer3DView(session: BuildingSession, navigator: BuildingNavigator, focus: ViewerFocus = ViewerFocus()) {
    val ownerKey = rememberSaveable { UUID.randomUUID().toString() }
    val ownerID = remember(ownerKey) { UUID.fromString(ownerKey) }
    var selection by remember { mutableStateOf<String?>(null) }
    var fitRequest by remember { mutableIntStateOf(0) }
    var appliedFocus by rememberSaveable { mutableStateOf(false) }
    // Read in `onDispose`: under AR, keep the claim (the scene comes back here when AR lets go).
    val arOpening = remember { BooleanFlag() }

    fun showAR() {
        arOpening.value = true
        navigator.showAR()
    }

    DisposableEffect(session, ownerID) {
        session.claimScene(ownerID, BuildingSession.SceneMode.VIEWER)
        if (!appliedFocus) {
            appliedFocus = true
            selection = applyFocus(session, focus)
        }
        onDispose {
            session.select(null)
            if (!arOpening.value) session.releaseScene(ownerID)
        }
    }

    Scaffold(
        topBar = {
            IvTopBar(session.name, onBack = navigator::back) {
                IconButton(onClick = ::showAR) {
                    Icon(Icons.Outlined.ViewInAr, contentDescription = stringResource(R.string.see_in_ar))
                }
                StoreyMenu(session, BuildingSession.SceneMode.VIEWER)
                IconButton(onClick = { fitRequest += 1 }) {
                    Icon(Icons.Outlined.ZoomOutMap, contentDescription = stringResource(R.string.fit_to_screen))
                }
            }
        },
        containerColor = Palette.background,
    ) { padding ->
        AccessGate(session, Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                val modelLabel = stringResource(R.string.s_3d_model)
                ModelViewer(
                    scene = session.scene,
                    isOwner = session.sceneOwner?.id == ownerID,
                    revision = session.sceneRevision,
                    fitRequest = fitRequest,
                    focusRequest = session.focusRevision,
                    focusBounds = { session.focusBounds },
                    onTap = { id -> selection = id },
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = modelLabel },
                )
                Column(
                    Modifier.fillMaxWidth().background(Palette.surface.copy(alpha = 0.92f)).padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (session.phase == BuildingSession.Phase.Loading || session.phase == BuildingSession.Phase.Connecting) {
                        val percent = (session.progress.fraction * 100).roundToInt()
                        LoadingBar(
                            session.progress.fraction,
                            stringResource(R.string.loading_the_model_npercent, percent),
                            Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    val roomID = session.viewerFilters.roomID
                    if (roomID != null) {
                        RoomFocusChip(session.roomName(roomID)) {
                            session.focusRoom(null, BuildingSession.SceneMode.VIEWER)
                            session.focusRoom(null, BuildingSession.SceneMode.AR)
                        }
                    }
                    SystemChips(session, BuildingSession.SceneMode.VIEWER)
                }
            }
        }
    }

    ObjectCardSheet(
        selection = selection,
        onSelectionChange = { selection = it },
        session = session,
        context = ObjectCardContext.VIEWER,
        onLocateInAR = ::showAR,
    )
}

/** A flag read in `onDispose`, so not Compose state. */
private class BooleanFlag(var value: Boolean = false)

/**
 * Applies the viewer's focus once, when it first opens; returns the element whose card opens
 * ("Show in 3D").
 */
private fun applyFocus(session: BuildingSession, focus: ViewerFocus): String? {
    session.track { AnalyticsEvent.view3DOpened(it) }
    focus.need?.let { session.apply(it) }
    focus.systems?.let { session.focus(it, BuildingSession.SceneMode.VIEWER) }
    focus.storeyID?.let { session.setStorey(it, BuildingSession.SceneMode.VIEWER) }
    // A room from the Rooms list: the same focus in AR, where the guest goes next.
    if (focus.roomID != session.viewerFilters.roomID) {
        session.focusRoom(focus.roomID, BuildingSession.SceneMode.VIEWER)
        session.focusRoom(focus.roomID, BuildingSession.SceneMode.AR)
    }
    // "Show in 3D": frame the element and open its card.
    val elementID = focus.elementID ?: return null
    session.focusElement(elementID)
    return elementID
}

/** "Kitchen ✕": the focused room; tapping clears the focus. */
@Composable
fun RoomFocusChip(name: String?, clear: () -> Unit) {
    val hint = stringResource(R.string.shows_the_whole_level_again)
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .background(Palette.accentSoft, RectangleShape)
            .clickable(onClickLabel = hint, onClick = clear)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.MeetingRoom, contentDescription = null, tint = Palette.ink, modifier = Modifier.size(18.dp))
        Text(name ?: stringResource(R.string.room), style = IvType.body(15.sp, FontWeight.Medium), color = Palette.ink)
        Icon(Icons.Filled.Cancel, contentDescription = null, tint = Palette.muted, modifier = Modifier.size(18.dp))
    }
}

/**
 * System chips: only systems in scope; a system whose chunk failed shows as unavailable. AR leaves
 * architecture to the See inside slider.
 */
@Composable
fun SystemChips(session: BuildingSession, mode: BuildingSession.SceneMode) {
    val filters = session.filters(mode)
    val systems = if (mode == BuildingSession.SceneMode.AR) session.serviceSystems else session.systems
    val unavailable = stringResource(R.string.couldn_t_load)
    ChipRow {
        for (system in systems) {
            val failed = system.key in session.failedSystems
            Chip(
                title = session.systemName(system.key),
                isSelected = system.key in filters.systems,
                onClick = { session.toggleSystem(system.key, mode) },
                color = session.systemColor(system.key).toColor(),
                modifier = Modifier
                    .alpha(if (failed) 0.4f else 1f)
                    .semantics { if (failed) stateDescription = unavailable },
            )
        }
    }
}

/** Subsystem chips for the systems that are on (hot water, drainage, …). */
@Composable
fun SubsystemChips(session: BuildingSession, mode: BuildingSession.SceneMode) {
    val filters = session.filters(mode)
    val items = session.serviceSystems
        .filter { it.key in filters.systems }
        .flatMap { system -> system.subsystems.map { SubsystemKey(system.key, it) } }
    if (items.isEmpty()) return
    ChipRow {
        for (item in items) {
            Chip(
                title = session.subsystemName(item.subsystem, item.system),
                isSelected = filters.showsSubsystem(item.subsystem, item.system),
                onClick = { session.toggleSubsystem(item.subsystem, item.system, mode) },
                color = session.subsystemColor(item.subsystem, item.system).toColor(),
            )
        }
    }
}

/** Storey picker: all levels, or one (a toolbar button with a menu). */
@Composable
fun StoreyMenu(session: BuildingSession, mode: BuildingSession.SceneMode) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = !(session.storeysTopDown.size < 2 && mode == BuildingSession.SceneMode.VIEWER),
        ) {
            Icon(Icons.Outlined.Layers, contentDescription = stringResource(R.string.level))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            StoreyPicker(session, mode) { expanded = false }
        }
    }
}

/**
 * The levels as menu items, the current one checked: "All levels" (3D viewer only), then each
 * storey from the top down. For the 3D viewer's menu and AR's ⋮ menu.
 */
@Composable
fun ColumnScope.StoreyPicker(session: BuildingSession, mode: BuildingSession.SceneMode, onPicked: () -> Unit = {}) {
    val selected = session.filters(mode).storeyID
    val pick: (String?) -> Unit = { storeyID ->
        session.setStorey(storeyID, mode)
        onPicked()
    }
    if (mode == BuildingSession.SceneMode.VIEWER) {
        StoreyOption(stringResource(R.string.all_levels), isSelected = selected == null) { pick(null) }
    }
    for (storey in session.storeysTopDown) {
        StoreyOption(storey.name ?: "–", isSelected = selected == storey.id) { pick(storey.id) }
    }
}

@Composable
private fun StoreyOption(title: String, isSelected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title) },
        onClick = onClick,
        trailingIcon = {
            if (isSelected) Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.accent)
        },
    )
}
