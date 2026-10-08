@file:OptIn(ExperimentalMaterial3Api::class)

package com.getinsiteview.features.objectcard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getinsiteview.api.ElementDetail
import com.getinsiteview.api.buildings.DocumentItem
import com.getinsiteview.core.CatalogColor
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.features.R
import com.getinsiteview.features.building.BuildingSession
import com.getinsiteview.features.documents.DocumentFailureDialog
import com.getinsiteview.features.documents.DocumentOpener
import com.getinsiteview.features.documents.DocumentRow
import com.getinsiteview.features.ui.ColorDot
import com.getinsiteview.features.ui.LabeledRow
import com.getinsiteview.features.ui.ListRow
import com.getinsiteview.features.ui.RowIcon
import com.getinsiteview.features.ui.SectionFooter
import com.getinsiteview.features.ui.SectionHeader
import com.getinsiteview.modelkit.ElementMeta
import com.getinsiteview.modelkit.ElementRecord
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * The object card (A-03): what the element is, where it is, its key properties and documents.
 * Meta from the chunk shows at once (it works offline); the full detail from the API adds
 * documents. Guests never see IFC terms (A-01): the IFC class, GlobalId and property sets appear
 * only under "Technical details" for members (IOS-M3-03; the API sends them to members only).
 */
class ObjectCardModel(val elementID: String, internal val session: BuildingSession) {
    var detail: ElementDetail? by mutableStateOf(null)
        private set
    var isLoadingDetail by mutableStateOf(false)
        private set

    suspend fun load() {
        if (detail != null) return
        isLoadingDetail = true
        try {
            detail = session.detail(elementID)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            isLoadingDetail = false
        }
    }

    private val record: ElementRecord? get() = session.index[elementID]

    val title: String
        get() {
            record?.let { return session.displayName(it) }
            val detail = detail ?: return ""
            val meta = ElementMeta(kind = detail.kind, name = detail.name, tag = detail.tag)
            return session.displayName(ElementRecord(detail.id, chunk = detail.system, system = detail.system, meta = meta))
        }

    val system: String? get() = record?.system ?: detail?.system

    val subsystem: String? get() = record?.subsystem ?: detail?.subsystem

    val colorKey: CatalogColor
        get() {
            val system = system ?: return CatalogColor.FALLBACK
            return session.catalog?.color(system, subsystem) ?: session.systemColor(system)
        }

    /** "Electrical · Power". */
    val systemLine: String?
        get() {
            val system = system ?: return null
            val parts = mutableListOf(session.systemName(system))
            val subsystem = subsystem
            val catalog = session.catalog
            if (subsystem != null && catalog != null) {
                parts += catalog.subsystemName(subsystem, system, session.language)
            }
            return parts.joinToString(" · ")
        }

    /** The manifest names rooms, so this works offline; the detail covers a missing meta. */
    val roomName: String? get() = session.roomName(record?.roomID) ?: detail?.space?.displayName

    val levelName: String? get() = session.storeyName(record?.storeyID) ?: detail?.storey?.name

    /** From meta (offline); from the detail when meta has none. */
    val keyProperties: List<ElementRecord.KeyProperty>
        get() {
            val record = record
            if (record != null && record.meta.keyProperties.isNotEmpty()) {
                return record.keyProperties(session.catalog, session.language)
            }
            val detail = detail ?: return emptyList()
            val meta = ElementMeta(kind = detail.kind, keyProperties = detail.keyProperties)
            return ElementRecord(detail.id, chunk = detail.system, system = detail.system, meta = meta)
                .keyProperties(session.catalog, session.language)
        }

    /** Access ended mid-session: the card closes and the screen below shows why. */
    val accessEnded: Boolean get() = session.accessProblem != null

    /** Documents linked to the element (from the detail; meta only counts them offline). */
    val documents: List<DocumentItem> get() = (detail?.documents ?: emptyList()).map(DocumentItem::of)

    val documentCount: Int get() = maxOf(record?.meta?.documentCount ?: 0, detail?.documents?.size ?: 0)

    /** Members only: the IFC class, GlobalId and property sets. */
    val technicalDetails: TechnicalDetails? get() = TechnicalDetails.of(session.isMember, detail)
}

