package com.getinsiteview.scene

// IVScene's port: the building as Filament nodes (docs/PLAN.md §2, §3 "Loading a building"). Loads
// GLB chunks under one root, applies catalog materials, storey and system visibility, the selection
// highlight, picking and bounds, and in AR hides what's behind walls and fades what's far away. The
// same root moves between the 3D viewer and AR (one Filament engine, `SceneEngine`), so switching
// modes doesn't reload anything.
//
// The logic it relies on (filters, picking, bounds, names, the fade) lives in `:modelkit` and is
// tested there.

import android.content.Context
import com.getinsiteview.core.Catalog
import com.getinsiteview.modelkit.ChunkPlan
import com.getinsiteview.modelkit.ElementIndex
import com.getinsiteview.modelkit.LoadedChunkFile
import com.getinsiteview.modelkit.Manifest
import com.getinsiteview.modelkit.ModelFilters
import com.getinsiteview.modelkit.NodeNames
import com.getinsiteview.modelkit.geometry.Bounds
import com.getinsiteview.modelkit.geometry.ProximityTracker
import com.getinsiteview.modelkit.geometry.Vec2
import com.getinsiteview.modelkit.geometry.Vec3
import com.getinsiteview.modelkit.scene.GlbMaterial
import com.getinsiteview.modelkit.scene.GlbMaterials
import com.getinsiteview.modelkit.scene.Matrix4
import com.getinsiteview.modelkit.scene.ScenePicking
import com.google.android.filament.MaterialInstance
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.model.Model
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.RenderableNode
import io.github.sceneview.node.SphereNode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the scene shows ([ModelFilters]: systems, storey, subsystems, room, opacity). */
typealias SceneFilters = ModelFilters

/**
 * The building's scene (iOS `BuildingScene`). Create it on the main thread and call it there.
 */
class BuildingScene(context: Context) {
    private val engine = SceneEngine.engine
    private val modelLoader = SceneEngine.modelLoader(context)
    private val materialLoader = SceneEngine.materialLoader(context)
    private val materials = SceneMaterials(materialLoader)

    /** Parent of every chunk. The 3D viewer keeps it at the origin; AR sets the alignment. */
    val root: Node = Node(engine).apply { name = ROOT_NAME }

    /** A mesh in a chunk, with the element it draws (if any) and what it showed when loaded. */
    private class Part(
        val node: RenderableNode,
        /** `null` outside elements (and inside an element nested in another). */
        val elementID: String?,
        /** The storey group (`s{order}`) it sits under, if any. */
        val storeyOrder: Int?,
        val originals: List<MaterialInstance>,
        val originalNames: List<String?>,
        /** Its box in its own frame; `null` when empty. */
        val box: Bounds?,
    ) {
        val current = arrayOfNulls<MaterialInstance>(originals.size)
    }

    private class ChunkNode(
        val key: String,
        val system: String,
        val container: Node,
        val modelNode: ModelNode,
        val model: Model,
        /** Element id → node. */
        val elements: Map<String, Node>,
        /** Storey order → storey group node (`s{order}`). */
        val storeyGroups: Map<Int, Node>,
        val parts: List<Part>,
        val elementParts: Map<String, List<Part>>,
        val fileMaterials: List<GlbMaterial>,
    ) {
        var isEnabled = true
        var opacity = 1.0
        val storeyShown = HashMap<Int, Boolean>()
        val elementShown = HashMap<String, Boolean>()

        /** Elements' picking boxes (their own frames) and their model → element maps. */
        val pickTargets = HashMap<String, ScenePicking.Target>()
    }

    private val chunks = LinkedHashMap<String, ChunkNode>()
    private val elementChunk = HashMap<String, String>()
    private var storeyOrders: Map<String, Int> = emptyMap()
    private var catalog: Catalog? = null

    var filters: SceneFilters = SceneFilters(systems = emptySet())
        private set
    var selectedElementID: String? = null
        private set

    /** Locate in AR's pulsing marker, a child of the root. */
    private var locateMarker: SphereNode? = null

    /** Behind a wall (docs/PLAN.md §3): system elements' boxes and the walls, fading in AR only. */
    private val proximity = ProximityTracker()
    private var proximityOn = false

    /** The latest index the scene was given, for re-applying filters to one element. */
    private var index = ElementIndex()

    /** Keys of the chunks in the scene. */
    val loadedChunks: Set<String> get() = chunks.keys.toSet()

