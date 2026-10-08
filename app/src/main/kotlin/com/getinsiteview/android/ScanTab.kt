package com.getinsiteview.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.getinsiteview.features.app.AppRootModel

/**
 * The Scan tab (A-02): the in-app QR scanner. Only our URLs are accepted; a building opens in the
 * Buildings tab. The scanner restarts each time the tab shows (the last one stopped after a code).
 */
@Composable
fun ScanTab(model: AppRootModel, isActive: Boolean, modifier: Modifier = Modifier) {
    // A new scanner each time the tab opens again (not on the first showing).
    var scanSession by remember { mutableIntStateOf(0) }
    var wasActive by remember { mutableStateOf(isActive) }
    LaunchedEffect(isActive) {
        if (isActive && !wasActive) scanSession += 1
        wasActive = isActive
    }
    if (isActive) {
        key(scanSession) {
            ScannerView(modifier = modifier, showsCancel = false) { text ->
                model.openScannedText(text)
            }
        }
    } else {
        Box(modifier.fillMaxSize().background(Color.Black))
    }
}
