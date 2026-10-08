package com.getinsiteview.modelkit.geometry

import com.getinsiteview.modelkit.ChunkMeta
import com.getinsiteview.modelkit.Fixtures
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Behind a wall")
class BehindWallFadeTest {
    /**
     * A 15 cm wall along x = 0 from z = −3 to 3 between two rooms, with a 1 m passage open to the top
     * at z −0.5…0.5: two pieces, as the converter gives them.
     */
    private val wall: List<List<Vec2>> = listOf(
        listOf(Vec2(-0.075, -3.0), Vec2(-0.075, -0.5), Vec2(0.075, -0.5), Vec2(0.075, -3.0)),
        listOf(Vec2(-0.075, 0.5), Vec2(-0.075, 3.0), Vec2(0.075, 3.0), Vec2(0.075, 0.5)),
    )

    /** The same wall with a door instead: the wall above it closes the doorway, so it's one piece. */
    private val wallWithDoor: List<List<Vec2>> = listOf(
        listOf(Vec2(-0.075, -3.0), Vec2(-0.075, 3.0), Vec2(0.075, 3.0), Vec2(0.075, -3.0)),
    )

    private fun near(a: Double, b: Double): Boolean = abs(a - b) < 1e-9

    private fun List<ProximityTracker.Change>.asMap(): Map<String, Double> = associate { it.id to it.opacity }

    @Test
    @DisplayName("A wall between the camera and the element hides it; an open passage doesn't")
    fun `A wall between the camera and the element hides it, an open passage doesn't`() {
        val occlusion = WallOcclusion(footprints = wall)
        assertTrue(occlusion.blocks(from = Vec2(-2.0, 1.5), to = Vec2(2.0, 1.5)))
        assertTrue(occlusion.blocks(from = Vec2(-2.0, 0.0), to = Vec2(2.0, 2.5)), "diagonally through the wall")
        assertFalse(occlusion.blocks(from = Vec2(-2.0, 0.0), to = Vec2(2.0, 0.0)), "straight through the passage")
        assertFalse(occlusion.blocks(from = Vec2(-2.0, 1.5), to = Vec2(-1.0, -2.0)), "same room")
    }

    @Test
    @DisplayName("A door is closed by the wall above it; standing in the doorway shows both rooms")
    fun `A door is closed by the wall above it, standing in the doorway shows both rooms`() {
        val occlusion = WallOcclusion(
            footprints = wallWithDoor + listOf(listOf(Vec2(3.0, -3.0), Vec2(3.0, 3.0), Vec2(3.15, 3.0), Vec2(3.15, -3.0))),
        )
        assertTrue(occlusion.blocks(from = Vec2(-2.0, 0.0), to = Vec2(2.0, 0.0)), "straight through the door")
        assertTrue(occlusion.blocks(from = Vec2(2.0, 1.0), to = Vec2(-2.0, 1.0)), "back through it")
        assertFalse(occlusion.blocks(from = Vec2(0.0, 0.0), to = Vec2(2.0, 1.0)), "from the doorway, ahead")
        assertFalse(occlusion.blocks(from = Vec2(0.07, 0.0), to = Vec2(-2.0, -2.5)), "from the doorway's edge, behind")
        assertTrue(occlusion.blocks(from = Vec2(0.0, 0.0), to = Vec2(4.0, 0.0)), "the next wall still hides")
    }

    @Test
    @DisplayName("Set in the wall you face: shown; set from the other side: behind it")
    fun `Set in the wall you face - shown, set from the other side - behind it`() {
        val occlusion = WallOcclusion(footprints = wall)
        // A pipe 3 cm behind this room's face, and an outlet's box 5 cm deep from the other room's face.
        assertFalse(occlusion.blocks(from = Vec2(-2.0, 2.0), to = Vec2(-0.045, 2.0)))
        assertTrue(occlusion.blocks(from = Vec2(-2.0, 2.0), to = Vec2(0.025, 2.0)))
        assertTrue(occlusion.blocks(from = Vec2(-2.0, 2.0), to = Vec2(0.075, 2.0)), "on the far face")
    }

    @Test
    fun `Touching a wall's end isn't crossing it`() {
        val occlusion = WallOcclusion(footprints = listOf(listOf(Vec2(0.0, 0.0), Vec2(0.0, 0.15), Vec2(1.0, 0.15), Vec2(1.0, 0.0))))
        assertFalse(occlusion.blocks(from = Vec2(-1.0, -1.0), to = Vec2(0.0, 0.0), faceTolerance = 0.0), "ends on the corner")
        assertFalse(occlusion.blocks(from = Vec2(-1.0, 0.0), to = Vec2(2.0, 0.0), faceTolerance = 0.0), "along the wall's face")
        assertTrue(WallOcclusion(footprints = emptyList()).isEmpty)
    }

