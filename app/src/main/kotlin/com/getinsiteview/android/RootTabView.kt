package com.getinsiteview.android

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.getinsiteview.design.GlassTab
import com.getinsiteview.design.IvGlassTabBar
import com.getinsiteview.design.LocalBottomBarInset
import com.getinsiteview.design.LocalGlassState
import com.getinsiteview.design.LocalTabReselects
import com.getinsiteview.design.glassSource
import com.getinsiteview.features.account.SignInReason
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.getinsiteview.api.GuestProblem
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.features.app.AppDependencies
import com.getinsiteview.features.app.AppRootModel
import com.getinsiteview.features.app.LocalAppDependencies
import com.getinsiteview.features.app.isARCover
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
            // Fades in over everything (iOS presents it with a fade).
            AnimatedVisibility(visible = root.updateRequired, enter = fadeIn(), exit = fadeOut()) { UpdateRequiredView() }
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
    val scope = rememberCoroutineScope()
    val signInReason by prompt.reason.collectAsStateWithLifecycle()

    // AR covers the tabs, as iOS's full-screen cover does (and switching tabs can't tear it down).
    val buildingsEntry by buildingsNav.currentBackStackEntryAsState()
    val searchEntry by searchNav.currentBackStackEntryAsState()
    val arCovers = when (tab) {
        AppTab.BUILDINGS -> isARCover(buildingsEntry)
        AppTab.SEARCH -> isARCover(searchEntry)
        else -> false
    }

    // Back at a tab's first screen leaves the app with its state kept, like going home on iOS.
    // Android 12+ does that itself (predictive back to home); older versions would finish the
    // activity and lose every tab's stack.
    val atTabRoot = when (tab) {
        AppTab.BUILDINGS -> buildingsEntry?.let { buildingsNav.previousBackStackEntry == null } ?: true
        AppTab.SEARCH -> searchEntry?.let { searchNav.previousBackStackEntry == null } ?: true
        else -> true
    }
    val activity = LocalContext.current.findActivity()
    BackHandler(enabled = atTabRoot && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        activity?.moveTaskToBack(true)
    }

    // A building was opened from a link or code: show the Buildings tab, where it's pushed.
    LaunchedEffect(model) {
        snapshotFlow { model.openRequests }.drop(1).collect { tab = AppTab.BUILDINGS }
    }

    val glass = rememberHazeState()
    var reselects by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    var barHeight by remember { mutableStateOf(0.dp) }
    val tabs = AppTab.entries.map { GlassTab(tabTitle(it), tabIcon(it)) }

    Box(Modifier.fillMaxSize().background(Palette.background)) {
        // The content runs under the floating tab bar (iOS 26), which blurs it.
        Box(Modifier.fillMaxSize().glassSource(glass)) {
            CompositionLocalProvider(
                LocalGlassState provides glass,
                LocalBottomBarInset provides if (arCovers) 0.dp else barHeight,
                LocalTabReselects provides reselects,
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
        if (!arCovers) {
            CompositionLocalProvider(LocalGlassState provides glass) {
                IvGlassTabBar(
                    tabs = tabs,
                    selected = tab.ordinal,
                    onSelect = { index ->
                        val item = AppTab.entries[index]
                        if (item == tab) {
                            // Tapping the current tab goes back to its first screen, then to the top, as on iOS.
                            val popped = when (item) {
                                AppTab.BUILDINGS -> buildingsNav.popBackStack(BuildingsListRoute, inclusive = false)
                                AppTab.SEARCH -> searchNav.popBackStack(SearchResultsRoute, inclusive = false)
                                else -> false
                            }
                            if (!popped) reselects++
                        } else {
                            tab = item
                        }
                    },
                    // Measured with the navigation bar's inset under it: the content clears both.
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { barHeight = with(density) { it.height.toDp() } }
                        .navigationBarsPadding(),
                )
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

    // The session ended on its own (S-01): once, wherever the person is.
    val sessionEnded by dependencies.account.showsSessionEnded.collectAsStateWithLifecycle()
    if (sessionEnded) {
        AlertDialog(
            onDismissRequest = { dependencies.account.dismissSessionEnded() },
            title = { Text(stringResource(FeaturesR.string.your_session_ended_sign_in_again)) },
            confirmButton = {
                TextButton(onClick = {
                    dependencies.account.dismissSessionEnded()
                    scope.launch { prompt.requestSignIn(SignInReason.Account) }
                }) { Text(stringResource(FeaturesR.string.sign_in)) }
            },
            dismissButton = {
                TextButton(onClick = { dependencies.account.dismissSessionEnded() }) { Text(stringResource(FeaturesR.string.ok)) }
            },
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