    /** Storey ids by `order`, for storey groups (`s{order}`) in the chunk files. */
    fun setStoreys(storeys: List<Manifest.Storey>) {
        val orders = LinkedHashMap<String, Int>()
        for (storey in storeys) orders.putIfAbsent(storey.id, storey.order)
        storeyOrders = orders
    }

    // region Loading

    /**
     * Loads a chunk's GLB under the root, then styles it, filters it and makes its elements
     * tappable. Loading the same chunk twice does nothing. Call on the main thread; the file is read
     * off it. Throws when the file isn't a readable GLB.
     */
    suspend fun addChunk(file: LoadedChunkFile, index: ElementIndex, catalog: Catalog?) {
        val key = file.request.chunk
        if (chunks.containsKey(key)) return
        val bytes = withContext(Dispatchers.IO) { file.url.readBytes() }
        val fileMaterials = withContext(Dispatchers.Default) { GlbMaterials.read(bytes) }
        if (chunks.containsKey(key)) return // loaded twice concurrently
        if (isDestroyed) return // the building closed while the file was read

        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes)
        buffer.rewind()
        val model = modelLoader.createModel(buffer, releaseSourceData = true)
        val modelNode = ModelNode(modelInstance = model.instance, autoAnimate = false)
        val container = Node(engine).apply { name = NodeNames.chunkRoot(key) }
        container.addChildNode(modelNode)

        val elements = LinkedHashMap<String, Node>()
        val storeyGroups = HashMap<Int, Node>()
        val parts = ArrayList<Part>()
        collect(container, element = null, insideElement = false, storey = null, elements, storeyGroups, parts)
        val elementParts = parts.filter { it.elementID != null }.groupBy { it.elementID!! }
        val node = ChunkNode(
            key = key, system = file.request.system, container = container, modelNode = modelNode, model = model,
            elements = elements, storeyGroups = storeyGroups, parts = parts, elementParts = elementParts, fileMaterials = fileMaterials,
        )
        for (id in elements.keys) elementChunk[id] = key
        chunks[key] = node
        root.addChildNode(container)