/** What the card offers besides its details, depending on where it opened. */
data class ObjectCardActions(
    /**
     * "Locate in AR" (from a list or the 3D viewer), or "Locate" inside AR: pulse, arrow and
     * distance (IOS-M3-05).
     */
    val locate: ((String) -> Unit)? = null,
    /** Inside AR the button says "Locate"; elsewhere "Locate in AR". */
    val locatesInPlace: Boolean = false,
    /** "Show in 3D": the 3D viewer framed on the element. */
    val showIn3D: ((String) -> Unit)? = null,
)

/**
 * The card's content. [dismiss] closes the sheet (the actions run after it closed, see
 * [ObjectCardSheet]).
 */
@Composable
fun ObjectCardView(model: ObjectCardModel, actions: ObjectCardActions = ObjectCardActions(), dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val opener = remember { DocumentOpener() }
    LaunchedEffect(model) { model.load() }
    LaunchedEffect(model.accessEnded) {
        if (model.accessEnded) dismiss()
    }
    var technicalOpen by remember { mutableStateOf(false) }
    var openSets by remember { mutableStateOf(setOf<String>()) }

    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.padding(top = 8.dp)) { ColorDot(model.colorKey, 12) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(model.title, style = IvType.body(22.sp, FontWeight.Bold), color = Palette.ink)
                    model.systemLine?.let { Text(it, style = IvType.body(15.sp), color = Palette.muted) }
                }
            }
        }

        val locate = actions.locate
        val showIn3D = actions.showIn3D
        if (locate != null || showIn3D != null) {
            item { SectionHeader("") }
            if (locate != null) {
                item {
                    ListRow(onClick = {
                        locate(model.elementID)
                        dismiss()
                    }) {
                        if (actions.locatesInPlace) {
                            RowIcon(Icons.Outlined.GpsFixed)
                            Text(stringResource(R.string.locate), style = IvType.body(), color = Palette.accent)
                        } else {
                            RowIcon(Icons.Outlined.ViewInAr)
                            Text(stringResource(R.string.locate_in_ar), style = IvType.body(), color = Palette.accent)
                        }
                    }
                }
            }
            if (showIn3D != null) {
                item {
                    ListRow(onClick = {
                        showIn3D(model.elementID)
                        dismiss()
                    }) {
                        RowIcon(Icons.Outlined.ViewInAr)
                        Text(stringResource(R.string.show_in_3d), style = IvType.body(), color = Palette.accent)
                    }
                }
            }
        }

        item { SectionHeader("") }
        item {
            LabeledRow(stringResource(R.string.room)) {
                val room = model.roomName
                when {
                    room != null -> Text(room, style = IvType.body(), color = Palette.muted)
                    model.isLoadingDetail -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else -> Text(stringResource(R.string.not_in_a_room), style = IvType.body(), color = Palette.muted)
                }
            }
        }
        model.levelName?.let { level ->
            item { LabeledRow(stringResource(R.string.level)) { Text(level, style = IvType.body(), color = Palette.muted) } }
        }

        val properties = model.keyProperties
        if (properties.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.details)) }
            for (property in properties) {
                item {
                    LabeledRow(property.label) { Text(property.value, style = IvType.body(), color = Palette.muted) }
                }
            }
        }

        val documents = model.documents
        if (documents.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.documents)) }
            for (document in documents) {
                item {
                    DocumentRow(document, isOpening = opener.opening == document.id) {
                        scope.launch { opener.open(document, model.session, context) }
                    }
                }
            }
        } else if (model.documentCount > 0) {
            item { SectionHeader("") }
            item {
                ListRow {
                    RowIcon(Icons.Outlined.Description, tint = Palette.muted)
                    Text(
                        if (model.isLoadingDetail) {
                            pluralStringResource(R.plurals.n_document, model.documentCount, model.documentCount)
                        } else {
                            // Counted in the offline copy, but the list needs a connection.
                            stringResource(R.string.connect_to_the_internet_to_open_the_documents)
                        },
                        style = IvType.body(),
                        color = Palette.muted,
                    )
                }
            }
        }

        item { SectionHeader("") }
        item {
            ListRow(Modifier.alpha(0.5f), onClick = {}, enabled = false) {
                RowIcon(Icons.Outlined.Route, tint = Palette.muted)
                Column {
                    Text(stringResource(R.string.follow_circuit), style = IvType.body(), color = Palette.ink)
                    Text(stringResource(R.string.coming_soon), style = IvType.body(12.sp), color = Palette.muted)
                }
            }
        }

        val technical = model.technicalDetails
        if (technical != null) {
            item { SectionHeader("") }
            item {
                ListRow(onClick = { technicalOpen = !technicalOpen }) {
                    RowIcon(Icons.Outlined.Info)
                    Text(stringResource(R.string.technical_details), style = IvType.body(), color = Palette.ink, modifier = Modifier.weight(1f))
                    Icon(if (technicalOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = Palette.muted)
                }
            }
            if (technicalOpen) {
                technical.ifcClass?.let { ifcClass ->
                    item { LabeledRow(stringResource(R.string.ifc_class)) { Text(ifcClass, style = IvType.body(), color = Palette.muted) } }
                }
                technical.globalID?.let { globalID ->
                    item {
                        LabeledRow(stringResource(R.string.globalid)) {
                            SelectionContainer { Text(globalID, style = IvType.mono(12.sp), color = Palette.muted) }
                        }
                    }
                }
                for (set in technical.propertySets) {
                    item {
                        val open = set.id in openSets
                        ListRow(onClick = { openSets = if (open) openSets - set.id else openSets + set.id }) {
                            Text(set.name, style = IvType.body(), color = Palette.ink, modifier = Modifier.weight(1f))
                            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = Palette.muted)
                        }
                    }
                    if (set.id in openSets) {
                        for (property in set.properties) {
                            item {
                                LabeledRow(property.name, Modifier.padding(start = 16.dp)) {
                                    SelectionContainer { Text(property.value, style = IvType.body(), color = Palette.muted) }
                                }
                            }
                        }
                    }
                }
            }
            item { SectionFooter(stringResource(R.string.only_members_of_the_building_s_company_see_these)) }
        }
        item { Row(Modifier.padding(bottom = 24.dp)) {} }
    }

    DocumentFailureDialog(opener)
}

