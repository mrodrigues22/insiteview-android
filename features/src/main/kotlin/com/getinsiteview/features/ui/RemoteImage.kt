package com.getinsiteview.features.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private val imageClient by lazy { OkHttpClient() }

/**
 * An image from a URL, filling its frame (iOS `AsyncImage` with `.scaledToFill()`): [placeholder]
 * until it loads, and when it can't. Used for the building's thumbnail on the landing. No cache
 * beyond OkHttp's connection reuse: the thumbnail shows only until the live model replaces it.
 */
@Composable
fun RemoteImage(url: URI, modifier: Modifier = Modifier, placeholder: @Composable () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, url) {
        value = withContext(Dispatchers.IO) {
            try {
                imageClient.newCall(Request.Builder().url(url.toString()).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    BitmapFactory.decodeStream(response.body.byteStream())?.asImageBitmap()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
    val image = bitmap
    if (image == null) {
        placeholder()
    } else {
        Image(image, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    }
}
