@file:OptIn(ExperimentalMaterial3Api::class)

package com.getinsiteview.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.getinsiteview.api.MySearchBuilding
import com.getinsiteview.api.SearchHit
import com.getinsiteview.api.SearchHitType
import com.getinsiteview.api.buildings.AccountSearchResults
import com.getinsiteview.api.searchMyBuildings
import com.getinsiteview.core.BuildingCode
import com.getinsiteview.core.DeepLink
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.design.PrimaryActionButton
import com.getinsiteview.features.account.SignInReason
import com.getinsiteview.features.app.AppDependencies
import com.getinsiteview.features.ui.IvTopBar
import com.getinsiteview.features.ui.ListRow
import com.getinsiteview.features.ui.RowIcon
import com.getinsiteview.features.ui.SectionHeader
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.getinsiteview.features.R as FeaturesR

/**
 * The Search tab (IOS-M3-04, A-02): one search box across all my buildings (`GET /v1/me/search`).
 * Buildings that match by name or address come first, then elements grouped by building and room,
 * in the order the API ranked them. A building opens on its landing; an element opens the building
 * with its card showing (`OpenBuilding`). Signed out, it invites the person to sign in.
 *
 * @param navController this tab's stack, hoisted by [RootTabView].
 */
@Composable
fun SearchTab(dependencies: AppDependencies, navController: NavHostController) {
    NavHost(navController, startDestination = SearchResultsRoute) {
        composable<SearchResultsRoute> { SearchScreen(dependencies, navController) }
        appBuildingDestinations(navController, dependencies)
    }
}

@Composable
private fun SearchScreen(dependencies: AppDependencies, navController: NavHostController) {
    val search: SearchTabModel = viewModel()
    val auth by dependencies.account.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    // Each keystroke restarts the search (the debounce is in run()).
    LaunchedEffect(search.query) {
        search.bind(dependencies)
        search.run()
    }

    Scaffold(
        topBar = { IvTopBar(title = stringResource(FeaturesR.string.search), onBack = null) },
        containerColor = Palette.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = search.query,
                onValueChange = { search.query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text(stringResource(FeaturesR.string.search_your_buildings_rooms_and_equipment), maxLines = 1) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (search.query.isNotEmpty()) {
                        IconButton(onClick = { search.query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.app_clear_search))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.accent,
                    unfocusedBorderColor = Palette.line,
                    focusedContainerColor = Palette.surface,
                    unfocusedContainerColor = Palette.surface,
                    cursorColor = Palette.accent,
                ),
            )
            Box(Modifier.fillMaxSize()) {
                if (auth.isSignedIn) {
                    SearchResultsContent(search, navController)
                } else {
                    SearchSignedOut(dependencies)
                }
            }
        }
    }
}

