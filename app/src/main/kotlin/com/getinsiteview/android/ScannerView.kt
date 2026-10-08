package com.getinsiteview.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.design.PrimaryActionButton
import com.getinsiteview.design.SecondaryActionButton
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.TimeSource
import com.getinsiteview.features.R as FeaturesR

/**
 * The in-app QR scanner (IOS-M1-05, iOS docs/PLAN.md §3 "Scan tab"): CameraX's preview with ML Kit
 * barcode scanning for QR codes (iOS: VisionKit's `DataScannerViewController`; docs/PLAN.md §6).
 * Only our URLs are accepted; anything else shows "This isn't an Insite View code" and scanning
 * goes on. The same payload is handled at most once every 2 s ([ScanDebouncer]); once a code is
 * accepted the scanner stops and the phone confirms with a haptic.
 *
 * Camera permission (Android has no "restricted"; docs/PLAN.md §3 "AR"): asked when the scanner
 * first shows; refused, it explains with iOS's "Scanning isn't available" and offers the system
 * prompt again while Android still shows it, then the app's settings.
 *
 * @param showsCancel a Cancel button (as a sheet); the Scan tab has none.
 * @param onScan returns whether the payload was an Insite View code.
 */
@Composable
fun ScannerView(
    modifier: Modifier = Modifier,
    showsCancel: Boolean = false,
    onCancel: () -> Unit = {},
    onScan: (String) -> Boolean,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    // Asked once per scanner; refusals after that go through the explanation's buttons.
    var asked by rememberSaveable { mutableStateOf(false) }
    var canAskAgain by remember { mutableStateOf(true) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        val activity = context.findActivity()
        canAskAgain = activity != null && activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
    // Back from the settings screen: the permission may have changed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = hasCameraPermission(context)
    }

    Box(modifier.fillMaxSize().background(if (granted) Color.Black else Palette.background)) {
        if (granted) {
            var showsForeignCode by remember { mutableStateOf(false) }
            CameraScanner(onPayload = { payload ->
                if (onScan(payload)) {
                    true
                } else {
                    showsForeignCode = true
                    false
                }
            })
            AnimatedContent(
                targetState = showsForeignCode,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 32.dp),
                label = "scanner hint",
            ) { foreign ->
                Text(
                    stringResource(
                        if (foreign) FeaturesR.string.this_isn_t_an_insite_view_code else FeaturesR.string.point_the_camera_at_the_insite_view_code,
                    ),
                    modifier = Modifier.background(Palette.surface.copy(alpha = 0.85f)).padding(12.dp),
                    style = IvType.body(16.sp, FontWeight.Medium),
                    color = Palette.ink,
                )
            }
        } else {
            ContentUnavailable(
                title = stringResource(FeaturesR.string.scanning_isn_t_available),
                description = stringResource(FeaturesR.string.allow_camera_access_in_settings_or_open_the_code_with_the_ca),
                icon = { UnavailableIcon(Icons.Outlined.NoPhotography) },
                actions = {
                    if (canAskAgain) {
                        PrimaryActionButton(stringResource(FeaturesR.string.allow_the_camera), onClick = {
                            launcher.launch(Manifest.permission.CAMERA)
                        })
                    }
                    SecondaryActionButton(stringResource(R.string.app_open_settings_android), onClick = {
                        WebPages.openAppSettings(context)
                    })
                },
            )
        }
        if (showsCancel) {
            IconButton(onClick = onCancel, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(FeaturesR.string.cancel), tint = if (granted) Color.White else Palette.ink)
            }
        }
    }
}

/**
 * The camera preview and QR analysis. [onPayload] returns whether the payload was accepted; the
 * analysis stops then.
 */
@Composable
private fun CameraScanner(onPayload: (String) -> Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val currentOnPayload by rememberUpdatedState(onPayload)
    val controller = remember { LifecycleCameraController(context) }
    val debouncer = remember {
        val start = TimeSource.Monotonic.markNow()
        ScanDebouncer(now = { start.elapsedNow() })
    }

    DisposableEffect(lifecycleOwner) {
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
        var accepted = false
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        controller.setImageAnalysisAnalyzer(
            executor,
            QrAnalyzer(scanner) { payloads ->
                // ML Kit's listeners run on the main thread.
                if (accepted) return@QrAnalyzer
                for (payload in payloads) {
                    if (!debouncer.shouldHandle(payload)) continue
                    if (currentOnPayload(payload)) {
                        accepted = true
                        controller.clearImageAnalysisAnalyzer()
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        return@QrAnalyzer
                    }
                }
            },
        )
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            scanner.close()
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                // Pinch to zoom and tap to focus come with the controller.
                this.controller = controller
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Feeds camera frames to ML Kit; [onPayloads] gets each frame's QR payloads (on the main thread). */
private class QrAnalyzer(
    private val scanner: BarcodeScanner,
    private val onPayloads: (List<String>) -> Unit,
) : ImageAnalysis.Analyzer {
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (media == null) {
            image.close()
            return
        }
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val payloads = barcodes.mapNotNull { it.rawValue }
                if (payloads.isNotEmpty()) onPayloads(payloads)
            }
            .addOnCompleteListener { image.close() }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
