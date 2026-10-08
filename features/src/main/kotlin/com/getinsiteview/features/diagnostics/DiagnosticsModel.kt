package com.getinsiteview.features.diagnostics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.getinsiteview.api.ApiClient
import com.getinsiteview.api.Readiness
import com.getinsiteview.core.AppConfiguration
import com.getinsiteview.core.DeepLink
import com.getinsiteview.core.Router
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

/**
 * State for the diagnostics screen. Calls `GET /health/ready`, the M0 exit check (IOS-M0-04).
 *
 * @param configuration the build's configuration, or why it couldn't be read (iOS `Result`).
 */
class DiagnosticsModel(val configuration: Result<AppConfiguration>) : ViewModel() {
    sealed interface Status {
        data object Checking : Status

        data class Finished(val readiness: Readiness) : Status

        data class Failed(val message: String) : Status
    }

    var status: Status by mutableStateOf(Status.Checking)
        private set

    /** The last URL the app was opened with, and what it routes to. */
    var openedURL: URI? by mutableStateOf(null)
        private set
    var deepLink: DeepLink? by mutableStateOf(null)
        private set

    suspend fun checkHealth() {
        val configuration = configuration.getOrElse { error ->
            status = Status.Failed(error.message ?: error.toString())
            return
        }
        status = Status.Checking
        status = try {
            Status.Finished(ApiClient(configuration.apiBaseURL).readiness())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Status.Failed(e.toString())
        }
    }

    fun open(url: URI) {
        val router = configuration.getOrNull()?.let { Router(it.webBaseURL) } ?: Router()
        openedURL = url
        deepLink = router.deepLink(url)
    }
}
