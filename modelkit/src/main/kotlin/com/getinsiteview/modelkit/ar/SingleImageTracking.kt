package com.getinsiteview.modelkit.ar

/**
 * One plate image at a time, as ARKit with `maximumNumberOfTrackedImages = 1` (iOS
 * `ARAlignmentView`): ARCore may track several images at once, so this picks the one whose poses
 * feed the alignment. The image already followed stays while ARCore still fully tracks it; when it
 * doesn't, the fully tracked image nearest the camera takes over.
 */
object SingleImageTracking {
    /**
     * @param current the image followed so far, if any.
     * @param fullyTracked every image ARCore fully tracks in this frame, with its distance to the
     *   camera (metres).
     * @return the image to follow now; `null` when none is tracked.
     */
    fun choose(current: String?, fullyTracked: Map<String, Double>): String? {
        if (current != null && current in fullyTracked) return current
        return fullyTracked.minByOrNull { it.value }?.key
    }
}
