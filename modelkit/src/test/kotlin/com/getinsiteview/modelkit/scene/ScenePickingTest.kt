package com.getinsiteview.modelkit.scene

import com.getinsiteview.modelkit.geometry.Bounds
import com.getinsiteview.modelkit.geometry.OrbitCamera
import com.getinsiteview.modelkit.geometry.PickingShape
import com.getinsiteview.modelkit.geometry.Vec3
import com.getinsiteview.modelkit.geometry.Vector
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

private fun near(a: Vec3, b: Vec3, tolerance: Double = 1e-9) = Vector.distance(a, b) <= tolerance

@DisplayName("Column-major 4×4 matrices")
class Matrix4Test {
    private val turned = Matrix4.fromColumns(
        x = Vec3(cos(0.5), 0.0, -sin(0.5)), y = Vec3(0.0, 1.0, 0.0), z = Vec3(sin(0.5), 0.0, cos(0.5)), translation = Vec3(1.0, 2.0, 3.0),
    )

    @Test
    @DisplayName("Points move, directions only turn")
    fun `Points move, directions only turn`() {
        assertTrue(near(Matrix4.transformPoint(turned, Vec3.zero), Vec3(1.0, 2.0, 3.0)))
        assertTrue(near(Matrix4.transformDirection(turned, Vec3(0.0, 0.0, 1.0)), Vec3(sin(0.5), 0.0, cos(0.5))))
        assertEquals(1.0, Matrix4.get(turned, 0, 3))
        assertEquals(Vec3(1.0, 2.0, 3.0), Matrix4.column(turned, 3))
    }

    @Test
    @DisplayName("The inverse undoes it, products apply right to left")
    fun `The inverse undoes it, products apply right to left`() {
        val inverse = assertNotNull(Matrix4.invert(turned))
        val product = Matrix4.multiply(inverse, turned)
        for (i in 0 until 16) assertTrue(abs(product[i] - Matrix4.identity[i]) < 1e-12, "element $i: ${product[i]}")
        val scale = Matrix4.fromColumns(Vec3(2.0, 0.0, 0.0), Vec3(0.0, 2.0, 0.0), Vec3(0.0, 0.0, 2.0), Vec3.zero)
        // Scale first, then the turn and the move.
        val p = Matrix4.transformPoint(Matrix4.multiply(turned, scale), Vec3(1.0, 0.0, 0.0))
        assertTrue(near(p, Matrix4.transformPoint(turned, Vec3(2.0, 0.0, 0.0))))
        assertNull(Matrix4.invert(DoubleArray(16)))
        val floats = Matrix4.toFloats(turned)
        assertTrue(Matrix4.of(floats).zip(turned.toList()).all { (a, b) -> abs(a - b) < 1e-6 })
    }
}

@DisplayName("Picking elements")
class ScenePickingTest {
    private val box = Bounds(min = Vec3(-1.0, -1.0, -1.0), max = Vec3(1.0, 1.0, 1.0))

    @Test
    @DisplayName("A ray meets a box where it enters it, or at once from inside")
    fun `A ray meets a box where it enters it, or at once from inside`() {
        assertEquals(4.0, ScenePicking.intersect(Vec3(0.0, 0.0, 5.0), Vec3(0.0, 0.0, -1.0), box))
        assertNull(ScenePicking.intersect(Vec3(0.0, 0.0, 5.0), Vec3(0.0, 0.0, 1.0), box), "pointing away")
        assertNull(ScenePicking.intersect(Vec3(3.0, 0.0, 5.0), Vec3(0.0, 0.0, -1.0), box), "passes beside")
        assertEquals(0.0, ScenePicking.intersect(Vec3.zero, Vec3(1.0, 0.0, 0.0), box))
        assertEquals(2.0, ScenePicking.intersect(Vec3(0.0, 0.0, 5.0), Vec3(0.0, 0.0, -2.0), box), "in the direction's units")
    }

