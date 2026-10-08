@file:OptIn(ExperimentalMaterial3Api::class)

package com.getinsiteview.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.getinsiteview.api.MyBuilding
import com.getinsiteview.api.MyBuildingVia
import com.getinsiteview.api.buildings.BuildingListSegment
import com.getinsiteview.core.BuildingCode
import com.getinsiteview.core.BuildingStatusLine
import com.getinsiteview.core.DeepLink
import com.getinsiteview.core.RecentBuildings
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.design.PrimaryActionButton
import com.getinsiteview.design.Wordmark
import com.getinsiteview.features.account.SignInReason
import com.getinsiteview.features.app.AppDependencies
import com.getinsiteview.features.app.AppRootModel
import com.getinsiteview.features.app.BuildingNavigationEffect
import com.getinsiteview.features.building.StatusLineView
import com.getinsiteview.features.ui.IvTopBar
import com.getinsiteview.features.ui.ListRow
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import com.getinsiteview.features.R as FeaturesR

/**
 * The Buildings tab (IOS-M3-02, A-02): the signed-in user's buildings, in the segments All ·
 * Active · Recent · Favorites. Deep links and scanned codes push onto this tab's stack
 * ([BuildingNavigationEffect] pushes [AppRootModel.pendingOpen]). Signed out, it invites the person
 * to sign in; "+ Add building" opens the web upload flow (no building creation in the app). Members
 * see a building's status line (IOS-M3-03); grant and saved buildings don't.
 *
 * @param navController this tab's stack, hoisted by [RootTabView] so it survives tab switches.
 * @param onScanRequested sends the person to the Scan tab (nothing to show yet).
 */
@Composable
fun BuildingsTab(
    model: AppRootModel,
    dependencies: AppDependencies,
    navController: NavHostController,
    onScanRequested: () -> Unit,
) {
    NavHost(navController, startDestination = BuildingsListRoute) {
        composable<BuildingsListRoute> {
            BuildingsList(model, dependencies, navController, onScanRequested)
        }
        appBuildingDestinations(navController, dependencies)
    }
    BuildingNavigationEffect(model, navController)
}

@Composable
private fun BuildingsList(
    model: AppRootModel,
    dependencies: AppDependencies,
    navController: NavHostController,
    onScanRequested: () -> Unit,
) {
    val buildings: BuildingsTabModel = viewModel()
    val auth by dependencies.account.state.collectAsStateWithLifecycle()
    val isSignedIn = auth.isSignedIn
    val context = LocalContext.current

    LaunchedEffect(isSignedIn) {
        buildings.bind(dependencies)
        buildings.refreshLocal()
        if (!isSignedIn) return@LaunchedEffect
        buildings.load()
        buildings.refreshLocal()
    }
    // A building was just opened from a link or code: keep Recent in step.
    LaunchedEffect(model.openRequests) {
        buildings.bind(dependencies)
        buildings.refreshLocal()
    }

    Scaffold(
        topBar = {
            IvTopBar(title = stringResource(FeaturesR.string.buildings), onBack = null) {
                if (isSignedIn) {
                    IconButton(onClick = { WebPages.open(context, dependencies.webLinks.addBuilding) }) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(FeaturesR.string.add_building))
                    }
                }
            }
        },
        containerColor = Palette.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (isSignedIn) {
                SignedInContent(buildings, dependencies, navController, onScanRequested)
            } else {
                BuildingsSignedOut(dependencies)
            }
        }
    }
}

@Composable
private fun SignedInContent(
    buildings: BuildingsTabModel,
    dependencies: AppDependencies,
    navController: NavHostController,
    onScanRequested: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    when {
        buildings.state == BuildingsTabModel.LoadState.LOADING && buildings.all.isEmpty() ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.accent) }
        buildings.state == BuildingsTabModel.LoadState.FAILED && buildings.all.isEmpty() ->
            ContentUnavailable(
                title = stringResource(FeaturesR.string.couldn_t_load_your_buildings),
                description = stringResource(FeaturesR.string.check_your_connection_and_try_again),
                icon = { UnavailableIcon(Icons.Outlined.WifiOff) },
                actions = {
                    PrimaryActionButton(stringResource(FeaturesR.string.try_again), onClick = {
                        scope.launch { buildings.load(refresh = true) }
                    })
                },
            )
        else -> BuildingsListContent(buildings, dependencies, navController, onScanRequested)
    }
}

