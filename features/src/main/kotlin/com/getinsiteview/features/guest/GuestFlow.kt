package com.getinsiteview.features.guest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.getinsiteview.api.BuildingAccess
import com.getinsiteview.api.GuestProblem
import com.getinsiteview.api.PublicBuilding
import com.getinsiteview.api.publicBuilding
import com.getinsiteview.core.DeepLink
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.features.R
import com.getinsiteview.features.app.AppDependencies
import com.getinsiteview.features.app.BuildingNavigator
import com.getinsiteview.features.building.BuildingSession
import com.getinsiteview.features.ui.IvTopBar
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * A building to open from the app: a link, optionally with an element whose card opens first
 * (a search result, IOS-M3-04).
 */
data class OpenBuilding(val link: DeepLink, val elementID: String? = null)

/**
 * One scanned code or link: the landing summary first, then the visit and the model in the
 * background (docs/PLAN.md §5 "QR scan → AR": landing in under 3 s). A ViewModel scoped to the
 * flow's first screen ([com.getinsiteview.features.app.GuestFlowRoute]), so the [session] is
 * shared by every screen pushed after it and closed with it.
 */
class GuestFlowModel(
    val link: DeepLink,
    /** A search result's element: its card opens on the landing (IOS-M3-04). */
    val initialElementID: String?,
    internal val dependencies: AppDependencies,
) : ViewModel() {
    sealed interface State {
        data object Loading : State

        data object Landing : State

        data class Problem(val problem: GuestProblem) : State
    }

    val session = BuildingSession(BuildingAccess.of(link), dependencies, viewModelScope)

    var state: State by mutableStateOf(State.Loading)
        private set

    /** From `GET /v1/public/buildings/{code}`; for a link, from the visit. */
    var summary: PublicBuilding? by mutableStateOf(null)
        private set

    /** Whether the landing already opened [initialElementID]'s card. */
    internal var openedInitialElement = false

    /**
     * `session_ended` when the app leaves the foreground, a new stretch when it comes back. (Here,
     * not in a screen: pushing BuildingHome or the viewer takes the landing off screen too.)
     */
    private val foreground = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = session.resumeSession()

        override fun onStop(owner: LifecycleOwner) = session.endSession()
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(foreground)
    }

    /**
     * The landing summary, then the visit and the model load. Signed in, a paused or not-live
     * building may still open with the user's own access (members and grant holders keep it
     * whatever the status), so only the visit decides (IOS-M3-03).
     */
    suspend fun load() {
        state = State.Loading
        val signedIn = dependencies.credentials.isSignedIn()
        when (val link = link) {
            is DeepLink.Building -> {
                try {
                    val summary = dependencies.api.publicBuilding(link.code)
                    this.summary = summary
                    val problem = GuestProblem.from(summary.status)
                    if (!signedIn && problem != null) {
                        state = State.Problem(problem)
                        return
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state = State.Problem(GuestProblem.from(e))
                    return
                }
            }
            // Links have no summary endpoint: the visit returns the building (IOS-M2-02).
            is DeepLink.AccessLink -> Unit
        }
        state = State.Landing
        session.start()
    }

    /** What the landing shows. */
    val building: PublicBuilding? get() = summary ?: session.visit?.building

    /** A problem from the visit (a revoked link, a paused building) replaces the landing. */
    val problem: GuestProblem?
        get() = GuestFlowRules.problem(state, session.accessProblem, session.phase, session.isArchitectureReady)

    suspend fun retry() {
        if (state is State.Problem) load() else session.start()
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(foreground)
        session.cancel()
    }

    companion object {
        fun factory(open: OpenBuilding, dependencies: AppDependencies): ViewModelProvider.Factory = viewModelFactory {
            initializer { GuestFlowModel(open.link, open.elementID, dependencies) }
        }
    }
}

/**
 * The guest flow for one link: landing, then BuildingHome, Rooms, 3D and AR pushed by
 * [navigator] (iOS `GuestFlowView`, a pushed screen in the app).
 */
@Composable
fun GuestFlowView(model: GuestFlowModel, navigator: BuildingNavigator) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) {
        if (model.state == GuestFlowModel.State.Loading) model.load()
    }
    val problem = model.problem
    when {
        problem != null -> Scaffold(
            topBar = { IvTopBar(model.building?.displayCode ?: "", onBack = navigator::back) },
            containerColor = Palette.background,
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                ProblemView(problem) { scope.launch { model.retry() } }
            }
        }
        model.state == GuestFlowModel.State.Loading -> Scaffold(
            topBar = { IvTopBar("", onBack = navigator::back) },
            containerColor = Palette.background,
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).background(Palette.background),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Palette.accent)
                Text(stringResource(R.string.opening), style = IvType.body(), color = Palette.muted)
            }
        }
        else -> GuestLandingView(model, navigator)
    }
}
