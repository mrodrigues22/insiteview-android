@file:OptIn(ExperimentalMaterial3Api::class)

package com.getinsiteview.features.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getinsiteview.core.DeepLink
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.features.R
import com.getinsiteview.features.ui.IvTopBar
import com.getinsiteview.features.ui.LabeledRow
import com.getinsiteview.features.ui.ListRow
import com.getinsiteview.features.ui.SectionHeader
import kotlinx.coroutines.launch

/** Debug screen: environment, `GET /health/ready` result and the URL the app was opened with. */
@Composable
fun DiagnosticsView(model: DiagnosticsModel, onBack: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.checkHealth() }
    Scaffold(
        topBar = { IvTopBar(stringResource(R.string.diagnostics), onBack) },
        containerColor = Palette.background,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader(stringResource(R.string.environment)) }
            val configuration = model.configuration.getOrNull()
            if (configuration != null) {
                item { MonoRow("API_BASE_URL", configuration.apiBaseURL.toString()) }
                item { MonoRow("WEB_BASE_URL", configuration.webBaseURL.toString()) }
            } else {
                item {
                    ListRow {
                        val error = model.configuration.exceptionOrNull()
                        Text(error?.message ?: error.toString(), color = Color.Red, style = IvType.body())
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.api_status)) }
            when (val status = model.status) {
                DiagnosticsModel.Status.Checking -> item {
                    ListRow {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.checking), style = IvType.body(), color = Palette.muted)
                    }
                }
                is DiagnosticsModel.Status.Finished -> {
                    val readiness = status.readiness
                    item {
                        ListRow {
                            if (readiness.isReady) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2E9E4F))
                                Text(stringResource(R.string.ready_x, readiness.health.status), color = Color(0xFF2E9E4F))
                            } else {
                                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFE08A00))
                                Text(stringResource(R.string.not_ready_x, readiness.health.status), color = Color(0xFFE08A00))
                            }
                        }
                    }
                    for (check in readiness.health.checks) {
                        item { MonoRow(check.name, check.status) }
                    }
                }
                is DiagnosticsModel.Status.Failed -> item {
                    ListRow {
                        Icon(Icons.Filled.Error, contentDescription = null, tint = Color.Red)
                        Text(stringResource(R.string.failed_x, status.message), color = Color.Red)
                    }
                }
            }
            item {
                ListRow {
                    TextButton(
                        onClick = { scope.launch { model.checkHealth() } },
                        enabled = model.status != DiagnosticsModel.Status.Checking,
                    ) {
                        Text(stringResource(R.string.check_again), color = Palette.accent)
                    }
                }
            }

            val url = model.openedURL
            if (url != null) {
                item { SectionHeader(stringResource(R.string.invocation)) }
                item {
                    ListRow {
                        SelectionContainer { Text(url.toString(), style = IvType.mono(12.sp), color = Palette.ink) }
                    }
                }
                item { ListRow { Text(deepLinkText(model.deepLink), style = IvType.body(), color = Palette.ink) } }
            }
        }
    }
}

@Composable
private fun MonoRow(key: String, value: String) {
    LabeledRow(key) {
        SelectionContainer { Text(value, style = IvType.body(15.sp), color = Palette.muted) }
    }
}

@Composable
private fun deepLinkText(link: DeepLink?): String = when (link) {
    is DeepLink.Building -> {
        val plate = link.plate
        if (plate == null) {
            stringResource(R.string.building_x, link.code.formatted)
        } else {
            stringResource(R.string.building_x_plate_n, link.code.formatted, plate)
        }
    }
    is DeepLink.AccessLink -> stringResource(R.string.access_link)
    null -> stringResource(R.string.this_isn_t_an_insite_view_code)
}
