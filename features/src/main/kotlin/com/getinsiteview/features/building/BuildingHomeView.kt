package com.getinsiteview.features.building

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getinsiteview.core.CatalogColor
import com.getinsiteview.design.IvType
import com.getinsiteview.design.LoadingBar
import com.getinsiteview.design.Palette
import com.getinsiteview.design.PrimaryActionButton
import com.getinsiteview.design.SecondaryActionButton
import com.getinsiteview.features.R
import com.getinsiteview.features.app.BuildingNavigator
import com.getinsiteview.features.app.BuildingRoute
import com.getinsiteview.features.guest.AccessGate
import com.getinsiteview.features.objectcard.ObjectCardContext
import com.getinsiteview.features.objectcard.ObjectCardSheet
import com.getinsiteview.features.ui.ColorDot
import com.getinsiteview.features.ui.IvTopBar
import com.getinsiteview.features.ui.ListRow
import com.getinsiteview.features.ui.RowIcon
import com.getinsiteview.features.ui.SectionFooter
import com.getinsiteview.features.ui.SectionHeader
import com.getinsiteview.features.viewer.ViewerFocus
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * BuildingHome (A-03 screen 1, IOS-M1-07): "View in AR" as the largest button, the 3D model, quick
 * access to the systems in scope, the equipment list, the rooms and the documents. Search asks the
 * server and falls back to the phone's copy offline (IOS-M3-04); an element opens its card with
 * "Locate in AR" and "Show in 3D" (IOS-M3-05). Members see the building's status line (IOS-M3-03).
 */
@Composable
fun BuildingHomeView(session: BuildingSession, navigator: BuildingNavigator) {
    var selection by remember { mutableStateOf<String?>(null) }
    val search = remember(session) { SearchModel() }
    LaunchedEffect(search.query) { search.run(session) }

    fun open(row: BuildingSession.SearchRow) {
        if (row.isRoom) {
            navigator.push(BuildingRoute.Viewer.of(ViewerFocus(roomID = row.id)))
        } else {
            selection = row.id
        }
    }

    Scaffold(
        topBar = { IvTopBar(session.name, onBack = navigator::back) },
        containerColor = Palette.background,
    ) { padding ->
        AccessGate(session, Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item { SearchField(search) }
                if (search.isActive) {
                    searchResults(session, search, ::open)
                } else {
                    content(session, navigator, onSelect = { selection = it })
                }
            }
        }
    }
    ObjectCardSheet(
        selection = selection,
        onSelectionChange = { selection = it },
        session = session,
        context = ObjectCardContext.LIST,
        onLocateInAR = navigator::showAR,
        onShowIn3D = { id -> navigator.push(BuildingRoute.Viewer.of(ViewerFocus(elementID = id))) },
    )
}

@Composable
private fun SearchField(search: SearchModel) {
    OutlinedTextField(
        value = search.query,
        onValueChange = { search.query = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.search_outlets_valves_panels), color = Palette.muted) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Palette.muted) },
        trailingIcon = {
            if (search.query.isNotEmpty()) {
                IconButton(onClick = { search.query = "" }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel), tint = Palette.muted)
                }
            }
        },
        singleLine = true,
        shape = RectangleShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Palette.surface,
            unfocusedContainerColor = Palette.surface,
            focusedBorderColor = Palette.ink,
            unfocusedBorderColor = Palette.line,
            cursorColor = Palette.accent,
            focusedTextColor = Palette.ink,
            unfocusedTextColor = Palette.ink,
        ),
    )
}

