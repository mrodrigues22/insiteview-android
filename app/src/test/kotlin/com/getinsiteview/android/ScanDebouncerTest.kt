package com.getinsiteview.android

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class ScanDebouncerTest {
    private var time: Duration = 100.seconds
    private val debouncer = ScanDebouncer(now = { time })

    @Test
    fun `the same code is handled once every 2 s`() {
        assertTrue(debouncer.shouldHandle("https://getinsiteview.com/b/8K29X7"))
        time += 500.milliseconds
        assertFalse(debouncer.shouldHandle("https://getinsiteview.com/b/8K29X7"))
        time += 1499.milliseconds
        assertFalse(debouncer.shouldHandle("https://getinsiteview.com/b/8K29X7"))
        time += 1.milliseconds
        assertTrue(debouncer.shouldHandle("https://getinsiteview.com/b/8K29X7"))
    }

    @Test
    fun `a different code is handled at once`() {
        assertTrue(debouncer.shouldHandle("hello"))
        time += 100.milliseconds
        assertTrue(debouncer.shouldHandle("https://getinsiteview.com/b/8K29X7"))
        time += 100.milliseconds
        assertTrue(debouncer.shouldHandle("hello"))
    }

    @Test
    fun `a skipped repeat doesn't extend the window`() {
        assertTrue(debouncer.shouldHandle("hello"))
        time += 1.seconds
        assertFalse(debouncer.shouldHandle("hello"))
        time += 1.seconds
        assertTrue(debouncer.shouldHandle("hello"))
    }
}
