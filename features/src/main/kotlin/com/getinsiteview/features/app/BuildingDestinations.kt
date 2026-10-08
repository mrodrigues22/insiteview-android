package com.getinsiteview.features.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.getinsiteview.core.BuildingCode
import com.getinsiteview.core.DeepLink
import com.getinsiteview.core.ProfessionalNeed
import com.getinsiteview.features.building.BuildingHomeView
import com.getinsiteview.features.building.BuildingSession
import com.getinsiteview.features.documents.DocumentsView
import com.getinsiteview.features.guest.GuestFlowModel
import com.getinsiteview.features.guest.GuestFlowView
import com.getinsiteview.features.guest.OpenBuilding
import com.getinsiteview.features.rooms.RoomsView
import com.getinsiteview.features.viewer.Viewer3DView
import com.getinsiteview.features.viewer.ViewerFocus
import kotlinx.serialization.Serializable

/**
 * Opens a building: the guest flow's first screen, the landing (iOS pushes a `DeepLink` or an
 * `OpenBuilding`). Every other building screen is a [BuildingRoute] pushed after it and shares
 * its [GuestFlowModel] (and so its [BuildingSession]).
 *
 * @param plate the plate number, 0 for none (plates are positive).
 */
@Serializable
data class GuestFlowRoute(
    val code: String? = null,
    val plate: Int = 0,
    val token: String? = null,
    val elementID: String? = null,
) {
    val open: OpenBuilding
        get() {
            val link = when {
                code != null -> DeepLink.Building(
                    checkNotNull(BuildingCode.parse(code)) { "Invalid building code $code" },
                    plate = plate.takeIf { it > 0 },
                )
                else -> DeepLink.AccessLink(checkNotNull(token) { "A guest flow needs a code or a token." })
            }
            return OpenBuilding(link, elementID)
        }

    companion object {
        fun of(open: OpenBuilding): GuestFlowRoute = when (val link = open.link) {
            is DeepLink.Building -> GuestFlowRoute(code = link.code.raw, plate = link.plate ?: 0, elementID = open.elementID)
            is DeepLink.AccessLink -> GuestFlowRoute(token = link.token, elementID = open.elementID)
        }

        fun of(link: DeepLink, elementID: String? = null): GuestFlowRoute = of(OpenBuilding(link, elementID))
    }
}

/** Screens pushed inside one building's flow (iOS `BuildingRoute`). */
sealed interface BuildingRoute {
    /** BuildingHome (A-03 screen 1). */
    @Serializable
    data object Home : BuildingRoute

    /**
     * The 3D viewer, optionally focused on systems, a storey, a room or an element ([ViewerFocus];
     * fields flattened for navigation arguments: [need] is a [ProfessionalNeed.raw], [systems]
     * comma-separated system keys).
     */
    @Serializable
    data class Viewer(
        val need: String? = null,
        val systems: String? = null,
        val storeyID: String? = null,
        val roomID: String? = null,
        val elementID: String? = null,
    ) : BuildingRoute {
        val focus: ViewerFocus
            get() = ViewerFocus(
                need = need?.let { raw -> ProfessionalNeed.entries.firstOrNull { it.raw == raw } },
                systems = systems?.split(',')?.filter { it.isNotEmpty() },
                storeyID = storeyID,
                roomID = roomID,
                elementID = elementID,
            )

        companion object {
            fun of(focus: ViewerFocus): Viewer = Viewer(
                need = focus.need?.raw,
                systems = focus.systems?.joinToString(","),
                storeyID = focus.storeyID,
                roomID = focus.roomID,
                elementID = focus.elementID,
            )
        }
    }

    /** The professional flow's Rooms list, with a trade's filters preselected (IOS-M2-08). */
    @Serializable
    data class Rooms(val need: String? = null) : BuildingRoute {
        val professionalNeed: ProfessionalNeed?
            get() = need?.let { raw -> ProfessionalNeed.entries.firstOrNull { it.raw == raw } }

        companion object {
            fun of(need: ProfessionalNeed?): Rooms = Rooms(need?.raw)
        }
    }

    /** The building's documents (IOS-M3-06). */
    @Serializable
    data object Documents : BuildingRoute

    /**
     * AR over the camera (iOS presents `ARExperienceView` as a full-screen cover; here it's a
     * destination, drawn by the `arScreen` passed to [buildingDestinations]).
     */
    @Serializable
    data object AR : BuildingRoute
}