@Composable
private fun BuildingsListContent(
    buildings: BuildingsTabModel,
    dependencies: AppDependencies,
    navController: NavHostController,
    onScanRequested: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val rows = buildings.rows

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                try {
                    buildings.load(refresh = true)
                    buildings.refreshLocal()
                } finally {
                    refreshing = false
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "segments") {
                SegmentPicker(
                    selected = buildings.segment,
                    onSelect = { buildings.segment = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (rows.isEmpty()) {
                item(key = "empty") { EmptySegment(buildings.segment, onScanRequested) }
            } else {
                items(rows, key = { it.id }) { building ->
                    BuildingRow(
                        building = building,
                        statusLine = buildings.statusLine(building),
                        isFavorite = building.buildingCode?.let { it.raw in buildings.favorites } ?: false,
                        onOpen = { code -> navController.pushBuilding(DeepLink.Building(code, plate = null)) },
                        onToggleFavorite = { code -> scope.launch { buildings.toggleFavorite(code) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentPicker(selected: BuildingListSegment, onSelect: (BuildingListSegment) -> Unit, modifier: Modifier = Modifier) {
    val segments = BuildingListSegment.entries
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        segments.forEachIndexed { index, segment ->
            SegmentedButton(
                selected = segment == selected,
                onClick = { onSelect(segment) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = segments.size),
                icon = {},
                label = { Text(segmentLabel(segment), maxLines = 1) },
            )
        }
    }
}

@Composable
private fun segmentLabel(segment: BuildingListSegment): String = when (segment) {
    BuildingListSegment.ALL -> stringResource(FeaturesR.string.all)
    BuildingListSegment.ACTIVE -> stringResource(FeaturesR.string.active)
    BuildingListSegment.RECENT -> stringResource(FeaturesR.string.recent)
    BuildingListSegment.FAVORITES -> stringResource(FeaturesR.string.favorites)
}

/**
 * One building's row. Swiping it right stars or unstars it (iOS: a leading swipe action with a
 * star); a building without a code can't be starred, and a draft can't be opened (muted).
 */
@Composable
private fun BuildingRow(
    building: MyBuilding,
    statusLine: BuildingStatusLine?,
    isFavorite: Boolean,
    onOpen: (BuildingCode) -> Unit,
    onToggleFavorite: (BuildingCode) -> Unit,
) {
    val code = building.buildingCode
    val content: @Composable () -> Unit = {
        ListRow(
            onClick = if (code != null && building.canOpen) ({ onOpen(code) }) else null,
            chevron = code != null && building.canOpen,
        ) {
            BuildingRowContent(building, statusLine, isFavorite, muted = code == null || !building.canOpen)
        }
    }
    if (code == null) {
        content()
        return
    }
    val swipe = rememberSwipeToDismissBoxState()
    // A full swipe is the star button: toggle, then the row slides back.
    LaunchedEffect(swipe.currentValue) {
        if (swipe.currentValue == SwipeToDismissBoxValue.StartToEnd) {
            onToggleFavorite(code)
            swipe.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(Palette.accent).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (isFavorite) Icons.Outlined.StarBorder else Icons.Filled.Star, contentDescription = null, tint = Palette.onAccent)
                Text(
                    stringResource(if (isFavorite) FeaturesR.string.unfavorite else FeaturesR.string.favorite),
                    style = IvType.body(15.sp),
                    color = Palette.onAccent,
                )
            }
        },
    ) {
        content()
    }
}

/** One building's row: name, where it is, and — for members — its billing status line. */
@Composable
private fun BuildingRowContent(building: MyBuilding, statusLine: BuildingStatusLine?, isFavorite: Boolean, muted: Boolean) {
    val favoriteLabel = stringResource(FeaturesR.string.favorite)
    Column(Modifier.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(building.name, style = IvType.headline, color = if (muted) Palette.muted else Palette.ink)
            if (isFavorite) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    tint = Palette.accent,
                    modifier = Modifier.size(12.dp).semantics { contentDescription = favoriteLabel },
                )
            }
        }
        subtitle(building)?.let {
            Text(it, style = IvType.body(15.sp), color = Palette.muted)
        }
        if (statusLine != null) StatusLineView(line = statusLine)
    }
}

/** Where the building is, or how it's shared when there's no city. */
private fun subtitle(building: MyBuilding): String? {
    val city = building.city
    if (!city.isNullOrEmpty()) return city
    return when (building.via) {
        MyBuildingVia.GRANT -> building.organizationName
        MyBuildingVia.SAVED -> null
        else -> building.organizationName
    }
}

@Composable
private fun EmptySegment(segment: BuildingListSegment, onScanRequested: () -> Unit) {
    when (segment) {
        BuildingListSegment.ALL -> ContentUnavailable(
            title = stringResource(FeaturesR.string.no_buildings_yet),
            description = stringResource(FeaturesR.string.scan_a_building_s_code_or_add_one_on_the_web),
            icon = { UnavailableIcon(Icons.Outlined.Apartment) },
            fillsScreen = false,
            actions = {
                PrimaryActionButton(stringResource(FeaturesR.string.scan_a_code), onClick = onScanRequested)
            },
        )
        BuildingListSegment.ACTIVE -> EmptySegmentText(stringResource(FeaturesR.string.none_of_your_buildings_are_live_right_now))
        BuildingListSegment.RECENT -> EmptySegmentText(stringResource(FeaturesR.string.buildings_you_open_show_up_here))
        BuildingListSegment.FAVORITES -> EmptySegmentText(stringResource(FeaturesR.string.swipe_a_building_and_tap_the_star_to_keep_it_here))
    }
}

@Composable
private fun EmptySegmentText(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        style = IvType.body(),
        color = Palette.muted,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun BuildingsSignedOut(dependencies: AppDependencies) {
    val scope = rememberCoroutineScope()
    ContentUnavailable(
        title = stringResource(FeaturesR.string.your_buildings_in_one_place),
        description = stringResource(FeaturesR.string.sign_in_to_see_your_company_s_buildings_the_ones_shared_with),
        icon = { Wordmark(Modifier.padding(bottom = 8.dp), size = 30.sp) },
        actions = {
            PrimaryActionButton(stringResource(FeaturesR.string.sign_in), onClick = {
                scope.launch { dependencies.signInPrompt.requestSignIn(SignInReason.Account) }
            })
        },
    )
}

/**
 * The Buildings tab's data: my buildings and each organization's trial end, from the shared
 * `MyBuildingsRepository`, plus this device's favourites and recents. The segment logic and status
 * lines are `:api`'s and `:core`'s, tested on the JVM.
 */
class BuildingsTabModel : ViewModel() {
    enum class LoadState { LOADING, LOADED, FAILED }

    var state by mutableStateOf(LoadState.LOADING)
        private set
    var all by mutableStateOf<List<MyBuilding>>(emptyList())
        private set
    var segment by mutableStateOf(BuildingListSegment.ALL)
    var favorites by mutableStateOf<Set<String>>(emptySet())
        private set
    var recents by mutableStateOf<List<RecentBuildings.Entry>>(emptyList())
        private set
    private var trialEnds by mutableStateOf<Map<String, Instant>>(emptyMap())
    private var dependencies: AppDependencies? = null

    fun bind(dependencies: AppDependencies) {
        if (this.dependencies == null) this.dependencies = dependencies
    }

    suspend fun load(refresh: Boolean = false) {
        val dependencies = dependencies ?: return
        if (all.isEmpty()) state = LoadState.LOADING
        try {
            all = dependencies.myBuildings.list(refresh = refresh)
            trialEnds = dependencies.myBuildings.trialEnds(refresh = refresh)
            state = LoadState.LOADED
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            state = LoadState.FAILED
        }
    }

    /** Favourites and recents from this device's store. */
    suspend fun refreshLocal() {
        val dependencies = dependencies ?: return
        favorites = dependencies.favorites.codes()
        recents = dependencies.recents.entries()
    }

    suspend fun toggleFavorite(code: BuildingCode) {
        val dependencies = dependencies ?: return
        dependencies.favorites.toggle(code)
        favorites = dependencies.favorites.codes()
    }

    val rows: List<MyBuilding> get() = segment.rows(all, favorites, recents)

    /**
     * The member status line for the list (A-02); grant and saved buildings show none. The list
     * has no activation year, so an active building reads "Active" (the year is on BuildingHome,
     * which fetches the building).
     */
    fun statusLine(building: MyBuilding): BuildingStatusLine? {
        if (!building.showsStatus) return null
        return BuildingStatusLine.from(
            status = building.status,
            trialEndsAt = trialEnds[building.organizationId.lowercase()],
            expiresAt = null,
            now = Instant.now(),
        )
    }
}
