package com.getinsiteview.scene

import com.getinsiteview.core.CatalogColor
import com.getinsiteview.modelkit.ModelFilters
import com.getinsiteview.modelkit.scene.GlbMaterial
import com.google.android.filament.Colors
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setColor
import kotlin.math.roundToInt

/**
 * Materials from catalog colours (master PLAN §9 "Materials": one PBR material per system or
 * subsystem colour, roughness 0.6, metallic 0), cached per colour, plus the selection highlight.
 *
 * Fading (See inside, behind a wall): iOS multiplies an `OpacityComponent` into any material.
 * Filament can't blend an opaque material, so each colour has a transparent copy per opacity step
 * (hundredths), from SceneView's `transparent_colored` material; a chunk that keeps its file colours
 * (architecture, structure, meshes outside elements) fades to transparent copies of its glTF
 * colours ([GlbMaterial]). At full opacity the opaque material (or the file's own) is used again.
 *
 * Main thread only. The instances live for the app's life (a few per colour).
 */
internal class SceneMaterials(private val loader: MaterialLoader) {
    private val surfaces = HashMap<Pair<CatalogColor, Int>, MaterialInstance>()
    private val files = HashMap<Pair<List<Double>?, Int>, MaterialInstance>()
    private val highlights = HashMap<Int, MaterialInstance>()

    /** The catalog colour (sRGB) at [opacity]. */
    fun surface(color: CatalogColor, opacity: Double): MaterialInstance {
        val step = step(opacity)
        return surfaces.getOrPut(color to step) {
            loader.createColorInstance(
                Float4(color.red.toFloat(), color.green.toFloat(), color.blue.toFloat(), alpha(step)),
                metallic = 0.0f,
                roughness = 0.6f,
            )
        }
    }

    /**
     * The brand accent (`--accent`, #E30532) glowing a little (#FF3B4E × 0.8), so the selection reads
     * against any system colour and in a dark basement. The glow is set when the material has an
     * emissive parameter.
     */
    fun highlight(opacity: Double): MaterialInstance {
        val step = step(opacity)
        return highlights.getOrPut(step) {
            loader.createColorInstance(
                Float4(0xE3 / 255f, 0x05 / 255f, 0x32 / 255f, alpha(step)),
                metallic = 0.0f,
                roughness = 0.4f,
            ).also { setEmissive(it, 0xFF / 255f, 0x3B / 255f, 0x4E / 255f, 0.8f) }
        }
    }

    /** A file colour (glTF, linear) at [opacity] < 1; neutral grey when the material isn't known. */
    fun faded(material: GlbMaterial?, opacity: Double): MaterialInstance {
        val step = step(opacity)
        val color = material?.baseColor
        return files.getOrPut(color to step) {
            val linear = color ?: listOf(0.6, 0.6, 0.6, 1.0)
            val alpha = (linear.getOrElse(3) { 1.0 } * alpha(step)).toFloat()
            loader.createColorInstance(
                Float4(linear[0].toFloat(), linear[1].toFloat(), linear[2].toFloat(), minOf(alpha, 0.999f)),
                metallic = (material?.metallic ?: 0.0).toFloat(),
                roughness = (material?.roughness ?: 0.8).toFloat(),
            ).also {
                // glTF colours are linear; the loader took them as sRGB.
                it.setColor(Float4(linear[0].toFloat(), linear[1].toFloat(), linear[2].toFloat(), minOf(alpha, 0.999f)), Colors.RgbaType.LINEAR)
            }
        }
    }

    private fun setEmissive(instance: MaterialInstance, r: Float, g: Float, b: Float, strength: Float) {
        val parameter = instance.material.parameters.firstOrNull { it.name == "emissive" } ?: return
        when (parameter.type) {
            Material.Parameter.Type.FLOAT4 -> instance.setParameter("emissive", r * strength, g * strength, b * strength, 1.0f)
            Material.Parameter.Type.FLOAT3 -> instance.setParameter("emissive", r * strength, g * strength, b * strength)
            else -> Unit
        }
    }

    companion object {
        /**
         * Architecture and structure keep the colours in the file (IFC surface colours); systems
         * are recoloured from the catalog so colour changes don't need reprocessing.
         */
        fun isRecoloured(system: String): Boolean = !ModelFilters.isContext(system)

        /** Opacity in hundredths; 100 is opaque. */
        fun step(opacity: Double): Int = if (opacity.isFinite()) (opacity.coerceIn(0.0, 1.0) * 100).roundToInt() else 100

        /** An opaque step gives alpha 1 (the opaque material); anything less, the transparent one. */
        private fun alpha(step: Int): Float = if (step >= 100) 1.0f else step / 100f
    }
}