private fun LazyListScope.content(session: BuildingSession, navigator: BuildingNavigator, onSelect: (String) -> Unit) {
    val line = session.statusLine
    if (session.isMember && line != null) {
        item { ListRow { StatusLineView(line) } }
    }

    // One control per row.
    item {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryActionButton(stringResource(R.string.view_in_ar), onClick = navigator::showAR)
            SecondaryActionButton(stringResource(R.string.s_3d_model), onClick = { navigator.push(BuildingRoute.Viewer()) })
        }
    }

    if (session.phase == BuildingSession.Phase.Connecting || session.phase == BuildingSession.Phase.Loading) {
        item {
            ListRow {
                val percent = (session.progress.fraction * 100).roundToInt()
                LoadingBar(session.progress.fraction, stringResource(R.string.loading_the_model_npercent, percent), Modifier.weight(1f))
            }
        }
    }

    val systems = session.systems
    if (systems.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.systems)) }
        for (system in systems) {
            item {
                ListRow(
                    onClick = { navigator.push(BuildingRoute.Viewer.of(ViewerFocus(systems = listOf(system.key)))) },
                    chevron = true,
                ) {
                    ColorDot(session.systemColor(system.key), 12)
                    // iOS `Spacer()`: "Couldn't load" sits at the trailing edge.
                    Text(session.systemName(system.key), style = IvType.body(), color = Palette.ink, modifier = Modifier.weight(1f))
                    if (system.key in session.failedSystems) {
                        Text(stringResource(R.string.couldn_t_load), style = IvType.body(13.sp), color = Palette.warn)
                    }
                }
            }
        }
    }

    val equipment = session.equipment
    if (equipment.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.equipment)) }
        for (record in equipment) {
            item {
                ListRow(onClick = { onSelect(record.id) }) {
                    ElementRow(
                        title = session.displayName(record),
                        color = session.catalog?.color(record.system, record.subsystem) ?: CatalogColor.FALLBACK,
                        place = session.storeyName(record.storeyID),
                    )
                    // iOS: a trailing swipe action.
                    IconButton(onClick = {
                        session.locate(record.id)
                        navigator.showAR()
                    }) {
                        Icon(Icons.Outlined.ViewInAr, contentDescription = stringResource(R.string.locate_in_ar), tint = Palette.accent)
                    }
                }
            }
        }
    }

    val rooms = session.rooms()
    if (rooms.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.rooms)) }
        for (room in rooms.take(5)) {
            item {
                ListRow(
                    onClick = {
                        navigator.push(BuildingRoute.Viewer.of(ViewerFocus(storeyID = room.storeyID, roomID = room.id)))
                    },
                    chevron = true,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            room.space.displayName ?: stringResource(R.string.unnamed_room),
                            style = IvType.body(),
                            color = Palette.ink,
                        )
                        session.storeyName(room.storeyID)?.let { level ->
                            Text(level, style = IvType.body(13.sp), color = Palette.muted)
                        }
                    }
                }
            }
        }
        if (rooms.size > 5) {
            item {
                ListRow(onClick = { navigator.push(BuildingRoute.Rooms()) }, chevron = true) {
                    Text(stringResource(R.string.all_n_rooms, rooms.size), style = IvType.body(), color = Palette.ink)
                }
            }
        }
    }

    item { SectionHeader("") }
    item {
        ListRow(onClick = { navigator.push(BuildingRoute.Documents) }, chevron = true) {
            RowIcon(Icons.Outlined.Description)
            Text(stringResource(R.string.documents), style = IvType.body(), color = Palette.ink)
        }
    }
    item { Box(Modifier.padding(bottom = 24.dp)) }
}

/**
 * In-building search (IOS-M3-04): waits for a pause in typing, asks the server, and falls back to
 * the phone's copy of the model when offline.
 */
class SearchModel {
    var query by mutableStateOf("")
    var rows by mutableStateOf<List<BuildingSession.SearchRow>>(emptyList())
        private set
    var isOffline by mutableStateOf(false)
        private set
    var isSearching by mutableStateOf(false)
        private set

    /** The query the rows are for. */
    var searchedQuery by mutableStateOf("")
        private set

    val isActive: Boolean get() = query.trim(' ', '\t').isNotEmpty()

    /** Run in a `LaunchedEffect(query)`: a new keystroke cancels it (the debounce). */
    suspend fun run(session: BuildingSession) {
        val query = query.trim()
        if (query.isEmpty()) {
            rows = emptyList()
            searchedQuery = ""
            return
        }
        delay(300)
        isSearching = true
        try {
            val result = session.search(query)
            rows = result.rows
            isOffline = result.offline
            searchedQuery = query
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            isSearching = false
        }
    }
}

/** Search results: elements (tap for the card) and rooms (the 3D viewer on the room). */
private fun LazyListScope.searchResults(
    session: BuildingSession,
    search: SearchModel,
    open: (BuildingSession.SearchRow) -> Unit,
) {
    if (search.rows.isEmpty()) {
        item {
            if (search.isSearching || search.searchedQuery.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Palette.accent)
                }
            } else {
                ListRow {
                    Text(stringResource(R.string.no_matches_in_the_systems_you_can_see), style = IvType.body(), color = Palette.muted)
                }
            }
        }
    }
    for (row in search.rows) {
        item {
            ListRow(onClick = { open(row) }) {
                if (row.isRoom) {
                    RowIcon(Icons.Outlined.MeetingRoom)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(row.title, style = IvType.body(), color = Palette.ink)
                        row.place?.let { Text(it, style = IvType.body(13.sp), color = Palette.muted) }
                    }
                } else {
                    val system = row.system
                    ElementRow(
                        title = row.title,
                        color = system?.let { session.catalog?.color(it, row.subsystem) ?: session.systemColor(it) }
                            ?: CatalogColor.FALLBACK,
                        place = row.place,
                    )
                }
            }
        }
    }
    if (search.isOffline) {
        item {
            ListRow {
                Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = Palette.muted)
                SectionFooter(stringResource(R.string.offline_searching_the_copy_saved_on_this_phone))
            }
        }
    }
}

/** An element in a list: system colour, name, where it is. */
@Composable
fun RowScope.ElementRow(title: String, color: CatalogColor, place: String?) {
    ColorDot(color, 10)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = IvType.body(), color = Palette.ink)
        if (place != null) Text(place, style = IvType.body(13.sp), color = Palette.muted)
    }
}
