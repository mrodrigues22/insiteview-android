package com.getinsiteview.features.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.getinsiteview.core.AppConfiguration
import com.getinsiteview.core.DeepLink
import com.getinsiteview.core.Router
import com.getinsiteview.features.guest.AppUpdate
import com.getinsiteview.features.guest.OpenBuilding
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.launch

/**
 * Deep links for the app (IOS-M1-05): App Links, scanned QR codes and the Play Install Referrer
 * handoff all go through [Router] and land here. Created once by the app (an Application- or
 * Activity-scoped object; its observable values are Compose state).
 *
 * iOS keeps the Buildings tab's `NavigationPath` here; on Android the tab's NavController owns its
 * stack, so a building to open is published as [pendingOpen] and pushed by
 * [BuildingNavigationEffect] (which also reports whether a building is on the stack, for the
 * "same link twice" rule).
 *
 * @param dependencies the app's dependencies, or the configuration error that kept them from
 *   being built (iOS `Result`).
 */
class AppRootModel(val dependencies: Result<AppDependencies>) {
    /** The building or link on screen. */
    var currentLink by mutableStateOf<DeepLink?>(null)
        private set

    /** Set when a URL or QR code isn't ours: "This isn't an Insite View code". */
    var showsForeignCode by mutableStateOf(false)

    /**
     * Bumped whenever a building opens from a link or code, so the app can bring its Buildings
     * tab forward (the stack the building is pushed on lives there).
     */
    var openRequests by mutableIntStateOf(0)
        private set

    /**
     * The building the Buildings tab should show next, replacing whatever is open (iOS sets
     * `path = [link]`). [BuildingNavigationEffect] navigates and calls [consumePendingOpen].
     */
    var pendingOpen by mutableStateOf<OpenBuilding?>(null)
        private set

    /** Whether the Buildings tab's stack has a building on it (iOS `!path.isEmpty`); set by the tab. */
    var isBuildingOnStack: () -> Boolean = { false }

    /** The API turned this app version away (426 `client.outdated`): "Update required" over everything. */
    val updateRequired: Boolean
        get() = dependencies.getOrNull()?.clientStatus?.updateRequired ?: false

    /** What "Update" does: the Google Play page. */
    val appUpdate: AppUpdate
        get() = dependencies.getOrNull()?.appUpdate ?: AppUpdate()

    val router: Router
        get() = dependencies.getOrNull()?.router ?: Router()

    /**
     * The web left a building for the app through the Play Install Referrer (Android's App Clip
     * handoff, docs/PLAN.md §3 "Guest entry"): open it. The app calls this once at launch, after
     * [com.getinsiteview.core.BuildingHandoff.saveReferrer].
     */
    fun openHandoff() {
        val dependencies = dependencies.getOrNull() ?: return
        dependencies.applicationScope.launch {
            dependencies.handoff.take()?.let { open(it) }
        }
    }

    /** The app is leaving the foreground: send queued analytics (PLAN §3). */
    fun didEnterBackground() {
        val dependencies = dependencies.getOrNull() ?: return
        dependencies.applicationScope.launch { dependencies.analytics.flush() }
    }

    /** An App Link. Returns whether it was ours. */
    fun open(url: URI): Boolean {
        val link = router.deepLink(url)
        if (link == null) {
            showsForeignCode = true
            return false
        }
        open(link)
        return true
    }

    /** An App Link as a string (an Intent's `data`). Returns whether it was ours. */
    fun open(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            showsForeignCode = true
            return false
        }
        return open(uri)
    }

    /** A scanned QR payload. Returns whether it was ours; the scanner keeps going otherwise. */
    fun openScannedText(text: String): Boolean {
        val link = router.deepLinkForScannedText(text) ?: return false
        open(link)
        return true
    }

    /**
     * Opens a building. The same link twice (an App Link can arrive both in `onCreate` and
     * `onNewIntent`) is ignored while it's open.
     */
    fun open(link: DeepLink) {
        openRequests += 1
        if (link == currentLink && isBuildingOnStack()) return
        currentLink = link
        pendingOpen = OpenBuilding(link)
    }

    /** The Buildings tab pushed [pendingOpen]. */
    fun consumePendingOpen() {
        pendingOpen = null
    }

    companion object {
        /** Builds the app's dependencies, keeping a configuration error for the root to show. */
        fun live(context: Context, configuration: () -> AppConfiguration): AppRootModel =
            AppRootModel(runCatching { AppDependencies.live(context, configuration()) })
    }
}
