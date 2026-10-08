package com.getinsiteview.modelkit

/**
 * What loading a building has produced so far: the element index from every meta file that
 * arrived, the model files ready to show, and what failed. The 3D and AR screens apply
 * [ChunkEvent]s here and react to the returned [Change].
 *
 * iOS's is a value type; this one is updated in place by [apply]. Take a [copy] for a snapshot.
 */
class ModelLoadState {
    var index: ElementIndex = ElementIndex()
        private set
    var progress: LoadProgress = LoadProgress(completedBytes = 0, totalBytes = 0, completedFiles = 0, totalFiles = 0)
        private set

    private val _models = LinkedHashMap<String, LoadedChunkFile>()
    private val _failures = LinkedHashMap<String, ChunkLoadError>()

    /** Chunk key → verified model file (GLB on Android). */
    val models: Map<String, LoadedChunkFile> get() = _models

    /** Request id (`electrical.glb`) → why it failed. */
    val failures: Map<String, ChunkLoadError> get() = _failures

    sealed interface Change {
        data class Progress(val progress: LoadProgress) : Change

        /** A chunk's meta is in the index. */
        data class Indexed(val chunk: String) : Change

        /** A chunk's model file is ready to load into the scene. */
        data class ModelReady(val file: LoadedChunkFile) : Change

        data class Failed(val request: ChunkRequest, val error: ChunkLoadError) : Change
    }

    /** An independent copy (the index included). */
    fun copy(): ModelLoadState {
        val copy = ModelLoadState()
        copy.index = index.copy()
        copy.progress = progress
        copy._models.putAll(_models)
        copy._failures.putAll(_failures)
        return copy
    }

    /** Chunk keys whose model or meta failed. */
    val failedChunks: Set<String>
        get() = _failures.keys.mapNotNull { key -> key.split('.').firstOrNull { it.isNotEmpty() } }.toSet()

    /**
     * Applies one downloader event. Reading or decoding a meta file that fails is recorded as a
     * failure of that file.
     */
    fun apply(event: ChunkEvent): Change = when (event) {
        is ChunkEvent.Progress -> {
            progress = event.progress
            Change.Progress(event.progress)
        }
        is ChunkEvent.Failed -> {
            _failures[event.request.id] = event.error
            Change.Failed(event.request, event.error)
        }
        is ChunkEvent.Loaded -> {
            val file = event.file
            _failures.remove(file.request.id)
            when (file.request.kind) {
                ChunkFileKind.META -> try {
                    val meta = ChunkMeta.decode(file.url.readBytes())
                    index.add(meta, file.request.chunk, file.request.system)
                    Change.Indexed(file.request.chunk)
                } catch (e: Exception) {
                    val failure = ChunkLoadError.Storage("Unreadable meta: $e")
                    _failures[file.request.id] = failure
                    Change.Failed(file.request, failure)
                }
                ChunkFileKind.USDZ, ChunkFileKind.GLB -> {
                    _models[file.request.chunk] = file
                    Change.ModelReady(file)
                }
            }
        }
    }
}
