package com.getinsiteview.features.diagnostics

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.getinsiteview.ar.ARAlignmentScene
import com.getinsiteview.ar.ARAlignmentView
import com.getinsiteview.design.Palette
import com.getinsiteview.features.ar.IntHolder
import com.getinsiteview.features.ar.PointsCrosshair
import com.getinsiteview.modelkit.ar.CrosshairState
import com.getinsiteview.modelkit.ar.TrackingStatus
import com.getinsiteview.modelkit.geometry.MarkSpread
import com.getinsiteview.modelkit.geometry.Vec3
import com.getinsiteview.modelkit.geometry.Vector
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The device test of marking floor corners with the crosshair (iOS docs/spikes/reference-points.md,
 * Part 0 of alignment by reference points): how repeatable corner marks are on this phone, the span
 * between two corners against a tape measure, and how far tracking drifts in a minute. "Copy
 * results" puts JSON on the clipboard to paste back.
 *
 * A developer tool (debug builds, Profile tab), so its text is English and not in the resources.
 */
@Stable
class MarkingTestModel(context: Context) {
    @Serializable
    enum class Target(val raw: String) {
        @SerialName("Corner A")
        CORNER_A("Corner A"),

        @SerialName("Corner B")
        CORNER_B("Corner B"),

        @SerialName("Corner A again, after walking 60 s")
        DRIFT("Corner A again, after walking 60 s"),
        ;

        /** On the switch at the top of the panel. */
        val shortName: String
            get() = when (this) {
                CORNER_A -> "Corner A"
                CORNER_B -> "Corner B"
                DRIFT -> "A after 60 s"
            }

        /** What to do for this target. */
        val instruction: String
            get() = when (this) {
                CORNER_A -> "Mark corner A 3 to 5 times, walking a couple of metres away and back between marks."
                CORNER_B -> "Mark corner B 3 times. A↔B appears below: compare it with the tape."
                DRIFT -> "Walk around the room for a minute, then mark corner A again. Drift appears below."
            }
    }

    /** Keys in alphabetical order, as iOS's `.sortedKeys` writes them. */
    @Serializable
    data class Entry(
        /** World metres; `null` when the aim or tracking didn't hold. */
        val position: List<Double>?,
        val seconds: Double,
        val target: Target,
        val tracking: String,
    )

    @Serializable
    private data class Summary(
        val marks: Int,
        val maxSpreadMm: Double?,
        val misses: Int,
        val rmsSpreadMm: Double?,
        val target: String,
    )

    @Serializable
    private data class Report(
        val device: String,
        val driftMm: Double?,
        val entries: List<Entry>,
        val lidar: Boolean,
        val spanAToBMm: Double?,
        val summary: List<Summary>,
    )

    data class Row(val target: Target, val marks: Int, val misses: Int, val spread: MarkSpread?)

    var target: Target by mutableStateOf(Target.CORNER_A)
    var entries: List<Entry> by mutableStateOf(emptyList())
        private set
    var tracking: TrackingStatus by mutableStateOf(TrackingStatus.INITIALIZING)
        private set
    var crosshair: CrosshairState by mutableStateOf(CrosshairState.NO_FLOOR)
        private set
    var capturing: Boolean by mutableStateOf(false)
        private set
    var copied: Boolean by mutableStateOf(false)
        private set

    val view = ARAlignmentView(context.applicationContext)
    private val startedAt = System.nanoTime()

    fun start() {
        view.onTrackingChange = { tracking = it }
        view.onCrosshairChange = { crosshair = it }
        // Marking first, so the session starts with the marking configuration (one configure).
        // Floor corners only: this measures the floor crosshair.
        view.allowsWallAims = false
        view.startMarking()
        view.run()
    }

    fun stop() {
        view.stopMarking()
        view.pause()
        view.release()
    }

    suspend fun mark() {
        if (capturing || crosshair != CrosshairState.READY) return
        capturing = true
        val mark = view.markCorner()
        capturing = false
        entries = entries + Entry(
            position = mark?.let { listOf(it.position.x, it.position.y, it.position.z) },
            seconds = (System.nanoTime() - startedAt) / 1e9,
            target = target,
            tracking = tracking.swiftName,
        )
        copied = false
    }

    fun undo() {
        val last = entries.lastOrNull() ?: return
        entries = entries.dropLast(1)
        // A miss left no disc.
        if (last.position != null) view.removeLastMark()
    }

    // Results

    val rows: List<Row>
        get() = Target.entries.mapNotNull { target ->
            val matching = entries.filter { it.target == target }
            if (matching.isEmpty()) return@mapNotNull null
            val points = matching.mapNotNull { it.position }.map { Vec3(it[0], it[1], it[2]) }
            Row(target, marks = points.size, misses = matching.size - points.size, spread = MarkSpread.of(points))
        }

    private fun mean(target: Target): Vec3? =
        MarkSpread.of(entries.filter { it.target == target }.mapNotNull { it.position }.map { Vec3(it[0], it[1], it[2]) })?.mean

