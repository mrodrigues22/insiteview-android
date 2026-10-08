package com.getinsiteview.features.ar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.getinsiteview.design.Detent
import com.getinsiteview.design.DetentSlider
import com.getinsiteview.design.IvType
import com.getinsiteview.design.Palette
import com.getinsiteview.features.R
import com.getinsiteview.features.building.BuildingSession
import com.getinsiteview.features.viewer.RoomFocusChip
import com.getinsiteview.features.viewer.SubsystemChips
import com.getinsiteview.features.viewer.SystemChips
import com.getinsiteview.modelkit.SeeInside

/**
 * The See inside panel (A-03 AR screen, IOS-M2-06): the model-opacity slider with three detents
 * (Reality · Reality + model · Model) and haptics, system chips (only systems in scope) and,
 * expanded, subsystem chips and the room focus. A panel rather than a system sheet, so the object
 * card can still open over the camera.
 */
@Composable
fun SeeInsidePanel(session: BuildingSession, isExpanded: Boolean, onExpandedChange: (Boolean) -> Unit) {
    val label = stringResource(if (isExpanded) R.string.fewer_filters else R.string.more_filters)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.surface.copy(alpha = 0.9f), RectangleShape)
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = label) { onExpandedChange(!isExpanded) }
                .clearAndSetSemantics { contentDescription = label }
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(width = 36.dp, height = 5.dp).background(Palette.line, RectangleShape))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.see_inside), style = IvType.headline, color = Palette.ink)
                Spacer(Modifier.weight(1f))
                Icon(
                    if (isExpanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    tint = Palette.muted,
                )
            }
        }

        DetentSlider(
            value = session.seeInside,
            onValueChange = { session.setSeeInside(it) },
            detents = listOf(
                Detent(SeeInside.Detent.REALITY.raw, stringResource(R.string.reality)),
                Detent(SeeInside.Detent.REALITY_AND_MODEL.raw, stringResource(R.string.reality_model)),
                Detent(SeeInside.Detent.MODEL.raw, stringResource(R.string.model)),
            ),
            accessibilityLabel = stringResource(R.string.model_opacity),
            snapped = { SeeInside.snapped(it) },
            reachedDetent = { from, to -> SeeInside.detentCrossed(from = from, to = to) != null },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // The chips scroll edge to edge (iOS pads them out by −16).
        SystemChips(session, BuildingSession.SceneMode.AR)

        AnimatedVisibility(visible = isExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SubsystemChips(session, BuildingSession.SceneMode.AR)
                val roomID = session.arFilters.roomID
                if (roomID != null) {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        RoomFocusChip(session.roomName(roomID)) { session.focusRoom(null, BuildingSession.SceneMode.AR) }
                    }
                }
            }
        }
    }
}
