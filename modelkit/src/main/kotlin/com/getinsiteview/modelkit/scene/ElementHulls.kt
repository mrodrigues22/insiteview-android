package com.getinsiteview.modelkit.scene

/**
 * Exact picking shapes for long diagonal runs (P2-01; iOS `ShapeResource.generateConvex(from:)`):
 * read a chunk's GLB bytes once, off the main thread, for the elements [ScenePicking.usesHull] picks.
 */
object ElementHulls {
    /**
     * The convex hull of each of [elementIds]' meshes in a chunk GLB, in the element node's own frame
     * (the frame of `ScenePicking.Target.elementFromModel`). Elements without geometry are left out.
     * Only the buffer views those elements use are decoded. Throws [GlbException] or
     * [MeshoptException] when the file can't be read.
     */
    fun read(glb: ByteArray, elementIds: Set<String>): Map<String, ConvexHull> {
        if (elementIds.isEmpty()) return emptyMap()
        val positions = GlbGeometry.read(glb).elementPositions(elementIds)
        val hulls = LinkedHashMap<String, ConvexHull>()
        for ((id, points) in positions) ConvexHull.of(points)?.let { hulls[id] = it }
        return hulls
    }
}
