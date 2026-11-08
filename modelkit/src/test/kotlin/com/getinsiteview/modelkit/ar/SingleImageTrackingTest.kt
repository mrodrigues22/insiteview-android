package com.getinsiteview.modelkit.ar

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/** ARKit tracks one plate image at a time (`maximumNumberOfTrackedImages = 1`); Android follows the same one. */
class SingleImageTrackingTest {
    @Test
    fun `the nearest fully tracked plate is followed first`() {
        assertEquals("plate-2", SingleImageTracking.choose(null, mapOf("plate-1" to 3.0, "plate-2" to 1.2)))
    }

    @Test
    fun `the followed plate stays while it is tracked, even when another is nearer`() {
        assertEquals("plate-1", SingleImageTracking.choose("plate-1", mapOf("plate-1" to 3.0, "plate-2" to 1.2)))
    }

    @Test
    fun `another plate takes over when the followed one is lost`() {
        assertEquals("plate-2", SingleImageTracking.choose("plate-1", mapOf("plate-2" to 1.2)))
    }

    @Test
    fun `nothing tracked, nothing followed`() {
        assertNull(SingleImageTracking.choose("plate-1", emptyMap()))
        assertNull(SingleImageTracking.choose(null, emptyMap()))
    }
}