        this.catalog = catalog ?: this.catalog
        this.index = index
        applyFilters(node)
        restyle(node)
        addPickTargets(node)
        trackProximity(node, index)
    }

    /**
     * Depth-first walk (iOS `walk`): elements don't nest (a nested one stays part of its outer
     * element's subtree, unstyled, as iOS's `forEachModel` leaves it); storey groups are found
     * outside elements.
     */
    private fun collect(
        node: Node,
        element: String?,
        insideElement: Boolean,
        storey: Int?,
        elements: MutableMap<String, Node>,
        storeyGroups: MutableMap<Int, Node>,
        parts: MutableList<Part>,
    ) {
        var currentElement = element
        var inside = insideElement
        var currentStorey = storey
        val name = node.name
        if (name != null && NodeNames.isElement(name)) {
            if (!inside) {
                elements[name] = node
                currentElement = name
                inside = true
            } else {
                currentElement = null
            }
        } else if (!inside && name != null) {
            NodeNames.storeyOrder(name)?.let { order ->
                storeyGroups[order] = node
                currentStorey = order
            }
        }
        if (node is RenderableNode) {
            val originals = runCatching { node.materialInstances }.getOrDefault(emptyList())
            parts += Part(
                node = node,
                elementID = currentElement,
                storeyOrder = currentStorey,
                originals = originals,
                originalNames = originals.map { instance -> runCatching { instance.name }.getOrNull() },
                box = localBox(node),
            )
        }
        for (child in node.childNodes) collect(child, currentElement, inside, currentStorey, elements, storeyGroups, parts)
    }

    /** Re-applies colours and filters after a chunk's meta arrives (or the catalog). */
    fun refresh(chunk: String, index: ElementIndex, catalog: Catalog?) {
        this.catalog = catalog ?: this.catalog
        this.index = index
        val node = chunks[chunk] ?: return
        restyle(node)
        applyFilters(node)
        trackProximity(node, index)
    }

    fun removeChunk(key: String) {
        val node = chunks.remove(key) ?: return
        for (id in node.elements.keys) elementChunk.remove(id)
        proximity.removeTargets(node.elements.keys)
        val selected = selectedElementID
        if (selected != null && node.elements.containsKey(selected)) selectedElementID = null
        node.container.parent = null
        node.container.destroy()
        modelLoader.destroyModel(node.model)
    }

    // endregion

    // region Materials

    /** System elements take their catalog colour (subsystem first); architecture and structure keep the file's. */
    private fun restyle(node: ChunkNode) {
        for (part in node.parts) applyMaterials(node, part)
    }

    /**
     * A mesh's materials now: the highlight when its element is selected, the catalog colour for a
     * system element, else the file's; transparent copies below full opacity (the chunk's See
     * inside opacity times the element's fade).
     */
    private fun applyMaterials(node: ChunkNode, part: Part) {
        val id = part.elementID
        val opacity = node.opacity * (if (id != null) proximityOpacity(id) else 1.0)
        val selected = id != null && id == selectedElementID
        val catalog = catalog
        val recolour = id != null && catalog != null && SceneMaterials.isRecoloured(node.system)
        for (primitive in part.originals.indices) {
            val material = when {
                selected -> materials.highlight(opacity)
                recolour -> materials.surface(catalog!!.color(node.system, index[id!!]?.subsystem), opacity)
                SceneMaterials.step(opacity) >= 100 -> part.originals[primitive]
                else -> materials.faded(GlbMaterials.lookup(node.fileMaterials, part.originalNames[primitive]), opacity)
            }
            if (part.current[primitive] !== material) {
                part.node.setMaterialInstanceAt(primitive, material)
                part.current[primitive] = material
            }
        }
    }

    // endregion

    // region Visibility

    fun apply(filters: SceneFilters, index: ElementIndex) {
        this.filters = filters
        this.index = index
        proximity.ignoresWalls = filters.roomID != null
        for (node in chunks.values) applyFilters(node)
    }

    private fun applyFilters(node: ChunkNode) {
        val enabled = filters.showsChunk(node.system)
        if (node.isEnabled != enabled) {
            node.isEnabled = enabled
            node.container.isVisible = enabled
        }
        // See inside: systems and architecture fade separately (iOS: an OpacityComponent on the chunk).
        val opacity = filters.opacity(ofSystem = node.system)
        if (opacity != node.opacity) {
            node.opacity = opacity
            restyle(node)
        }
        if (!enabled) return
        if (node.storeyGroups.isNotEmpty()) {
            // Storey groups from the converter: one switch per storey.
            val selectedOrder = filters.storeyID?.let { storeyOrders[it] }
            for ((order, group) in node.storeyGroups) {
                val shows = selectedOrder == null || order == selectedOrder
                if (node.storeyShown[order] != shows) {
                    node.storeyShown[order] = shows
                    group.isVisible = shows
                }
            }
        }
        // Subsystems, the room focus and (without storey groups) the storey, per element; in AR,
        // also gone past the fading distance.
        for ((id, element) in node.elements) {
            val shows = filters.showsElement(index[id], system = node.system) && proximityOpacity(id) > 0.01
            setShown(node, id, element, shows)
        }
    }

    private fun setShown(node: ChunkNode, id: String, element: Node, shows: Boolean) {
        if (node.elementShown[id] == shows) return
        node.elementShown[id] = shows
        element.isVisible = shows
    }

    /** Whether a mesh shows, from the scene's own switches (not the root's, which AR turns off until placed). */
    private fun isShown(node: ChunkNode, part: Part): Boolean {
        if (!node.isEnabled) return false
        val order = part.storeyOrder
        if (order != null && node.storeyShown[order] == false) return false
        val id = part.elementID
        if (id != null && node.elementShown[id] == false) return false
        return true
    }

    // endregion

    // region Behind a wall

    /**
     * AR hides system elements behind walls of the model and fades far ones ([ProximityTracker]).
     * Call every frame with the camera in model coordinates; `null` stops and puts everything back,
     * as the 3D viewer shows it.
     */
    fun updateProximity(camera: Vec3?) {
        if (camera == null) {
            if (!proximityOn) return
            proximityOn = false
            for (id in proximity.reset()) applyProximity(id)
            return
        }
        proximityOn = true
        for (change in proximity.update(camera)) applyProximity(change.id)
    }

    /** Opacity from the fading, 1 outside AR; the element whose card is open always shows in full. */
    private fun proximityOpacity(id: String): Double {
        if (!proximityOn || id == selectedElementID) return 1.0
        return proximity.opacity(of = id)
    }

    /** One element's fade: its meshes' opacity, and off altogether past the fading distance. */
    private fun applyProximity(id: String) {
        val key = elementChunk[id] ?: return
        val node = chunks[key] ?: return
        val element = node.elements[id] ?: return
        val shows = filters.showsElement(index[id], system = node.system) && proximityOpacity(id) > 0.01
        if (node.isEnabled) setShown(node, id, element, shows) else node.elementShown.remove(id)
        for (part in node.elementParts[id].orEmpty()) applyMaterials(node, part)
    }

    /**
     * Keeps the fading's view of a chunk current: system elements' boxes (model coordinates) and
     * storeys; for the architecture chunk, the walls' footprints from its meta.
     */
    private fun trackProximity(node: ChunkNode, index: ElementIndex) {
        if (node.system == ChunkPlan.architecture) {
            val walls = LinkedHashMap<String?, MutableList<List<Vec2>>>()
            for (record in index.elementsInSystem(ChunkPlan.architecture)) {
                val footprint = record.meta.footprint ?: continue
                walls.getOrPut(record.storeyID) { ArrayList() }.addAll(footprint)
            }
            proximity.setWalls(walls)
        }
        if (!SceneMaterials.isRecoloured(node.system)) return
        for (id in node.elements.keys) {
            val bounds = bounds(ofElement = id) ?: continue
            proximity.setTarget(id, ProximityTracker.Target(bounds = bounds, storeyID = index[id]?.storeyID))
        }
    }

    // endregion

    // region Selection

    /** Highlights one element (the object card's), restoring the previous one. */
    fun select(id: String?) {
        if (id == selectedElementID) return
        val previous = selectedElementID
        selectedElementID = id
        for (changed in listOfNotNull(previous, id)) {
            if (proximityOn) {
                // The open card's element shows in full; the one before goes back to its fade.
                applyProximity(changed)
            } else {
                val node = elementChunk[changed]?.let { chunks[it] } ?: continue
                for (part in node.elementParts[changed].orEmpty()) applyMaterials(node, part)
            }
        }
    }

    // endregion

    // region Locate in AR

    /**
     * Shows the pulsing marker at the centre of [bounds] (model coordinates), or removes it. It's a
     * child of the root, so it follows the alignment; systems draw without occlusion in AR, so it
     * shows through walls like they do.
     */
    fun showLocateMarker(bounds: Bounds?) {
        if (bounds == null) {
            locateMarker?.let { marker ->
                marker.parent = null
                marker.destroy()
            }
            locateMarker = null
            return
        }
        val marker = locateMarker ?: makeLocateMarker()
        marker.position = Float3(bounds.center.x.toFloat(), bounds.center.y.toFloat(), bounds.center.z.toFloat())
        if (marker.parent !== root) root.addChildNode(marker)
        locateMarker = marker
    }

    /** The pulse: the marker's scale (`LocateGuide.pulseScale`). */
    fun setLocateMarkerScale(scale: Float) {
        locateMarker?.scale = Float3(scale, scale, scale)
    }

    private fun makeLocateMarker(): SphereNode {
        val material = locateMaterial ?: materialLoader.createUnlitColorInstance(Float4(0.04f, 0.43f, 0.47f, 0.8f)).also { locateMaterial = it }
        return SphereNode(engine = engine, radius = 0.05f, materialInstance = material).apply { name = "LocateMarker" }
    }

    private var locateMaterial: MaterialInstance? = null

    // endregion

    // region Picking

    /** The element a node belongs to: walks up to the nearest `e{hex}` name. */
    fun elementID(node: Node?): String? {
        var current = node
        while (current != null) {
            val name = current.name
            if (name != null && NodeNames.isElement(name) && elementChunk.containsKey(name)) return name
            if (current === root) return null
            current = current.parent
        }
        return null
    }

    fun element(id: String): Node? = elementChunk[id]?.let { chunks[it]?.elements?.get(id) }

    /**
     * The element a ray (world coordinates) meets first, among those showing (iOS: RealityKit
     * collision boxes and `entity(at:)`). Boxes are grown so thin conduits stay tappable
     * ([ScenePicking]); long diagonal runs fall back to their grown box.
     */
    fun pick(origin: Vec3, direction: Vec3): String? {
        val modelFromWorld = Matrix4.invert(relative(root, ancestor = null)) ?: return null
        val o = Matrix4.transformPoint(modelFromWorld, origin)
        val d = Matrix4.transformDirection(modelFromWorld, direction)
        val targets = ArrayList<ScenePicking.Target>()
        for (node in chunks.values) {
            if (!node.isEnabled) continue
            for ((id, target) in node.pickTargets) {
                if (node.elementShown[id] == false) continue
                val parts = node.elementParts[id].orEmpty()
                if (parts.isNotEmpty() && parts.none { isShown(node, it) }) continue
                targets += target
            }
        }
        return ScenePicking.pick(o, d, targets)
    }

    /** Picking boxes from each element's visual bounds in its own frame (iOS `addCollisions`). */
    private fun addPickTargets(node: ChunkNode) {
        val isSystem = SceneMaterials.isRecoloured(node.system)
        for ((id, element) in node.elements) {
            val box = Bounds.union(
                node.elementParts[id].orEmpty().mapNotNull { part ->
                    part.box?.let { ScenePicking.transformedBounds(it, relative(part.node, ancestor = element)) }
                },
            ) ?: continue
            val elementFromModel = Matrix4.invert(relative(element, ancestor = root)) ?: continue
            node.pickTargets[id] = ScenePicking.Target(id, ScenePicking.pickingBox(box, isSystemElement = isSystem), elementFromModel)
        }
    }

    // endregion

    // region Bounds

    /** Bounds of what's visible, in model coordinates (relative to the root). */
    fun visibleBounds(): Bounds? = Bounds.union(
        chunks.values.flatMap { node ->
            node.parts.filter { isShown(node, it) }.mapNotNull { part -> modelBounds(part) }
        },
    )

    /** Bounds of several elements (a room's) in model coordinates, for focusing the camera. */
    fun bounds(ofElements: Iterable<String>): Bounds? = Bounds.union(ofElements.mapNotNull { bounds(ofElement = it) })

    /** Bounds of one element in model coordinates, for focusing the camera on it. */
    fun bounds(ofElement: String): Bounds? {
        val node = elementChunk[ofElement]?.let { chunks[it] } ?: return null
        return Bounds.union(node.elementParts[ofElement].orEmpty().mapNotNull { modelBounds(it) })
    }

    private fun modelBounds(part: Part): Bounds? = part.box?.let { ScenePicking.transformedBounds(it, relative(part.node, ancestor = root)) }

    /** A mesh's own box (Filament's axis-aligned bounding box), `null` when empty. */
    private fun localBox(node: RenderableNode): Bounds? {
        val box = runCatching { node.axisAlignedBoundingBox }.getOrNull() ?: return null
        val center = box.center
        val half = box.halfExtent
        if (half[0] <= 0f && half[1] <= 0f && half[2] <= 0f) return null
        val c = Vec3(center[0].toDouble(), center[1].toDouble(), center[2].toDouble())
        val h = Vec3(half[0].toDouble(), half[1].toDouble(), half[2].toDouble())
        return Bounds(min = c - h, max = c + h)
    }

    /**
     * [node]'s frame → [ancestor]'s, from the local transforms in between (column-major); up to the
     * top of the hierarchy for `null`. Local transforms are always current in Filament, world ones
     * only after the transform manager's next commit.
     */
    private fun relative(node: Node, ancestor: Node?): DoubleArray {
        var matrix = Matrix4.identity
        var current: Node? = node
        val scratch = FloatArray(16)
        while (current != null && current !== ancestor) {
            current.transformManager.getTransform(current.transformInstance, scratch)
            matrix = Matrix4.multiply(Matrix4.of(scratch), matrix)
            current = current.parent
        }
        return matrix
    }

    // endregion

    // region Teardown

    /** Whether [destroy] ran. */
    var isDestroyed: Boolean = false
        private set

    /**
     * Frees the building's Filament resources: every chunk's nodes and model, the locate marker,
     * the root, and the materials made for it (iOS frees the entities when the session goes). Call
     * on the main thread when the building closes, after the views let go of [root]; the scene
     * isn't usable afterwards. Renderables go before the material instances they use.
     */
    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        for (key in chunks.keys.toList()) removeChunk(key)
        showLocateMarker(null)
        root.parent = null
        root.destroy()
        locateMaterial?.let { materialLoader.destroyMaterialInstance(it) }
        locateMaterial = null
        materials.destroy()
    }

    // endregion

    companion object {
        const val ROOT_NAME = "BuildingRoot"
    }
}
