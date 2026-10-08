package com.getinsiteview.features.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Domain
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getinsiteview.core.ProfessionalNeed
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.features.R
import com.getinsiteview.features.app.BuildingNavigator
import com.getinsiteview.features.app.BuildingRoute
import com.getinsiteview.features.building.BuildingSession
import com.getinsiteview.features.guest.AccessGate
import com.getinsiteview.features.guest.needTitle
import com.getinsiteview.features.ui.ColorDot
import com.getinsiteview.features.ui.IvTopBar
import com.getinsiteview.features.ui.ListRow
import com.getinsiteview.features.ui.RowIcon
import com.getinsiteview.features.ui.SectionFooter
import com.getinsiteview.features.ui.SectionHeader
import com.getinsiteview.features.viewer.SystemChips
import com.getinsiteview.features.viewer.ViewerFocus
import com.getinsiteview.modelkit.RoomEntry

/**
 * The professional flow (A-05, IOS-M2-08): "What do you need?" preselects a trade's systems and
 * subsystems, then this Rooms list from the manifest's `spaces`; a room opens the 3D viewer
 * focused on it (and AR from there). Also reachable from BuildingHome with no trade.
 */
@Composable
fun RoomsView(session: BuildingSession, navigator: BuildingNavigator, need: ProfessionalNeed?) {
    var appliedNeed by rememberSaveable { mutableStateOf(false) }
    // The trade's systems in scope, or everything.
    val systems = need?.systems?.toSet()
    val rooms = session.rooms(systems)

    LaunchedEffect(Unit) {
        if (!appliedNeed) {
            appliedNeed = true
            if (need != null) session.apply(need)
        }
    }
    // The manifest arrived after the screen opened: preselect now.
    val hasManifest = session.manifest != null
    var sawManifest by rememberSaveable { mutableStateOf(hasManifest) }
    LaunchedEffect(hasManifest) {
        if (hasManifest != sawManifest) {
            sawManifest = hasManifest
            if (need != null && hasManifest) session.apply(need)
        }
    }

    Scaffold(
        topBar = { IvTopBar(need?.let { needTitle(it) } ?: stringResource(R.string.rooms), onBack = navigator::back) },
        containerColor = Palette.background,
    ) { padding ->
        AccessGate(session, Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (need != null) {
                    item { SystemChips(session, BuildingSession.SceneMode.VIEWER) }
                    item { SectionFooter(stringResource(R.string.pick_a_room_to_see_what_s_inside_its_walls_and_floor)) }
                }
                item { SectionHeader("") }
                item {
                    ListRow(onClick = { navigator.push(BuildingRoute.Viewer.of(ViewerFocus(need = need))) }, chevron = true) {
                        RowIcon(Icons.Outlined.Domain)
                        Text(stringResource(R.string.whole_building), style = IvType.body(), color = Palette.ink)
                    }
                }
                if (rooms.isEmpty()) {
                    if (session.phase == BuildingSession.Phase.Connecting || session.phase == BuildingSession.Phase.Loading) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Palette.accent)
                            }
                        }
                    } else {
                        item { SectionHeader("") }
                        item {
                            ListRow {
                                Text(
                                    stringResource(R.string.this_model_has_no_rooms_open_the_whole_building_instead),
                                    style = IvType.body(),
                                    color = Palette.muted,
                                )
                            }
                        }
                    }
                } else {
                    for (group in storeyGroups(rooms)) {
                        item {
                            val name = session.storeyName(group.storeyID)
                            when {
                                name != null -> SectionHeader(name)
                                group.storeyID != null -> SectionHeader(stringResource(R.string.level))
                                else -> SectionHeader("")
                            }
                        }
                        for (room in group.rooms) {
                            item {
                                ListRow(
                                    onClick = {
                                        navigator.push(
                                            BuildingRoute.Viewer.of(ViewerFocus(need = need, storeyID = room.storeyID, roomID = room.id)),
                                        )
                                    },
                                    chevron = true,
                                ) {
                                    RoomRow(session, room)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Rooms on one storey, in list order. */
data class StoreyGroup(val storeyID: String?, val rooms: List<RoomEntry>)

/** Consecutive rooms on the same storey (the list is sorted by storey). */
fun storeyGroups(rooms: List<RoomEntry>): List<StoreyGroup> {
    val groups = ArrayList<StoreyGroup>()
    for (room in rooms) {
        val last = groups.lastOrNull()
        if (last != null && last.storeyID == room.storeyID) {
            groups[groups.size - 1] = last.copy(rooms = last.rooms + room)
        } else {
            groups.add(StoreyGroup(room.storeyID, listOf(room)))
        }
    }
    return groups
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.RoomRow(session: BuildingSession, room: RoomEntry) {
    val dimmed = if (room.elementCount > 0) 1f else 0.6f
    Column(Modifier.weight(1f).alpha(dimmed), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val name = room.space.displayName
        if (name != null) {
            Text(name, style = IvType.body(), color = Palette.ink)
        } else {
            Text(stringResource(R.string.unnamed_room), style = IvType.body(), color = Palette.muted)
        }
        Text(
            if (room.elementCount > 0) {
                pluralStringResource(R.plurals.n_item, room.elementCount, room.elementCount)
            } else {
                stringResource(R.string.nothing_to_show_here)
            },
            style = IvType.body(13.sp),
            color = Palette.muted,
        )
    }
    Row(Modifier.alpha(dimmed), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val catalog = session.catalog
        for (system in room.systems.sortedBy { catalog?.systemOrder(it) ?: 0 }) {
            val label = session.systemName(system)
            Box(Modifier.semantics { contentDescription = label }) {
                ColorDot(session.systemColor(system), 10)
            }
        }
    }
}
