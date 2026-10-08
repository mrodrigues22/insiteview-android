package com.getinsiteview.features.building

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.getinsiteview.api.BuildingAccess
import com.getinsiteview.api.BuildingConnection
import com.getinsiteview.api.BuildingPlate
import com.getinsiteview.api.ElementDetail
import com.getinsiteview.api.GuestProblem
import com.getinsiteview.api.MyBuilding
import com.getinsiteview.api.MyBuildingVia
import com.getinsiteview.api.OrganizationRole
import com.getinsiteview.api.PublicBuildingStatus
import com.getinsiteview.api.Visit
import com.getinsiteview.api.VisitVia
import com.getinsiteview.api.building
import com.getinsiteview.api.buildingDocuments
import com.getinsiteview.api.buildings.BuildingSearch
import com.getinsiteview.api.buildings.BuildingSearchOutcome
import com.getinsiteview.api.buildings.DocumentItem
import com.getinsiteview.api.placePlate
import com.getinsiteview.api.plates
import com.getinsiteview.api.saveRoomCorrection
import com.getinsiteview.core.AnalyticsEvent
import com.getinsiteview.core.BuildingCode
import com.getinsiteview.core.BuildingStatus
import com.getinsiteview.core.BuildingStatusLine
import com.getinsiteview.core.Catalog
import com.getinsiteview.core.CatalogColor
import com.getinsiteview.core.CatalogLanguage
import com.getinsiteview.core.GuestPreferences
import com.getinsiteview.core.ProfessionalNeed
import com.getinsiteview.core.UnitFormatter
import com.getinsiteview.features.app.AppDependencies
import com.getinsiteview.modelkit.ChunkEvent
import com.getinsiteview.modelkit.ChunkLoadError
import com.getinsiteview.modelkit.ChunkPlan
import com.getinsiteview.modelkit.ElementIndex
import com.getinsiteview.modelkit.ElementRecord
import com.getinsiteview.modelkit.LoadProgress
import com.getinsiteview.modelkit.Manifest
import com.getinsiteview.modelkit.ModelFilters
import com.getinsiteview.modelkit.ModelLoadState
import com.getinsiteview.modelkit.RoomEntry
import com.getinsiteview.modelkit.SeeInside
import com.getinsiteview.modelkit.geometry.Bounds
import com.getinsiteview.modelkit.geometry.PlateFrame
import com.getinsiteview.modelkit.geometry.RoomCorrection
import com.getinsiteview.modelkit.geometry.RoomCorrections
import com.getinsiteview.modelkit.geometry.RoomObservation
import com.getinsiteview.scene.BuildingScene
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One open building, shared by the landing, BuildingHome, the 3D viewer and AR: the visit, the
 * manifest, the element index, the Filament scene and the filters (docs/PLAN.md §3 "Loading a
 * building"). The scene's root moves between the 3D viewer and AR, so switching modes reloads
 * nothing. Everything shown comes from the scoped manifest (IOS-M2-03): hidden systems never
 * appear in chips, search, equipment or rooms, and a chunk that fails is left out.
 *
 * iOS's `@Observable` main-actor model: every observable property is Compose snapshot state, so a
 * composable reading it (directly or through a derived property such as [systems]) recomposes when
 * it changes; outside Compose use `snapshotFlow { session.phase }`. Use it from the main thread.
 * It lives as long as the building's flow ([com.getinsiteview.features.guest.GuestFlowModel],
 * a ViewModel), whose [scope] runs the loading.
 *
 * @param scope the flow's `viewModelScope` (main thread): loading and member lookups run there and
 *   stop when the building is closed.
 */
