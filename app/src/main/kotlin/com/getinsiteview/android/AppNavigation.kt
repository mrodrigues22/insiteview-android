package com.getinsiteview.android

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import com.getinsiteview.core.DeepLink
import com.getinsiteview.features.app.AppDependencies
import com.getinsiteview.features.app.GuestFlowRoute
import com.getinsiteview.features.app.buildingDestinations
import com.getinsiteview.features.ar.ARExperienceView
import com.getinsiteview.features.building.BuildingSession
import kotlinx.serialization.Serializable

// The tabs' own routes, and the one place the app pushes a building onto a tab's stack. The
// building screens themselves (the guest flow and what it pushes) are :features'
// `buildingDestinations`.

/** The Buildings tab's list (start destination of its stack). */
@Serializable
data object BuildingsListRoute

/** The Search tab's results (start destination of its stack). */
@Serializable
data object SearchResultsRoute

/**
 * Pushes a building (iOS `NavigationLink(value: DeepLink)` / `NavigationLink(value: OpenBuilding)`):
 * its landing, with [elementId]'s card showing first when set (a search hit, IOS-M3-04).
 */
fun NavController.pushBuilding(link: DeepLink, elementId: String? = null) {
    navigate(GuestFlowRoute.of(link, elementId))
}

/** The building screens on a tab's stack, with AR (iOS presents it as a full-screen cover). */
fun NavGraphBuilder.appBuildingDestinations(navController: NavController, dependencies: AppDependencies) {
    buildingDestinations(navController, dependencies, arScreen = { session, close -> ARScreen(session, close) })
}

@Composable
private fun ARScreen(session: BuildingSession, close: () -> Unit) {
    ARExperienceView(session = session, onClose = close)
}
