package com.getinsiteview.core

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Runs a suspending block that never actually suspends (the in-memory stores complete
 * synchronously), so `:core` tests don't need kotlinx-coroutines.
 */
fun <T> blocking(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return checkNotNull(outcome) { "The block suspended." }.getOrThrow()
}