    @Test
    @DisplayName("The nearest element wins, each in its own frame")
    fun `The nearest element wins, each in its own frame`() {
        val near = ScenePicking.Target("near", box, Matrix4.invert(Matrix4.fromColumns(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0), Vec3(0.0, 0.0, 2.0)))!!)
        val far = ScenePicking.Target("far", box, Matrix4.identity)
        val beside = ScenePicking.Target("beside", box, Matrix4.invert(Matrix4.fromColumns(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0), Vec3(5.0, 0.0, 0.0)))!!)
        assertEquals("near", ScenePicking.pick(Vec3(0.0, 0.0, 10.0), Vec3(0.0, 0.0, -1.0), listOf(far, beside, near)))
        assertEquals("beside", ScenePicking.pick(Vec3(5.0, 0.0, 10.0), Vec3(0.0, 0.0, -1.0), listOf(far, beside, near)))
        assertNull(ScenePicking.pick(Vec3(0.0, 5.0, 10.0), Vec3(0.0, 0.0, -1.0), listOf(far, beside, near)))
        // A scaled element frame: parameters still compare in model units.
        val scaled = ScenePicking.Target("scaled", box, Matrix4.invert(Matrix4.fromColumns(Vec3(0.5, 0.0, 0.0), Vec3(0.0, 0.5, 0.0), Vec3(0.0, 0.0, 0.5), Vec3(0.0, 0.0, 1.2)))!!)
        assertEquals("scaled", ScenePicking.pick(Vec3(0.0, 0.0, 10.0), Vec3(0.0, 0.0, -1.0), listOf(far, scaled)))
    }

    @Test
    @DisplayName("Thin conduits are grown, a long diagonal run falls back to its grown box")
    fun `Thin conduits are grown, a long diagonal run falls back to its grown box`() {
        val conduit = Bounds(min = Vec3(0.0, 0.0, 0.0), max = Vec3(3.0, 0.016, 0.016))
        val picked = ScenePicking.pickingBox(conduit, isSystemElement = true)
        assertEquals(PickingShape.MINIMUM_SIZE, picked.extents.y, 1e-12)
        assertEquals(3.0, picked.extents.x, 1e-12)
        assertTrue(near(picked.center, conduit.center))
        val diagonal = Bounds(min = Vec3(0.0, 0.0, 0.0), max = Vec3(3.0, 3.0, 0.02))
        val hull = ScenePicking.pickingBox(diagonal, isSystemElement = true)
        assertEquals(PickingShape.MINIMUM_SIZE, hull.extents.z, 1e-12)
        assertEquals(3.0, hull.extents.x, 1e-12)
    }

    @Test
    @DisplayName("A box carried into another frame keeps its corners inside")
    fun `A box carried into another frame keeps its corners inside`() {
        val turned = Matrix4.fromColumns(Vec3(0.0, 0.0, -1.0), Vec3(0.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(10.0, 0.0, 0.0))
        val moved = ScenePicking.transformedBounds(Bounds(Vec3(0.0, 0.0, 0.0), Vec3(2.0, 1.0, 1.0)), turned)
        assertTrue(near(moved.min, Vec3(10.0, 0.0, -2.0)) && near(moved.max, Vec3(11.0, 1.0, 0.0)), "$moved")
    }

    @Test
    @DisplayName("The viewer's ray through the middle is the camera's line of sight")
    fun `The viewer's ray through the middle is the camera's line of sight`() {
        val camera = OrbitCamera(target = Vec3(1.0, 0.0, 0.0), distance = 5.0)
        val fov = 55 * PI / 180
        val (origin, direction) = assertNotNull(ScenePicking.orbitRay(camera, fov, 500.0, 1000.0, 1000.0, 2000.0))
        assertTrue(near(origin, camera.position))
        assertTrue(near(direction, camera.forward, 1e-12))
        // The top edge is half the vertical field of view up.
        val (_, top) = assertNotNull(ScenePicking.orbitRay(camera, fov, 500.0, 0.0, 1000.0, 2000.0))
        assertEquals(cos(fov / 2), Vector.dot(top, camera.forward), 1e-9)
        assertTrue(Vector.dot(top, camera.up) > 0)
        assertNull(ScenePicking.orbitRay(camera, fov, 0.0, 0.0, 0.0, 0.0))
    }
}

@DisplayName("A GLB's materials")
class GlbMaterialsTest {
    private fun glb(json: String): ByteArray {
        val padded = json.toByteArray().let { bytes -> bytes + ByteArray((4 - bytes.size % 4) % 4) { ' '.code.toByte() } }
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0x46546C67).putInt(2).putInt(20 + padded.size).putInt(padded.size).putInt(0x4E4F534A)
        out.write(header.array())
        out.write(padded)
        return out.toByteArray()
    }

    @Test
    @DisplayName("Names, base colours and factors, with glTF's defaults")
    fun `Names, base colours and factors, with glTF's defaults`() {
        val materials = GlbMaterials.read(
            glb(
                """{"asset":{"version":"2.0"},"materials":[
                {"name":"Concrete","pbrMetallicRoughness":{"baseColorFactor":[0.5,0.5,0.4,1],"metallicFactor":0,"roughnessFactor":0.9}},
                {"pbrMetallicRoughness":{}}]}""",
            ),
        )
        assertEquals(2, materials.size)
        assertEquals(GlbMaterial("Concrete", listOf(0.5, 0.5, 0.4, 1.0), 0.0, 0.9), materials[0])
        assertEquals(GlbMaterial(null, listOf(1.0, 1.0, 1.0, 1.0), 1.0, 1.0), materials[1])
        assertEquals("Concrete", GlbMaterials.lookup(materials, "Concrete")?.name)
        assertNull(GlbMaterials.lookup(materials, "Glass"), "unknown, and more than one")
        assertEquals(materials[0], GlbMaterials.lookup(materials.take(1), null), "the only one")
    }

    @Test
    @DisplayName("Not a GLB: no materials")
    fun `Not a GLB - no materials`() {
        assertTrue(GlbMaterials.read(ByteArray(8)).isEmpty())
        assertTrue(GlbMaterials.read("{\"materials\":[]}".toByteArray() + ByteArray(20)).isEmpty())
        assertTrue(GlbMaterials.read(glb("""{"asset":{"version":"2.0"}}""")).isEmpty())
    }
}
