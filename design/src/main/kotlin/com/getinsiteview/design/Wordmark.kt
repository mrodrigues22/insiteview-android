package com.getinsiteview.design

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** The logo: "INSITE VIEW" in Anton followed by a red slash. The brand name isn't translated. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, size: TextUnit = 28.sp) {
    val gap = with(LocalDensity.current) { (size * 0.18f).toDp() }
    Row(
        modifier.clearAndSetSemantics {
            contentDescription = "Insite View"
            heading()
        },
    ) {
        Text("INSITE VIEW", style = IvType.display(size), color = Palette.ink, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(gap))
        Text("/", style = IvType.display(size), color = Palette.accent, maxLines = 1, softWrap = false)
    }
}