class BuildingSession(
    val access: BuildingAccess,
    private val dependencies: AppDependencies,
    private val scope: CoroutineScope,
) {
    sealed interface Phase {
        /** Starting the visit and fetching the manifest. */
        data object Connecting : Phase

        /** Downloading chunks; the scene fills in as they arrive. */
        data object Loading : Phase

        /** Every file loaded or failed. */
        data object Ready : Phase

        data class Failed(val problem: GuestProblem) : Phase
    }

    /** Which view shows the scene: the 3D viewer (and the landing preview) or AR. */
    enum class SceneMode {
        VIEWER,
        AR,
    }

    data class SceneClaim(val id: UUID, val mode: SceneMode)

    var phase: Phase by mutableStateOf(Phase.Connecting)
        private set

    /**
     * Set when a visit call says access has ended mid-session (link revoked or expired, building
     * paused or offline, access removed): every screen gives way to this state.
     */
    var accessProblem: GuestProblem? by mutableStateOf(null)
        private set

    var visit: Visit? by mutableStateOf(null)
        private set

    var manifest: Manifest? by mutableStateOf(null)
        private set

    var catalog: Catalog? by mutableStateOf(null)
        private set

    /** iOS's value-type `loadState`: updated in place here, so [loadRevision] tells Compose. */
    private val loadState = ModelLoadState()
    private var loadRevision by mutableIntStateOf(0)

    /** Bumped whenever a chunk enters the scene, so viewers can refit. */
    var sceneRevision by mutableIntStateOf(0)
        private set

    /**
     * While AR aims at floor corners (marking, "Fix here"), downloaded chunks wait to be decoded
     * and added: that work runs on the main thread, which ARCore's frame updates share, and a
     * starved session drifts (docs/PLAN.md §3).
     */
    var holdsSceneWork by mutableStateOf(false)

    /** Bumped when the 3D camera should fit [focusBounds] (a room was chosen). */
    var focusRevision by mutableIntStateOf(0)
        private set

    /** The view holding the scene's root, last claim wins. */
    var sceneOwner: SceneClaim? by mutableStateOf(null)
        private set

    /** What the 3D viewer shows: every system in scope at first. */
    var viewerFilters by mutableStateOf(ModelFilters(systems = emptySet()))
        private set

    /** What AR shows: systems over the camera; architecture fades in with the See inside slider. */
    var arFilters by mutableStateOf(ModelFilters(systems = emptySet()))
        private set

    /** The See inside slider (0 Reality · 0.5 Reality + model · 1 Model). */
    var seeInside by mutableDoubleStateOf(SeeInside.defaultValue)
        private set

    /** The element "Show in 3D" framed (IOS-M3-05); a room focus clears it. */
    var focusedElementID: String? by mutableStateOf(null)
        private set

    /** The element "Locate in AR" points at: pulse, arrow and distance (IOS-M3-05). */
    var locating: String? by mutableStateOf(null)
        private set

    /** Signed in as a member: the building from my buildings (its id opens the member endpoints). */
    var memberBuilding: MyBuilding? by mutableStateOf(null)
        private set

    /** My role in the building's organization (members): admins register plates on site. */
    var role: OrganizationRole? by mutableStateOf(null)
        private set

    /** Members: "Trial · 9 days left", "Active until 2036", … (IOS-M3-03, M4-01). */
    var statusLine: BuildingStatusLine? by mutableStateOf(null)
        private set

    /** Building documents (IOS-M3-06), loaded when the list opens. */
    var documents: DocumentsState by mutableStateOf(DocumentsState.Idle)
        private set

    /** "Save this building" (IOS-M3-08). */
    var saveState: SaveState by mutableStateOf(SaveState.UNKNOWN)
        private set

    /** The building's Filament entities under one root, shared by the 3D viewer and AR. */
    val scene = BuildingScene()

    private val connection = BuildingConnection(
        access = access, api = dependencies.api, deviceId = dependencies.deviceID, platform = dependencies.platform,
    )
    private var loading: Job? = null
    private val claims = ArrayList<SceneClaim>()
    private val details = HashMap<String, ElementDetail>()

    /** When the current stretch of use began, for `session_ended`. */
    private var activeSince: Instant? = null

    // Derived

    val name: String get() = manifest?.building?.name ?: visit?.building?.name ?: ""

    val language: CatalogLanguage get() = dependencies.language

    val units: UnitFormatter get() = dependencies.unitFormatter

    val preferences: GuestPreferences get() = dependencies.preferences

    /** The element index from every meta file that arrived (observable through the load revision). */
    val index: ElementIndex
        get() {
            loadRevision
            return loadState.index
        }

    val progress: LoadProgress
        get() {
            loadRevision
            return loadState.progress
        }

    /** A PIN building opened without its PIN: architecture only until [unlock]. */
    val isPinLocked: Boolean get() = visit?.pinLocked == true

    /** The plate number from the scanned URL (`/b/{code}/{plate}`), for plate coaching. */
    val scannedPlate: Int? get() = access.plate

    val plates: List<Manifest.Plate> get() = manifest?.plates ?: emptyList()

    /** The building's code: from the QR, else from the visit or manifest. */
    val code: BuildingCode?
        get() {
            if (access is BuildingAccess.Code) return access.code
            return (visit?.building?.code ?: manifest?.building?.code)?.let(BuildingCode::parse)
        }

    /**
     * Signed in as a member of the building's organization (the visit says `Member`, or member
     * access): "Technical details" on the object card and the status line (IOS-M3-03).
     */
    val isMember: Boolean
        get() {
            if (access is BuildingAccess.Member) return true
            return visit?.via == VisitVia.MEMBER
        }

    /** The expired building's read-only archive (guests and members). */
    val isReadOnly: Boolean
        get() = visit?.readOnly == true || visit?.building?.status == PublicBuildingStatus.EXPIRED ||
            statusLine == BuildingStatusLine.Expired

    /** Systems in scope (the served manifest lists only those), in catalog order. */
    val systems: List<Manifest.SystemSummary>
        get() {
            val systems = manifest?.systems ?: emptyList()
            val catalog = catalog ?: return systems
            return systems.sortedBy { catalog.systemOrder(it.key) }
        }

    /** Systems in scope that aren't the building itself: the ones chips toggle in AR. */
    val serviceSystems: List<Manifest.SystemSummary>
        get() = systems.filter { !ModelFilters.isContext(it.key) }

    /**
     * Scope for lists and search: the manifest's systems (never a hidden one, even if a stale
     * chunk is cached).
     */
    private val scopeSystems: Set<String> get() = manifest?.systemKeys ?: emptySet()

    /** Storeys from the top down, as a picker lists them. */
    val storeysTopDown: List<Manifest.Storey> get() = (manifest?.storeysByOrder ?: emptyList()).reversed()

    val isArchitectureReady: Boolean
        get() {
            loadRevision
            return loadState.models[ChunkPlan.architecture] != null
        }

    /** Chunks that failed to load (shown as unavailable, never as a crash). */
    val failedSystems: Set<String>
        get() {
            loadRevision
            return loadState.failedChunks
        }

    fun systemName(key: String): String = catalog?.systemName(key, language) ?: Catalog.humanizedKey(key)

    fun subsystemName(key: String, of: String): String =
        catalog?.subsystemName(key, of, language) ?: Catalog.humanizedKey(key)

    fun systemColor(key: String): CatalogColor = catalog?.color(system = key) ?: CatalogColor.FALLBACK

    fun subsystemColor(key: String, of: String): CatalogColor =
        catalog?.color(system = of, subsystem = key) ?: systemColor(of)

    fun storeyName(id: String?): String? {
        if (id == null) return null
        return manifest?.storey(id)?.name
    }

    /** A room's name from the manifest's spaces ("Kitchen" over "R1"). */
    fun roomName(id: String?): String? {
        if (id == null) return null
        return manifest?.space(id)?.displayName
    }

    fun displayName(record: ElementRecord): String = record.displayName(catalog, language)

    /** Equipment in scope: panels, water heaters, AC units, … (A-03 screen 1). */
    val equipment: List<ElementRecord>
        get() {
            val catalog = catalog ?: return emptyList()
            val manifest = manifest ?: return emptyList()
            return index.equipment(catalog, manifest.storeys, scopeSystems, language)
        }

    /**
     * The Rooms list (IOS-M2-08): every room in the manifest, with its elements in [systems]
     * (default: everything in scope).
     */
    fun rooms(systems: Set<String>? = null): List<RoomEntry> {
        val manifest = manifest ?: return emptyList()
        return index.roomList(manifest.spaces, manifest.storeys, (systems ?: scopeSystems).intersect(scopeSystems))
    }

    /** Offline search over the loaded meta, scoped to the visit's systems. */
    fun localSearch(query: String): List<ElementRecord> {
        val roomNames = LinkedHashMap<String, String>()
        for (space in manifest?.spaces ?: emptyList()) {
            space.displayName?.let { roomNames.putIfAbsent(space.id, it) }
        }
        return index.search(query, catalog, language, scopeSystems, roomNames)
    }

    // Loading

    /**
     * Starts the visit and loads the model: the architecture chunk first, then the systems in
     * [priority] (e.g. from "What do you need?"), then the rest. Calling it again does nothing
     * unless the last attempt failed.
     */
    fun start(priority: List<String> = emptyList()) {
        if (loading != null && !isFailed) return
        loading = scope.launch { run(priority) }
        if (activeSince == null) activeSince = Instant.now()
    }

    private val isFailed: Boolean get() = phase is Phase.Failed

    private suspend fun run(priority: List<String>) = coroutineScope {
        phase = Phase.Connecting
        val catalogRepository = dependencies.catalog
        val loadedCatalog = async {
            try {
                catalogRepository.catalog()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        val manifest: Manifest
        try {
            if (visit == null) visit = connection.start()
            manifest = connection.manifest()
            visit = connection.visit
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteAccess(e)
            phase = Phase.Failed(GuestProblem.from(e))
            loadedCatalog.await()
            return@coroutineScope
        }
        if (!isActive) return@coroutineScope
        this@BuildingSession.manifest = manifest
        code?.let { dependencies.recents.record(it) }
        if (memberBuilding == null && isMember) {
            scope.launch { loadMemberInfo() }
        }
        catalog = loadedCatalog.await()
        scene.setStoreys(manifest.storeys)
        val all = manifest.systemKeys
        viewerFilters = viewerFilters.copy(systems = all)
        arFilters = arFilters.copy(systems = all - ChunkPlan.architecture)
        setSeeInside(seeInside)
        applyActiveFilters()
        phase = Phase.Loading

        val connection = connection
        val requests = ChunkPlan.requests(manifest, kinds = ChunkPlan.defaultKinds, priority = priority)
        val events = dependencies.downloader.load(
            requests,
            urlsExpireAt = manifest.urlsExpireAt,
            refreshManifest = {
                // Fresh URLs mid-download: also where a revoked link or paused building shows up.
                // (Runs on the downloader's I/O dispatcher; state changes go back to main.)
                try {
                    val fresh = connection.manifest()
                    withContext(Dispatchers.Main.immediate) { syncVisit() }
                    fresh
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        withContext(Dispatchers.Main.immediate) { noteAccess(e) }
                    }
                    throw e
                }
            },
        )
        events.collect { event ->
            while (holdsSceneWork && isActive) delay(200)
            if (!isActive) return@collect
            handle(event)
        }
        phase = BuildingSessionRules.finalPhase(loadState.models.isEmpty(), loadState.failures.values)
    }

    private suspend fun handle(event: ChunkEvent) {
        val change = loadState.apply(event)
        loadRevision += 1
        when (change) {
            is ModelLoadState.Change.Progress, is ModelLoadState.Change.Failed -> Unit
            is ModelLoadState.Change.Indexed -> scene.refresh(change.chunk, loadState.index, catalog)
            is ModelLoadState.Change.ModelReady -> try {
                scene.addChunk(change.file, loadState.index, catalog)
                applyActiveFilters()
                sceneRevision += 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An unreadable GLB: the chunk is left out, the rest carries on.
                loadState.apply(ChunkEvent.Failed(change.file.request, ChunkLoadError.Storage(e.toString())))
                loadRevision += 1
            }
        }
    }

    /**
     * Loads again with the current visit (after the PIN unlocked more systems). Chunks already in
     * the scene stay; new ones download.
     */
    private fun reload() {
        loading?.cancel()
        loading = scope.launch { run(emptyList()) }
    }

    /** Stops downloads (the building was closed). */
    fun cancel() {
        loading?.cancel()
    }

    /**
     * Records an error from a visit call; one that ends access stops the load and replaces the
     * screens with its state.
     */
    internal fun noteAccess(error: Throwable, elementLookup: Boolean = false) {
        val problem = GuestProblem.from(error)
        if (!problem.endsAccess || accessProblem != null) return
        // A 404 for one element is that element, not the building.
        if (elementLookup && problem == GuestProblem.NotFound) return
        accessProblem = problem
        loading?.cancel()
    }

    /** The connection may have started a new visit (expired token); keep ours in step. */
    private fun syncVisit() {
        visit = connection.visit
    }

    // PIN (IOS-M2-02)

    /**
     * Enters the building's PIN. On success the visit sees every system and the model reloads;
     * throws `pin.invalid` / `pin.locked` for the keypad.
     */
    suspend fun unlock(pin: String) {
        try {
            visit = connection.unlock(pin)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteAccess(e)
            throw e
        }
        reload()
    }

    // Scene ownership

    /** A view that shows the scene claims it when it appears; the last claim holds the root. */
    fun claimScene(id: UUID, mode: SceneMode) {
        claims.removeAll { it.id == id }
        claims.add(SceneClaim(id, mode))
        updateOwner()
    }

    fun releaseScene(id: UUID) {
        claims.removeAll { it.id == id }
        updateOwner()
    }

    private fun updateOwner() {
        val owner = claims.lastOrNull()
        if (owner == sceneOwner) return
        sceneOwner = owner
        applyActiveFilters()
    }

    private fun applyActiveFilters() {
        scene.apply(if (sceneOwner?.mode == SceneMode.AR) arFilters else viewerFilters, loadState.index)
    }

    // Filters

    fun filters(mode: SceneMode): ModelFilters = if (mode == SceneMode.AR) arFilters else viewerFilters

    fun setFilters(filters: ModelFilters, mode: SceneMode) {
        when (mode) {
            SceneMode.VIEWER -> viewerFilters = filters
            SceneMode.AR -> arFilters = filters
        }
        if (sceneOwner?.mode == mode) {
            scene.apply(filters, loadState.index)
        }
    }

    fun toggleSystem(key: String, mode: SceneMode) {
        val filters = filters(mode).toggledSystem(key)
        val on = key in filters.systems
        setFilters(filters, mode)
        track { AnalyticsEvent.systemToggled(key, on, it) }
    }

    fun toggleSubsystem(key: String, of: String, mode: SceneMode) {
        setFilters(filters(mode).toggledSubsystem(key, of), mode)
    }

    /** Shows only these systems (plus architecture in the 3D viewer, for context). */
    fun focus(systems: List<String>, mode: SceneMode) {
        val inScope = scopeSystems.intersect(systems.toSet())
        if (inScope.isEmpty()) return
        val shown = if (mode == SceneMode.VIEWER) inScope + scopeSystems.intersect(setOf(ChunkPlan.architecture)) else inScope
        setFilters(filters(mode).copy(systems = shown), mode)
    }

    /** "What do you need?": the trade's systems and subsystems, in the 3D viewer and in AR. */
    fun apply(need: ProfessionalNeed) {
        val manifest = manifest ?: return
        for (mode in listOf(SceneMode.VIEWER, SceneMode.AR)) {
            val preset = ModelFilters.preselected(need, manifest, includeArchitecture = mode == SceneMode.VIEWER) ?: continue
            setFilters(filters(mode).copy(systems = preset.systems, hiddenSubsystems = preset.hiddenSubsystems), mode)
        }
    }

    fun setStorey(storeyID: String?, mode: SceneMode) {
        var filters = filters(mode).copy(storeyID = storeyID)
        val roomID = filters.roomID
        val room = roomID?.let { manifest?.space(it) }
        if (room != null && room.storeyId != null && room.storeyId != storeyID) {
            filters = filters.copy(roomID = null) // the room is on another level
        }
        setFilters(filters, mode)
    }

    /**
     * Focuses a room (IOS-M2-08): its level, only its system elements, and in 3D the camera on
     * it. `null` clears the focus.
     */
    fun focusRoom(roomID: String?, mode: SceneMode) {
        if (mode == SceneMode.VIEWER) focusedElementID = null
        var filters = filters(mode).copy(roomID = roomID)
        if (roomID != null) {
            filters = filters.copy(storeyID = rooms().firstOrNull { it.id == roomID }?.storeyID ?: filters.storeyID)
        }
        setFilters(filters, mode)
        if (mode == SceneMode.VIEWER) focusRevision += 1
    }

    /**
     * Where the 3D camera should look: the element from "Show in 3D", the focused room's
     * elements, else what's visible.
     */
    val focusBounds: Bounds?
        get() {
            focusedElementID?.let { id -> scene.bounds(ofElement = id)?.let { return it } }
            viewerFilters.roomID?.let { roomID ->
                scene.bounds(ofElements = index.elementIDsInRoom(roomID))?.let { return it }
            }
            return scene.visibleBounds()
        }

    /**
     * The See inside slider (IOS-M2-06): systems opacity `min(1, 2s)`, architecture
     * `max(0, 2s − 1)`; architecture is on in AR only while it's visible.
     */
    fun setSeeInside(value: Double) {
        seeInside = value
        var filters = SeeInside.apply(value, to = arFilters)
        filters = if (filters.architectureOpacity > 0 && ChunkPlan.architecture in scopeSystems) {
            filters.copy(systems = filters.systems + ChunkPlan.architecture)
        } else {
            filters.copy(systems = filters.systems - ChunkPlan.architecture)
        }
        setFilters(filters, SceneMode.AR)
    }

    // Elements

    /** Full detail for the object card (`/v1/visit/elements/{id}` or the member endpoint). */
    suspend fun detail(id: String): ElementDetail {
        details[id]?.let { return it }
        try {
            val detail = connection.element(id)
            details[id] = detail
            return detail
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteAccess(e, elementLookup = true)
            throw e
        }
    }

    /** The object card opened (`object_opened`). */
    fun opened(elementID: String) {
        val record = index[elementID] ?: return
        track { AnalyticsEvent.objectOpened(record.kind, record.system, it) }
    }

    /** Highlights the element on the object card. */
    fun select(id: String?) {
        scene.select(id)
    }

    // Analytics (IOS-M2-09)

    /**
     * Queues an event for this building's visit (or member scope). Events before the visit exists
     * are dropped: there's nothing to send them as.
     */
    fun track(make: (Instant) -> AnalyticsEvent) {
        val date = Instant.now()
        val audience = connection.analyticsAudience() ?: return
        dependencies.analytics.track(make(date), audience)
    }

    /**
     * The building was closed or the app went to the background: `session_ended` with the time
     * since it was opened (or came back), then a flush.
     */
    fun endSession() {
        val since = activeSince ?: return
        activeSince = null
        val duration = (Instant.now().toEpochMilli() - since.toEpochMilli()).milliseconds
        track { AnalyticsEvent.sessionEnded(duration, it) }
        val analytics = dependencies.analytics
        dependencies.applicationScope.launch { analytics.flush() }
    }

    /** Back in the foreground: a new stretch of use begins. */
    fun resumeSession() {
        if (activeSince == null && visit != null) activeSince = Instant.now()
    }

    // Signed-in app (M3)

    sealed interface DocumentsState {
        data object Idle : DocumentsState

        data object Loading : DocumentsState

        data class Loaded(val items: List<DocumentItem>) : DocumentsState

        data class Failed(val problem: GuestProblem) : DocumentsState
    }

    enum class SaveState {
        /** Not known yet (signed out, or my buildings not loaded). */
        UNKNOWN,

        /** Already mine: a member or grant building, nothing to save. */
        MINE,
        NOT_SAVED,
        SAVING,
        SAVED,

        /** Saving didn't work; "Try again". */
        FAILED,
    }

    /** A search result row: from the server's search, or the offline meta search. */
    data class SearchRow(
        /** An element id (`e…`) or, for a room, the space id. */
        val id: String,
        val isRoom: Boolean,
        val title: String,
        val system: String?,
        val subsystem: String?,
        /** "Kitchen · Level 1". */
        val place: String?,
    )

    /** iOS's `(rows:, offline:)` tuple. */
    data class SearchResult(val rows: List<SearchRow>, val offline: Boolean)

    // Members (IOS-M3-03)

    /** Looks up the building in my buildings (its id), then its status and the trial's end. */
    internal suspend fun loadMemberInfo() {
        val code = code ?: return
        val building = dependencies.myBuildings.building(code) ?: return
        if (building.via != MyBuildingVia.MEMBER) return
        memberBuilding = building
        var status: BuildingStatus = building.status
        var expiresAt: Instant? = null
        val id = building.buildingID
        if (id != null) {
            val detail = try {
                dependencies.api.building(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (detail != null) {
                status = detail.status
                expiresAt = detail.expiresAt
                role = detail.role
            }
        }
        val trialEnds = if (status == BuildingStatus.TRIAL_LIVE) dependencies.myBuildings.trialEnds() else emptyMap()
        statusLine = BuildingStatusLine.from(
            status, trialEndsAt = trialEnds[building.organizationId.lowercase()], expiresAt = expiresAt, now = Instant.now(),
        )
    }

    // Plates on site (docs/PLAN.md §3 "Registering a plate on site")

    /**
     * Admins register where a plate was stuck. (iOS also requires the full app, not the Clip;
     * Android has no Clip, docs/PLAN.md §3 "AR".)
     */
    val canManagePlates: Boolean
        get() = role?.canManage == true && memberBuilding?.buildingID != null

    /**
     * The scanned plate when it isn't in the model yet (nothing to align on): admins are offered
     * to register it.
     */
    val scannedPlateIsUnplaced: Boolean
        get() {
            val plate = scannedPlate ?: return false
            val manifest = manifest ?: return false
            return manifest.plate(plate) == null
        }

    /** Every plate of the building, placed or not. */
    suspend fun buildingPlates(): List<BuildingPlate> {
        val id = memberBuilding?.buildingID ?: return emptyList()
        return dependencies.api.plates(id)
    }

    /**
     * Saves a plate's position in this version's model coordinates, then reloads the manifest's
     * plates so AR aligns on it.
     */
    suspend fun registerPlate(id: UUID, frame: PlateFrame) {
        val versionID = manifest?.versionId ?: return
        dependencies.api.placePlate(id, versionID, frame)
        refreshPlates()
    }

    /** The manifest's plates again (after registering one); the scene stays. */
    suspend fun refreshPlates() {
        val fresh = try {
            connection.manifest()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        manifest = manifest?.copy(plates = fresh.plates)
    }

    // Room corrections (docs/PLAN.md §3 "Room corrections")

    /** The rooms with an outline and where each really is, as the manifest has them. */
    val roomCorrections: RoomCorrections get() = RoomCorrections.of(manifest?.spaces ?: emptyList())

    /** Admins save a room's correction for everyone, like plates. */
    val canManageRoomCorrections: Boolean get() = canManagePlates

    /**
     * Sends a "Fix here" that measured its room, in the background: losing one is harmless, the
     * server pools many. Nothing for an expired building's archive.
     */
    fun recordRoomObservation(observation: RoomObservation, lidar: Boolean) {
        if (isReadOnly) return
        val connection = connection
        dependencies.applicationScope.launch {
            try {
                connection.recordRoomObservation(observation, lidar)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Saves where room [spaceID] really is for everyone (admins), then uses it here too. */
    suspend fun saveRoomCorrection(spaceID: String, correction: RoomCorrection) {
        val buildingID = memberBuilding?.buildingID ?: return
        val versionID = manifest?.versionId ?: return
        dependencies.api.saveRoomCorrection(buildingID, spaceID, versionID, correction)
        val current = manifest ?: return
        if (current.spaces.none { it.id == spaceID }) return
        manifest = current.copy(
            spaces = current.spaces.map { if (it.id == spaceID) it.copy(correction = correction.manifest) else it },
        )
    }

    // Search (IOS-M3-04)

    /**
     * The server's search in this building (members' and guests' scope alike, through the visit),
     * or the phone's copy of the meta when the server can't be reached. An error that ends access
     * sets [accessProblem].
     */
    suspend fun search(query: String): SearchResult {
        val connection = connection
        val outcome: BuildingSearchOutcome<ElementRecord> = try {
            BuildingSearch.run(query, server = { connection.search(query) }, offline = { localSearch(query) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteAccess(e)
            throw e
        }
        val result = when (outcome) {
            is BuildingSearchOutcome.Server -> SearchResult(
                BuildingSessionRules.serverSearchRows(outcome.hits, scopeSystems) { index[it]?.subsystem },
                offline = false,
            )
            is BuildingSearchOutcome.Offline -> SearchResult(
                outcome.results.map { record ->
                    SearchRow(
                        id = record.id, isRoom = false, title = displayName(record), system = record.system,
                        subsystem = record.subsystem,
                        place = BuildingSessionRules.placeLine(roomName(record.roomID), storeyName(record.storeyID)),
                    )
                },
                offline = true,
            )
        }
        val count = result.rows.size
        track { AnalyticsEvent.searchPerformed(count, it) }
        return result
    }

    // Locate in AR and Show in 3D (IOS-M3-05)

    /** Points AR at an element: its system is turned on in AR so the pulse has something to show. */
    fun locate(id: String?) {
        locating = id
        if (id == null) return
        val system = index[id]?.system ?: details[id]?.system ?: return
        val filters = arFilters
        if (!ModelFilters.isContext(system) && system in scopeSystems && system !in filters.systems) {
            setFilters(filters.copy(systems = filters.systems + system), SceneMode.AR)
        }
    }

    /** Where Locate in AR points: the element's box in model coordinates. */
    fun locateBounds(): Bounds? = locating?.let { scene.bounds(ofElement = it) }

    /** "Show in 3D": the element's system and level on, the camera on it, the element selected. */
    fun focusElement(id: String) {
        var filters = viewerFilters
        val record = index[id]
        val system = record?.system ?: details[id]?.system
        if (system != null && system in scopeSystems) {
            filters = filters.copy(systems = filters.systems + system)
        }
        if (filters.roomID != null && record?.roomID != filters.roomID) {
            filters = filters.copy(roomID = null)
        }
        val storey = filters.storeyID
        val elementStorey = record?.storeyID
        if (storey != null && elementStorey != null && storey != elementStorey) {
            filters = filters.copy(storeyID = elementStorey)
        }
        setFilters(filters, SceneMode.VIEWER)
        focusedElementID = id
        focusRevision += 1
    }

    // Documents (IOS-M3-06)

    /** The building's documents: every one for members, those shared with guests otherwise. */
    suspend fun loadDocuments() {
        if (documents == DocumentsState.Loading) return
        documents = DocumentsState.Loading
        documents = try {
            DocumentsState.Loaded(fetchDocuments(null).filter { it.url != null })
        } catch (e: CancellationException) {
            documents = DocumentsState.Idle
            throw e
        } catch (e: Exception) {
            noteAccess(e)
            DocumentsState.Failed(GuestProblem.from(e))
        }
    }

    private suspend fun fetchDocuments(elementID: String?): List<DocumentItem> {
        val id = memberBuilding?.buildingID
        if (id != null) {
            return dependencies.api.buildingDocuments(id, elementID).map(DocumentItem::of)
        }
        return connection.documents(elementID).map(DocumentItem::of)
    }

    /**
     * Downloads a document for the viewer app (a fresh link when the 15-minute one expired) and
     * counts `document_opened`.
     */
    suspend fun open(item: DocumentItem): File {
        try {
            val file = dependencies.documents.file(
                item,
                renewURL = { fetchDocuments(item.elementID).firstOrNull { it.id == item.id }?.url },
            )
            track { AnalyticsEvent.documentOpened(it) }
            return file
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noteAccess(e)
            throw e
        }
    }

    // Save this building (IOS-M3-08)

    /** Whether "Save this building" applies: a guest visit to a live building. */
    val canSave: Boolean
        get() {
            val visit = visit ?: return false
            if (code == null) return false
            return visit.via != VisitVia.MEMBER && visit.via != VisitVia.GRANT && visit.building.status.isViewable
        }

    /** Signed in on this device (tokens are stored). */
    suspend fun isSignedIn(): Boolean = dependencies.credentials.isSignedIn()

    /** Checks my buildings for this one (signed in). */
    suspend fun refreshSaveState() {
        val code = code
        if (code == null || !dependencies.credentials.isSignedIn()) {
            saveState = SaveState.UNKNOWN
            return
        }
        saveState = BuildingSessionRules.saveState(dependencies.myBuildings.building(code)?.via)
    }

    /** Saves it to the signed-in account; it shows up in Buildings. */
    suspend fun save() {
        val code = code ?: return
        saveState = SaveState.SAVING
        saveState = try {
            dependencies.myBuildings.save(code)
            track { AnalyticsEvent.buildingSaved(it) }
            SaveState.SAVED
        } catch (e: CancellationException) {
            saveState = SaveState.FAILED
            throw e
        } catch (_: Exception) {
            SaveState.FAILED
        }
    }
}
