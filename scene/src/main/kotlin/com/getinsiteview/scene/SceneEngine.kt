package com.getinsiteview.scene

import android.content.Context
import android.opengl.EGLContext
import com.google.android.filament.Engine
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader

/**
 * The one Filament engine of the app, and the loaders that make what lives on it (docs/PLAN.md §3
 * "Loading a building" and "AR": one `BuildingScene` moves between the 3D viewer and AR, as iOS
 * moves one `BuildingRoot`). Nodes can only move between views that render with the same engine,
 * so every `SceneView` and `ARSceneView` of the app is given this one; it is never destroyed.
 *
 * Main thread only: Filament's API isn't thread-safe and SceneView drives it from the main thread.
 */
object SceneEngine {
    private var eglContext: EGLContext? = null
    private var engineInstance: Engine? = null
    private var models: ModelLoader? = null
    private var materials: MaterialLoader? = null

    /** Created on first use. */
    val engine: Engine
        get() = engineInstance ?: run {
            val context = createEglContext()
            eglContext = context
            createEngine(context).also { engineInstance = it }
        }

    /**
     * Loads the building's GLB chunks. Shared with the views (their render loops pump its texture
     * loads) and kept for the app's life: a view's own loader destroys its models when it goes.
     */
    fun modelLoader(context: Context): ModelLoader =
        models ?: ModelLoader(engine, context.applicationContext).also { models = it }

    /** Catalog colours, the highlight, faded copies and the AR markers. Kept for the app's life. */
    fun materialLoader(context: Context): MaterialLoader =
        materials ?: MaterialLoader(engine, context.applicationContext).also { materials = it }
}
