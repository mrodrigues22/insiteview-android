package com.getinsiteview.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.getinsiteview.api.GuestProblem
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.features.app.AppDependencies
import com.getinsiteview.features.app.AppRootModel
import com.getinsiteview.features.app.LocalAppDependencies
import com.getinsiteview.features.guest.LocalAppUpdate
import com.getinsiteview.features.guest.ProblemView
import kotlinx.coroutines.flow.drop
import com.getinsiteview.features.R as FeaturesR

/**
 * The app's content (iOS `InsiteViewApp.body`): the tabs, or the configuration error that kept the
 * dependencies from being built; "Update required" covers everything after a 426
 * `client.outdated` (master PLAN §9 "Schema and client compatibility").
 */
@Composable
fun AppRoot(root: AppRootModel) {
    CompositionLocalProvider(LocalAppUpdate provides root.appUpdate) {
        Box(Modifier.fillMaxSize()) {
            root.dependencies.fold(
                onSuccess = { dependencies ->
                    CompositionLocalProvider(LocalAppDependencies provides dependencies) {
                        RootTabView(root, dependencies)
                    }
                },
                onFailure = { error ->
                    Surface(Modifier.fillMaxSize(), color = Palette.background) {
                        Text(error.message ?: error.toString(), Modifier.safeDrawingPadding().padding(16.dp), style = IvType.body())
                    }
                },
            )
            if (root.updateRequired) UpdateRequiredView()
        }
    }
}

/**
 * Covers the whole app when the API no longer serves this version: "Update required" and the
 * Google Play button (ProblemView reads `LocalAppUpdate`). Back leaves the app; nothing under it
 * works until it's updated.
 */
@Composable
fun UpdateRequiredView() {
    val context = LocalContext.current
    BackHandler { context.findActivity()?.finish() }
    // A Surface takes every touch, so nothing underneath can be used.
    Surface(Modifier.fillMaxSize(), color = Palette.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            ProblemView(GuestProblem.UpdateRequired)
        }
    }
}

/** The app's tabs (A-02, IOS-M3-02). */
enum class AppTab { BUILDINGS, SEARCH, SCAN, PROFILE }

/**
 * The app's tabs: Buildings, Search, Scan, Profile. Links and scanned codes open in the Buildings
 * tab. The sign-in sheet shows here whenever a screen asks for it (Profile, Buildings, "Save this
 * building"); so does "This isn't an Insite View code" for a link that isn't ours.
 */
@Composable
fun RootTabView(model: AppRootModel, dependencies: AppDependencies) {
    var tab by rememberSaveable { mutableStateOf(AppTab.BUILDINGS) }
    // Each tab's stack outlives switching tabs (iOS keeps every tab's NavigationStack).
    val buildingsNav = rememberNavController()
    val searchNav = rememberNavController()
    val tabStates = rememberSaveableStateHolder()
    val prompt = dependencies.signInPrompt
    val signInReason by prompt.reason.collectAsStateWithLifecycle()

    // A building was opened from a link or code: show the Buildings tab, where it's pushed.
    LaunchedEffect(model) {
        snapshotFlow { model.openRequests }.drop(1).collect { tab = AppTab.BUILDINGS }
    }

    Scaffold(
        containerColor = Palette.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = Palette.surface) {
                for (item in AppTab.entries) {
                    val selected = tab == item
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (selected) {
                                // Tapping the current tab goes back to its first screen, as on iOS.
                                when (item) {
                                    AppTab.BUILDINGS -> buildingsNav.popBackStack(BuildingsListRoute, inclusive = false)
                                    AppTab.SEARCH -> searchNav.popBackStack(SearchResultsRoute, inclusive = false)
                                    else -> Unit
                                }
                            } else {
                                tab = item
                            }
                        },
                        icon = { Icon(tabIcon(item), contentDescription = null) },
                        label = { Text(tabTitle(item)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Palette.accent,
                            selectedTextColor = Palette.accent,
                            indicatorColor = Palette.accentSoft,
                            unselectedIconColor = Palette.muted,
                            unselectedTextColor = Palette.muted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
        ) {
            // Only the selected tab is composed; each keeps its saved UI state while hidden.
            tabStates.SaveableStateProvider(tab.name) {
                when (tab) {
                    AppTab.BUILDINGS -> BuildingsTab(model, dependencies, buildingsNav, onScanRequested = { tab = AppTab.SCAN })
                    AppTab.SEARCH -> SearchTab(dependencies, searchNav)
                    AppTab.SCAN -> ScanTab(model, isActive = true)
                    AppTab.PROFILE -> ProfileTab(dependencies)
                }
            }
        }
    }

    signInReason?.let { reason ->
        SignInView(
            dependencies = dependencies,
            reason = reason,
            onFinished = { prompt.finish(signedIn = true) },
            onDismiss = { prompt.finish(signedIn = dependencies.account.isSignedIn) },
        )
    }

    if (model.showsForeignCode) {
        AlertDialog(
            onDismissRequest = { model.showsForeignCode = false },
            title = { Text(stringResource(FeaturesR.string.this_isn_t_an_insite_view_code)) },
            confirmButton = {
                TextButton(onClick = { model.showsForeignCode = false }) {
                    Text(stringResource(FeaturesR.string.ok))
                }
            },
        )
    }
}

@Composable
private fun tabTitle(tab: AppTab): String = when (tab) {
    AppTab.BUILDINGS -> stringResource(FeaturesR.string.buildings)
    AppTab.SEARCH -> stringResource(FeaturesR.string.search)
    AppTab.SCAN -> stringResource(FeaturesR.string.scan)
    AppTab.PROFILE -> stringResource(FeaturesR.string.profile)
}

private fun tabIcon(tab: AppTab): ImageVector = when (tab) {
    AppTab.BUILDINGS -> Icons.Outlined.Apartment
    AppTab.SEARCH -> Icons.Outlined.Search
    AppTab.SCAN -> Icons.Outlined.QrCodeScanner
    AppTab.PROFILE -> Icons.Outlined.AccountCircle
}