/** Where the object card opens, which decides its actions. */
enum class ObjectCardContext {
    /** BuildingHome, the landing, search results: "Locate in AR" and "Show in 3D". */
    LIST,

    /** The 3D viewer: "Locate in AR". */
    VIEWER,

    /** AR: "Locate" (pulse and arrow in place). */
    AR,
}

private sealed interface Pending {
    data class LocateInAR(val id: String) : Pending

    data class ShowIn3D(val id: String) : Pending
}

/**
 * The object card as a sheet for [selection] (an element id; `null`: closed), with the actions
 * that fit [context]. "Locate in AR" and "Show in 3D" run once the card has closed, so AR or the
 * viewer can be pushed (iOS `.objectCardSheet(...)`).
 */
@Composable
fun ObjectCardSheet(
    selection: String?,
    onSelectionChange: (String?) -> Unit,
    session: BuildingSession,
    context: ObjectCardContext,
    onLocateInAR: (() -> Unit)? = null,
    onShowIn3D: ((String) -> Unit)? = null,
) {
    LaunchedEffect(selection) {
        if (context != ObjectCardContext.LIST) {
            // In AR, the element being located stays highlighted once its card closes.
            session.select(selection ?: if (context == ObjectCardContext.AR) session.locating else null)
        }
        if (selection != null) session.opened(selection)
    }
    if (selection == null) return

    val scope = rememberCoroutineScope()
    val sheetState: SheetState = rememberModalBottomSheetState()
    var pending by remember { mutableStateOf<Pending?>(null) }

    fun runPending() {
        when (val action = pending) {
            is Pending.LocateInAR -> {
                session.locate(action.id)
                onLocateInAR?.invoke()
            }
            is Pending.ShowIn3D -> onShowIn3D?.invoke(action.id)
            null -> Unit
        }
        pending = null
    }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onSelectionChange(null)
            runPending()
        }
    }

    val locateInAR: ((String) -> Unit)? = if (onLocateInAR == null) null else { id -> pending = Pending.LocateInAR(id) }
    val actions = when (context) {
        ObjectCardContext.AR -> ObjectCardActions(locate = { id -> session.locate(id) }, locatesInPlace = true)
        ObjectCardContext.VIEWER -> ObjectCardActions(locate = locateInAR)
        ObjectCardContext.LIST -> ObjectCardActions(
            locate = locateInAR,
            showIn3D = if (onShowIn3D == null) null else { id -> pending = Pending.ShowIn3D(id) },
        )
    }
    val model = remember(selection) { ObjectCardModel(selection, session) }
    ModalBottomSheet(
        onDismissRequest = {
            onSelectionChange(null)
            runPending()
        },
        sheetState = sheetState,
        containerColor = Palette.background,
    ) {
        ObjectCardView(model, actions, dismiss = ::close)
    }
}