    @Test
    @DisplayName("Full up to 8 m, gone at 12 m; hidden behind a wall")
    fun `Full up to 8 m, gone at 12 m, hidden behind a wall`() {
        assertEquals(1.0, ProximityFade.opacity(distance = 3.0, behindWall = false))
        assertEquals(0.0, ProximityFade.opacity(distance = 3.0, behindWall = true))
        assertTrue(near(ProximityFade.opacity(distance = 10.0, behindWall = false), 0.5))
        assertEquals(0.0, ProximityFade.opacity(distance = 10.0, behindWall = true))
        assertEquals(0.0, ProximityFade.opacity(distance = 12.0, behindWall = false))
        assertEquals(0.0, ProximityFade.opacity(distance = 40.0, behindWall = true))
    }

    @Test
    @DisplayName("Hides what's behind the wall and far away; follows the camera through the passage")
    fun `Hides what's behind the wall and far away, follows the camera through the passage`() {
        val tracker = ProximityTracker()
        tracker.setWalls(mapOf("s1" to wall))
        val box = { x: Double, z: Double -> Bounds(min = Vec3(x - 0.05, 0.2, z - 0.05), max = Vec3(x + 0.05, 0.4, z + 0.05)) }
        tracker.setTarget("here", ProximityTracker.Target(bounds = box(-2.0, 2.0), storeyID = "s1"))
        tracker.setTarget("there", ProximityTracker.Target(bounds = box(2.0, 2.0), storeyID = "s1"))
        tracker.setTarget("far", ProximityTracker.Target(bounds = box(-2.0, -20.0), storeyID = "s1"))
        // A pipe from the next room into this one: its box reaches this side.
        tracker.setTarget(
            "pipe",
            ProximityTracker.Target(bounds = Bounds(min = Vec3(-1.5, 2.9, 1.0), max = Vec3(2.0, 2.95, 1.03)), storeyID = "s1"),
        )

        val first = tracker.update(camera = Vec3(-2.0, 1.6, 1.5)).asMap()
        assertEquals(mapOf("there" to 0.0, "far" to 0.0), first)
        assertTrue(tracker.opacity(of = "here") == 1.0 && tracker.opacity(of = "pipe") == 1.0)

        assertTrue(tracker.update(camera = Vec3(-2.1, 1.6, 1.5)).isEmpty(), "moved less than 30 cm")

        // Into the other room through the passage.
        val moved = tracker.update(camera = Vec3(2.0, 1.6, 1.5)).asMap()
        assertEquals(mapOf("here" to 0.0, "there" to 1.0), moved)

        // A room picked from the menu isn't greyed out for being behind a wall; distance still fades.
        tracker.ignoresWalls = true
        val picked = tracker.update(camera = Vec3(2.0, 1.6, 1.5)).asMap()
        assertEquals(mapOf("here" to 1.0), picked)
        tracker.ignoresWalls = false
        tracker.update(camera = Vec3(2.0, 1.6, 1.5))

        assertEquals(setOf("here", "far"), tracker.reset().toSet())
        assertEquals(1.0, tracker.opacity(of = "here"))
    }

    @Test
    fun `At most 48 changes a frame`() {
        val tracker = ProximityTracker()
        tracker.setWalls(mapOf("s1" to wall))
        for (index in 0 until 100) {
            val z = 0.6 + index * 0.02
            tracker.setTarget(
                "e$index",
                ProximityTracker.Target(bounds = Bounds(min = Vec3(1.5, 0.3, z), max = Vec3(1.6, 0.4, z + 0.01)), storeyID = "s1"),
            )
        }
        val camera = Vec3(-2.0, 1.6, 2.0)
        assertEquals(48, tracker.update(camera = camera).size)
        assertEquals(48, tracker.update(camera = camera).size)
        assertEquals(4, tracker.update(camera = camera).size)
        assertTrue(tracker.update(camera = camera).isEmpty())
    }

    @Test
    @DisplayName("The converter's test room: its four walls keep the camera's view inside")
    fun `The converter's test room - its four walls keep the camera's view inside`() {
        val meta = ChunkMeta.decode(Fixtures.data("test-room/architecture.meta.json"))
        val footprints = meta.elements.values.mapNotNull { it.footprint }
        assertEquals(4, footprints.size)
        val occlusion = WallOcclusion(footprints = footprints.flatten())
        assertTrue(occlusion.blocks(from = Vec2(0.0, 0.0), to = Vec2(0.0, 3.0)), "out through the south wall")
        assertFalse(occlusion.blocks(from = Vec2(0.0, 0.0), to = Vec2(1.0, 1.76)), "in the south wall, 1 cm deep")
        assertFalse(occlusion.blocks(from = Vec2(0.0, 0.0), to = Vec2(-1.5, -1.0)), "inside the room")
    }

    @Test
    fun `Chunk meta brings wall footprints`() {
        val json = """
            {"e1":{"k":"wall","f":[[[-2.15,1.75],[-2.15,1.9],[2.15,1.9],[2.15,1.75]],[[0,0],[1]]]},"e2":{"k":"outlet"}}
        """.trimIndent()
        val meta = ChunkMeta.decode(json)
        val footprint = assertNotNull(meta.elements["e1"]?.footprint)
        assertEquals(
            listOf(listOf(Vec2(-2.15, 1.75), Vec2(-2.15, 1.9), Vec2(2.15, 1.9), Vec2(2.15, 1.75))),
            footprint,
            "the broken polygon is left out",
        )
        assertNull(meta.elements["e2"]?.footprint)
    }
}
