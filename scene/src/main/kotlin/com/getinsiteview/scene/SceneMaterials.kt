package com.getinsiteview.scene

import com.getinsiteview.core.CatalogColor
import com.getinsiteview.modelkit.ModelFilters
import com.getinsiteview.modelkit.scene.GlbMaterial
import com.google.android.filament.Colors
import com.google.android.filament.MaterialInstance
import com.google.android.filament.gltfio.MaterialProvider
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setBaseColorFactor
import io.github.sceneview.material.setColor
import io.github.sceneview.material.setEmissiveFactor
import io.github.sceneview.material.setEmissiveStrength
import io.github.sceneview.material.setMetallicFactor
import io.github.sceneview.material.setRoughnessFactor
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
 * Main thread only. The instances live as long as the building's scene (a few per colour);
 * [destroy] frees them once nothing renders with them.
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
     * against any system colour and in a dark basement (iOS `PhysicallyBasedMaterial` with
     * `emissiveColor`). SceneView's colour materials have no emissive parameter, so this is
     * Filament's glTF material (the one gltfio uses for the chunks): base colour, roughness 0.4,
     * metallic 0, emissive factor and strength; blended below full opacity. If the device has no
     * such material, the plain colour material is used (no glow).
     */
    fun highlight(opacity: Double): MaterialInstance {
        val step = step(opacity)
        return highlights.getOrPut(step) {
            val base = Float4(srgbToLinear(0xE3), srgbToLinear(0x05), srgbToLinear(0x32), alpha(step))
            val key = MaterialProvider.MaterialKey().apply { alphaMode = if (step >= 100) ALPHA_OPAQUE else ALPHA_BLEND }
            loader.createUbershaderInstance(key, label = "highlight")?.also {
                it.setBaseColorFactor(base)
                it.setMetallicFactor(0.0f)
                it.setRoughnessFactor(0.4f)
                it.setEmissiveFactor(Float4(srgbToLinear(0xFF), srgbToLinear(0x3B), srgbToLinear(0x4E), 1.0f))
                it.setEmissiveStrength(EMISSIVE_INTENSITY * EMISSIVE_GAIN)
            } ?: loader.createColorInstance(
                Float4(0xE3 / 255f, 0x05 / 255f, 0x32 / 255f, alpha(step)),
                metallic = 0.0f,
                roughness = 0.4f,
            )
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

    /** Frees every instance made here (`BuildingScene.destroy`, after its renderables are gone). */
    fun destroy() {
        for (instance in surfaces.values + files.values + highlights.values) loader.destroyMaterialInstance(instance)
        surfaces.clear()
        files.clear()
        highlights.clear()
    }

    companion object {
        /** iOS `emissiveIntensity`. */
        const val EMISSIVE_INTENSITY = 0.8f

        /**
         * RealityKit's emissive intensity and Filament's emissive strength aren't the same unit
         * (Filament's is affected by the camera exposure): tuned side by side with an iPhone on a
         * device (docs/device-test.md, P2-02).
         */
        const val EMISSIVE_GAIN = 1.0f

        /** glTF alpha modes (cgltf): the material key's `alphaMode`. */
        private const val ALPHA_OPAQUE = 0
        private const val ALPHA_BLEND = 2

        /** An 8-bit sRGB channel as a linear value (glTF factors are linear). */
        internal fun srgbToLinear(channel: Int): Float {
            val c = channel / 255.0
            return (if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)).toFloat()
        }

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