/** Moves between a building's screens (iOS `NavigationLink(value:)`, `dismiss`, the AR cover). */
class BuildingNavigator(private val navController: NavController) {
    fun push(route: BuildingRoute) {
        navController.navigate(route)
    }

    /** "See in AR" / "View in AR" / "Locate in AR". */
    fun showAR() {
        navController.navigate(BuildingRoute.AR)
    }

    fun back() {
        navController.popBackStack()
    }
}

/**
 * The navigation destinations for opening a building: [GuestFlowRoute] (a scanned code, an App
 * Link, a row in the Buildings tab, or a search result with the element whose card opens first,
 * IOS-M3-04) and every [BuildingRoute]. Added once to each NavHost a tab owns (iOS
 * `.buildingDestinations(dependencies:)`).
 *
 * @param arScreen AR for the session (ported in the AR screens; `:features`' AR package): called
 *   for [BuildingRoute.AR] with a `close` that pops it.
 */
fun NavGraphBuilder.buildingDestinations(
    navController: NavController,
    dependencies: AppDependencies,
    arScreen: @Composable (session: BuildingSession, close: () -> Unit) -> Unit,
) {
    composable<GuestFlowRoute> { entry ->
        val model = rememberGuestFlowModel(navController, entry, dependencies)
        GuestFlowView(model, remember(navController) { BuildingNavigator(navController) })
    }
    composable<BuildingRoute.Home> { entry ->
        val model = rememberGuestFlowModel(navController, entry, dependencies)
        BuildingHomeView(model.session, remember(navController) { BuildingNavigator(navController) })
    }
    composable<BuildingRoute.Viewer> { entry ->
        val model = rememberGuestFlowModel(navController, entry, dependencies)
        val focus = remember(entry) { entry.toRoute<BuildingRoute.Viewer>().focus }
        Viewer3DView(model.session, remember(navController) { BuildingNavigator(navController) }, focus)
    }
    composable<BuildingRoute.Rooms> { entry ->
        val model = rememberGuestFlowModel(navController, entry, dependencies)
        val need = remember(entry) { entry.toRoute<BuildingRoute.Rooms>().professionalNeed }
        RoomsView(model.session, remember(navController) { BuildingNavigator(navController) }, need)
    }
    composable<BuildingRoute.Documents> { entry ->
        val model = rememberGuestFlowModel(navController, entry, dependencies)
        DocumentsView(model.session, remember(navController) { BuildingNavigator(navController) })
    }
    composable<BuildingRoute.AR> { entry ->
        val model = rememberGuestFlowModel(navController, entry, dependencies)
        arScreen(model.session) { navController.popBackStack() }
    }
}

/**
 * The flow's model, from the nearest [GuestFlowRoute] entry at or below [entry] (its
 * ViewModelStore keeps it while any of the building's screens is on the stack). Looked up once per
 * entry, so a screen leaving with its flow (pop to root) doesn't look again mid-animation.
 */
@Composable
private fun rememberGuestFlowModel(
    navController: NavController,
    entry: NavBackStackEntry,
    dependencies: AppDependencies,
): GuestFlowModel = remember(entry) {
    val flowEntry = if (entry.destination.hasRoute<GuestFlowRoute>()) entry else navController.getBackStackEntry<GuestFlowRoute>()
    val open = flowEntry.toRoute<GuestFlowRoute>().open
    ViewModelProvider(flowEntry, GuestFlowModel.factory(open, dependencies))[GuestFlowModel::class.java]
}

/**
 * Opens a building on this stack, replacing whatever building is open (iOS `path = [link]`): back
 * to the graph's start, then the guest flow.
 */
fun NavController.openBuilding(open: OpenBuilding) {
    navigate(GuestFlowRoute.of(open)) {
        popUpTo(graph.startDestinationId)
        launchSingleTop = false
    }
}

/**
 * Pushes [AppRootModel.pendingOpen] on the Buildings tab's stack, and tells the model whether a
 * building is on it (for "the same link twice is ignored while it's open"). Put it inside the tab
 * that owns [navController].
 */
@Composable
fun BuildingNavigationEffect(model: AppRootModel, navController: NavHostController) {
    SideEffect {
        model.isBuildingOnStack = {
            try {
                navController.getBackStackEntry<GuestFlowRoute>()
                true
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    }
    val pending = model.pendingOpen
    LaunchedEffect(pending) {
        if (pending != null) {
            navController.openBuilding(pending)
            model.consumePendingOpen()
        }
    }
}