@Composable
private fun SearchResultsContent(search: SearchTabModel, navController: NavHostController) {
    val results = search.results
    when {
        !search.isActive -> ContentUnavailable(
            title = stringResource(FeaturesR.string.search_across_your_buildings),
            description = stringResource(FeaturesR.string.find_a_building_by_name_or_an_outlet_valve_or_panel_across_e),
            icon = { UnavailableIcon(Icons.Outlined.Search) },
        )
        search.isSearching && results == null ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Palette.accent) }
        results != null && results.isEmpty -> ContentUnavailable(
            // iOS `ContentUnavailableView.search(text:)`.
            title = stringResource(R.string.app_no_results_for_x, search.query),
            description = stringResource(R.string.app_check_the_spelling_or_try_a_new_search),
            icon = { UnavailableIcon(Icons.Outlined.Search) },
        )
        results != null -> LazyColumn(Modifier.fillMaxSize()) {
            if (results.buildings.isNotEmpty()) {
                item(key = "buildings-header") { SectionHeader(stringResource(FeaturesR.string.buildings)) }
                items(results.buildings, key = { "b-" + it.id }) { building ->
                    SearchBuildingRow(building) { code -> navController.pushBuilding(DeepLink.Building(code, plate = null)) }
                }
            }
            for (group in results.groups) {
                item(key = "g-" + group.id) { SectionHeader(group.name) }
                for (room in group.rooms) {
                    // No keys: the API could list one element twice.
                    items(room.hits) { hit ->
                        HitRow(hit, room.room, group.code) { code ->
                            navController.pushBuilding(DeepLink.Building(code, plate = null), elementId = hit.id)
                        }
                    }
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun SearchBuildingRow(building: MySearchBuilding, onOpen: (BuildingCode) -> Unit) {
    val subtitle = listOf(building.addressLine, building.city).firstOrNull { !it.isNullOrEmpty() }
    val code = building.buildingCode
    ListRow(onClick = code?.let { { onOpen(it) } }, chevron = code != null) {
        RowIcon(Icons.Outlined.Apartment)
        TwoLineLabel(building.name, subtitle, muted = code == null)
    }
}

/** One element hit: what it is and where. */
@Composable
private fun HitRow(hit: SearchHit, room: String?, code: BuildingCode?, onOpen: (BuildingCode) -> Unit) {
    val place = listOfNotNull(room, hit.storey).joinToString(" · ").ifEmpty { null }
    ListRow(onClick = code?.let { { onOpen(it) } }, chevron = code != null) {
        RowIcon(if (hit.type == SearchHitType.ROOM) Icons.Outlined.MeetingRoom else Icons.Outlined.ViewInAr)
        TwoLineLabel(hit.title, place, muted = code == null)
    }
}

@Composable
private fun TwoLineLabel(title: String, subtitle: String?, muted: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = IvType.body(), color = if (muted) Palette.muted else Palette.ink)
        if (subtitle != null) Text(subtitle, style = IvType.body(13.sp), color = Palette.muted)
    }
}

@Composable
private fun SearchSignedOut(dependencies: AppDependencies) {
    val scope = rememberCoroutineScope()
    ContentUnavailable(
        title = stringResource(FeaturesR.string.search_your_buildings),
        description = stringResource(FeaturesR.string.sign_in_to_search_across_every_building_you_can_open),
        icon = { UnavailableIcon(Icons.Outlined.Search) },
        actions = {
            PrimaryActionButton(stringResource(FeaturesR.string.sign_in), onClick = {
                scope.launch { dependencies.signInPrompt.requestSignIn(SignInReason.Account) }
            })
        },
    )
}

/**
 * The Search tab's data (IOS-M3-04): debounced calls to `GET /v1/me/search`, grouped by
 * [AccountSearchResults] (tested on the JVM). Building codes come from my buildings, so an element
 * hit knows which building to open.
 */
class SearchTabModel : ViewModel() {
    var query by mutableStateOf("")
    var results by mutableStateOf<AccountSearchResults?>(null)
        private set
    var isSearching by mutableStateOf(false)
        private set
    private var dependencies: AppDependencies? = null

    val isActive: Boolean get() = query.trim(' ', '\t').isNotEmpty()

    fun bind(dependencies: AppDependencies) {
        if (this.dependencies == null) this.dependencies = dependencies
    }

    /** Runs the search for [query] after [DEBOUNCE_MS]; a new keystroke cancels it (the caller's effect restarts). */
    suspend fun run() {
        val dependencies = dependencies ?: return
        val query = query.trim()
        if (query.isEmpty()) {
            results = null
            return
        }
        // Debounce: a new keystroke cancels this coroutine.
        delay(DEBOUNCE_MS)
        isSearching = true
        try {
            // Codes let an element hit open the right building; a stale list still opens the ones we know.
            val codes = buildingCodes(dependencies)
            val raw = try {
                dependencies.api.searchMyBuildings(query)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
            results = AccountSearchResults.of(raw, codes)
        } finally {
            isSearching = false
        }
    }

    private suspend fun buildingCodes(dependencies: AppDependencies): Map<String, BuildingCode> {
        val list = try {
            dependencies.myBuildings.list()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyMap()
        }
        val codes = LinkedHashMap<String, BuildingCode>()
        for (building in list) {
            val code = building.buildingCode ?: continue
            codes.putIfAbsent(building.id.lowercase(), code)
        }
        return codes
    }

    companion object {
        const val DEBOUNCE_MS = 300L
    }
}