    /** Corner A to corner B, horizontally, to compare with the tape measure. */
    val span: Double?
        get() {
            val a = mean(Target.CORNER_A) ?: return null
            val b = mean(Target.CORNER_B) ?: return null
            return sqrt((a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z))
        }

    /** Corner A now against corner A at the start. */
    val drift: Double?
        get() {
            val a = mean(Target.CORNER_A) ?: return null
            val again = mean(Target.DRIFT) ?: return null
            return Vector.distance(a, again)
        }

    fun copyResults(context: Context) {
        val report = Report(
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            driftMm = drift?.let { millimetres(it) },
            entries = entries,
            lidar = ARAlignmentView.hasLiDAR,
            spanAToBMm = span?.let { millimetres(it) },
            summary = rows.map { row ->
                Summary(
                    marks = row.marks,
                    maxSpreadMm = row.spread?.let { millimetres(it.maximum) },
                    misses = row.misses,
                    rmsSpreadMm = row.spread?.let { millimetres(it.rms) },
                    target = row.target.raw,
                )
            },
        )
        val text = json.encodeToString(Report.serializer(), report)
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Marking test", text))
        copied = true
    }

    private fun millimetres(metres: Double): Double = (metres * 1000).roundToLong().toDouble()

    private companion object {
        val json = Json { prettyPrint = true }
    }
}

/**
 * The marking device test (debug builds, from the Profile tab): the AR camera with the floor
 * crosshair and a panel to mark corners A and B and copy the results.
 *
 * @param onClose closes it (iOS `dismiss`).
 */
@Composable
fun MarkingTestView(onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }
    if (!granted) {
        Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(16.dp)) {
            CloseButton(onClose)
            Text(
                "The marking test needs the camera.",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }
    MarkingTestContent(onClose)
}

@Composable
private fun MarkingTestContent(onClose: () -> Unit) {
    val context = LocalContext.current
    val model = remember { MarkingTestModel(context) }
    DisposableEffect(model) {
        model.start()
        onDispose { model.stop() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ARAlignmentScene(model.view, Modifier.fillMaxSize())
        PointsCrosshair(model.crosshair, model.capturing)
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CloseButton(onClose)
                Spacer(Modifier.weight(1f))
                Text(
                    "Tracking: ${model.tracking.swiftName}",
                    fontSize = 12.sp,
                    color = Palette.ink,
                    modifier = Modifier.background(Palette.surface.copy(alpha = 0.85f), RoundedCornerShape(50)).padding(8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Panel(model)
        }
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    Box(
        Modifier.size(44.dp).background(Palette.surface.copy(alpha = 0.85f), CircleShape).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Palette.ink)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Panel(model: MarkingTestModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // iOS `.sensoryFeedback(.impact, trigger: entries.count)`: on each change (a mark, a miss, an undo).
    val count = model.entries.size
    val lastCount = remember { IntHolder(count) }
    LaunchedEffect(count) {
        if (count != lastCount.value) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        lastCount.value = count
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.surface.copy(alpha = 0.95f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val targets = MarkingTestModel.Target.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            targets.forEachIndexed { index, target ->
                SegmentedButton(
                    selected = model.target == target,
                    onClick = { model.target = target },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = targets.size),
                ) {
                    Text(target.shortName, fontSize = 12.sp)
                }
            }
        }
        Text(
            model.target.instruction + " Point the phone down with the circle on the corner, and tap Mark.",
            fontSize = 13.sp,
            color = Palette.ink,
        )
        Column(Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in model.rows) MonoLine(line(row))
            model.span?.let { MonoLine("A↔B horizontal: ${millimetres(it)} (compare with the tape)") }
            model.drift?.let { MonoLine("Drift on corner A: ${millimetres(it)}") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { model.undo() }) { Text("Undo") }
            OutlinedButton(onClick = { model.copyResults(context) }) { Text(if (model.copied) "Copied" else "Copy results") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { scope.launch { model.mark() } },
                enabled = !model.capturing && model.crosshair == CrosshairState.READY,
                modifier = Modifier.widthIn(min = 80.dp),
            ) {
                Text(if (model.capturing) "Hold still…" else "Mark")
            }
        }
    }
}

@Composable
private fun MonoLine(text: String) {
    Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Palette.ink)
}

private fun line(row: MarkingTestModel.Row): String {
    val spread = row.spread?.let { "max ${millimetres(it.maximum)}, rms ${millimetres(it.rms)}" } ?: "no hits"
    return "${row.target.raw}: ${row.marks} marks, ${row.misses} misses, $spread"
}

private fun millimetres(metres: Double): String = "${(metres * 1000).roundToLong()} mm"

/** The status as iOS's `String(describing:)` writes it ("excessiveMotion"), so reports from both apps compare. */
internal val TrackingStatus.swiftName: String
    get() = name.lowercase().split('_').let { words -> words.first() + words.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } }
