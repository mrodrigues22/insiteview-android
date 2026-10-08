@file:OptIn(ExperimentalMaterial3Api::class)

package com.getinsiteview.features.documents

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Architecture
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.getinsiteview.api.DocumentKind
import com.getinsiteview.api.GuestProblem
import com.getinsiteview.api.buildings.DocumentItem
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.features.R
import com.getinsiteview.features.app.BuildingNavigator
import com.getinsiteview.features.building.BuildingSession
import com.getinsiteview.features.guest.AccessGate
import com.getinsiteview.features.guest.ProblemView
import com.getinsiteview.features.ui.IvTopBar
import com.getinsiteview.features.ui.ListRow
import com.getinsiteview.features.ui.SectionHeader
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * Opens a document in the phone's viewer app (IOS-M3-06; iOS previews it with QuickLook, Android
 * hands it over with `ACTION_VIEW` through the app's FileProvider, docs/PLAN.md §6): downloads it
 * to `cacheDir/documents` (a fresh link when the 15-minute one expired), then opens it. Shared by
 * the documents list and the object card.
 */
class DocumentOpener {
    /** The document being downloaded. */
    var opening: String? by mutableStateOf(null)
        private set

    /** "Couldn't open the document". */
    var showsFailure by mutableStateOf(false)

    suspend fun open(item: DocumentItem, session: BuildingSession, context: Context) {
        if (opening != null) return
        opening = item.id
        try {
            val file = session.open(item)
            if (!view(context, file, item.mime)) showsFailure = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Access ending is handled by the gate; anything else: "Couldn't open".
            if (!GuestProblem.from(e).endsAccess) showsFailure = true
        } finally {
            opening = null
        }
    }

    /** `ACTION_VIEW` with a read grant; false when no app can show it. */
    private fun view(context: Context, file: File, mime: String): Boolean {
        val uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime.substringBefore(';').trim().ifEmpty { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    companion object {
        /** The app's FileProvider authority is `${applicationId}.files` (app manifest). */
        const val FILE_PROVIDER_SUFFIX = ".files"
    }
}

/** "Couldn't open the document" · "Check your connection and try again." */
@Composable
fun DocumentFailureDialog(opener: DocumentOpener) {
    if (!opener.showsFailure) return
    AlertDialog(
        onDismissRequest = { opener.showsFailure = false },
        confirmButton = {
            TextButton(onClick = { opener.showsFailure = false }) { Text(stringResource(R.string.ok), color = Palette.accent) }
        },
        title = { Text(stringResource(R.string.couldn_t_open_the_document)) },
        text = { Text(stringResource(R.string.check_your_connection_and_try_again)) },
    )
}

/** One document: kind icon, title, where it belongs; a spinner while it downloads. */
@Composable
fun DocumentRow(
    item: DocumentItem,
    /** The element it belongs to ("Water heater"), when listed with the building's documents. */
    elementName: String? = null,
    isOpening: Boolean,
    open: () -> Unit,
) {
    ListRow(
        onClick = open,
        enabled = item.url != null,
        onClickLabel = stringResource(R.string.opens_the_document),
    ) {
        Icon(item.kind.icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, style = IvType.body(), color = Palette.ink)
            val kind = documentKindTitle(item.kind)
            Text(
                if (elementName != null) "$kind · $elementName" else kind,
                style = IvType.body(13.sp),
                color = Palette.muted,
            )
        }
        if (isOpening) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Palette.accent, strokeWidth = 2.dp)
        }
    }
}

/**
 * The building's documents (IOS-M3-06): manuals, warranties, certificates, … Guests see the ones
 * the company shared with them; members see every one.
 */
@Composable
fun DocumentsView(session: BuildingSession, navigator: BuildingNavigator) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val opener = remember { DocumentOpener() }
    // iOS `.refreshable`: the spinner stays while the list reloads.
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(session) {
        if (session.documents == BuildingSession.DocumentsState.Idle) session.loadDocuments()
    }
    Scaffold(
        topBar = { IvTopBar(stringResource(R.string.documents), onBack = navigator::back) },
        containerColor = Palette.background,
    ) { padding ->
        AccessGate(session, Modifier.fillMaxSize().padding(padding)) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        try {
                            session.loadDocuments()
                        } finally {
                            refreshing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    when (val state = session.documents) {
                        BuildingSession.DocumentsState.Idle, BuildingSession.DocumentsState.Loading -> item {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Palette.accent)
                            }
                        }
                        is BuildingSession.DocumentsState.Failed -> item {
                            ProblemView(state.problem) { scope.launch { session.loadDocuments() } }
                        }
                        is BuildingSession.DocumentsState.Loaded -> {
                            val items = state.items
                            if (items.isEmpty()) {
                                item { EmptyDocuments() }
                            } else {
                                val building = items.filter { it.elementID == null }
                                val elements = items.filter { it.elementID != null }
                                if (building.isNotEmpty()) {
                                    item { SectionHeader(stringResource(R.string.whole_building)) }
                                    for (document in building) {
                                        item {
                                            DocumentRow(document, isOpening = opener.opening == document.id) {
                                                scope.launch { opener.open(document, session, context) }
                                            }
                                        }
                                    }
                                }
                                if (elements.isNotEmpty()) {
                                    item { SectionHeader(stringResource(R.string.equipment_and_fixtures)) }
                                    for (document in elements) {
                                        item {
                                            val name = document.elementID?.let { session.index[it] }?.let(session::displayName)
                                            DocumentRow(document, elementName = name, isOpening = opener.opening == document.id) {
                                                scope.launch { opener.open(document, session, context) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    DocumentFailureDialog(opener)
}

@Composable
private fun EmptyDocuments() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.Description, contentDescription = null, tint = Palette.muted, modifier = Modifier.size(48.dp))
        Text(
            stringResource(R.string.no_documents_yet),
            style = IvType.body(22.sp, FontWeight.Bold),
            color = Palette.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.manuals_warranties_and_certificates_the_builder_shares_appea),
            style = IvType.body(15.sp),
            color = Palette.muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(8.dp))
    }
}

private val DocumentKind.icon: ImageVector
    get() = when (this) {
        DocumentKind.MANUAL -> Icons.AutoMirrored.Outlined.MenuBook
        DocumentKind.WARRANTY -> Icons.Outlined.Verified
        DocumentKind.CERTIFICATE -> Icons.Outlined.WorkspacePremium
        DocumentKind.DRAWING -> Icons.Outlined.Architecture
        DocumentKind.PHOTO -> Icons.Outlined.Photo
        DocumentKind.INVOICE -> Icons.AutoMirrored.Outlined.ReceiptLong
        else -> Icons.Outlined.Description
    }

/** A document kind's name ("Manual", "Warranty", …; "Document" for others). */
@Composable
fun documentKindTitle(kind: DocumentKind): String = stringResource(
    when (kind) {
        DocumentKind.MANUAL -> R.string.manual
        DocumentKind.WARRANTY -> R.string.warranty
        DocumentKind.CERTIFICATE -> R.string.certificate
        DocumentKind.DRAWING -> R.string.drawing
        DocumentKind.PHOTO -> R.string.photo
        DocumentKind.INVOICE -> R.string.invoice
        else -> R.string.document
    },
)
