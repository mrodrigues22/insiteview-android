// iOS 26 navigation chrome: the large title that collapses into a small one, the bar's glass
// buttons, the search field (at the bottom of the screen on iPhone), and list rows with swipe
// actions.
package com.getinsiteview.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * A navigation bar (iOS 26):
 * - the back button is a glass circle with a chevron, the trailing [actions] sit in a glass capsule
 *   ([IvGlassButtonGroup]);
 * - [largeTitle] (tab roots): an Anton title under the bar, mixed case, that scrolls away into the
 *   small Barlow semibold title as [scrollBehavior] collapses;
 * - the small title is centred, or follows the back button when there are trailing actions (iOS
 *   leaves the centre to the toolbar group then);
 * - no bar background at rest; once content scrolls under it, a soft fade of the background colour
 *   (iOS 26's scroll edge effect) instead of a hairline.
 *
 * @param scrollBehavior pass `TopAppBarDefaults.exitUntilCollapsedScrollBehavior()` and connect its
 *   `nestedScrollConnection` to the content; null for a fixed bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IvNavigationBar(
    title: String,
    onBack: (() -> Unit)?,
    backLabel: String,
    modifier: Modifier = Modifier,
    largeTitle: Boolean = false,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    var largeHeightPx by remember { mutableStateOf(0f) }
    val state = scrollBehavior?.state
    // The large title's height is the distance the bar can collapse.
    LaunchedEffect(largeHeightPx, state) {
        if (state != null) state.heightOffsetLimit = if (largeTitle) -largeHeightPx else 0f
    }
    val collapsed = if (!largeTitle) 1f else state?.collapsedFraction ?: 0f
    val scrolledUnder = (state?.overlappedFraction ?: 0f) > 0.01f || (largeTitle && collapsed > 0.99f)
    val background = Palette.background

    Column(
        modifier
            .fillMaxWidth()
            .background(
                if (scrolledUnder) {
                    Brush.verticalGradient(0f to background, 0.8f to background.copy(alpha = 0.92f), 1f to background.copy(alpha = 0f))
                } else {
                    SolidColor(Color.Transparent)
                },
            )
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IvGlassButton(Icons.AutoMirrored.Filled.ArrowBackIos, contentDescription = backLabel, onClick = onBack)
                }
                if (actions != null && title.isNotEmpty() && collapsed > 0.5f) {
                    Spacer(Modifier.width(12.dp))
                    SmallTitle(title, Modifier.graphicsLayer { alpha = ((collapsed - 0.5f) * 2f).coerceIn(0f, 1f) })
                }
            }
            if (actions == null && title.isNotEmpty()) {
                SmallTitle(
                    title,
                    Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 56.dp)
                        .graphicsLayer { alpha = if (largeTitle) ((collapsed - 0.5f) * 2f).coerceIn(0f, 1f) else 1f },
                )
            }
            if (actions != null) {
                IvGlassButtonGroup(Modifier.align(Alignment.CenterEnd), content = actions)
            }
        }
        if (largeTitle) {
            val offsetPx = state?.heightOffset ?: 0f
            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { if (it.height > 0 && largeHeightPx == 0f) largeHeightPx = it.height.toFloat() }
                    .layoutHeightOffset(offsetPx),
            ) {
                Text(
                    title,
                    style = IvType.display(34.sp),
                    color = Palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                        .graphicsLayer {
                            translationY = offsetPx
                            alpha = (1f - collapsed * 1.5f).coerceIn(0f, 1f)
                        },
                )
            }
        }
    }
}

@Composable
private fun SmallTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, style = IvType.headline, color = Palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** Shrinks a layout by [offsetPx] (≤ 0) from the bottom, so content under it moves up as it collapses. */
private fun Modifier.layoutHeightOffset(offsetPx: Float): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = (placeable.height + offsetPx.roundToInt()).coerceAtLeast(0)
        layout(placeable.width, height) { placeable.place(0, 0) }
    },
)

/**
 * The iOS 26 search field: a glass capsule with a magnifier, the query, and a clear button; while
 * it's focused a circular glass ✕ next to it ends the search (iOS "Cancel"). iPhone shows it at the
 * bottom of the screen, above the tab bar; screens place it there.
 */
@Composable
fun IvSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    clearLabel: String,
    cancelLabel: String,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .glass(RoundedCornerShape(percent = 50), GlassMaterial.Regular)
                .clickable(interactionSource = null, indication = null) { focus.requestFocus() }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = Palette.muted, modifier = Modifier.size(20.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(placeholder, style = IvType.system(SystemStyle.Body), color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = IvType.system(SystemStyle.Body).copy(color = Palette.ink),
                    cursorBrush = SolidColor(Palette.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        onSearch()
                        focusManager.clearFocus()
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused },
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Filled.Cancel,
                    contentDescription = clearLabel,
                    tint = Palette.muted,
                    modifier = Modifier.size(20.dp).clickable { onQueryChange("") },
                )
            }
        }
        AnimatedVisibility(visible = focused, enter = fadeIn(), exit = fadeOut()) {
            IvGlassButton(
                Icons.Filled.Close,
                contentDescription = cancelLabel,
                onClick = {
                    onQueryChange("")
                    focusManager.clearFocus()
                },
                material = GlassMaterial.Regular,
            )
        }
    }
}

/** A swipe action revealed under a row (iOS `.swipeActions`): an icon on the accent colour. */
data class SwipeAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** The row that's open in a list: opening another closes it, as on iOS. */
class SwipeRowGroup {
    internal var open by mutableStateOf<Any?>(null)
}

/** One open row at a time for the rows inside (a list). */
val LocalSwipeRowGroup = compositionLocalOf { SwipeRowGroup() }

/**
 * A row with a swipe action (iOS `.swipeActions(edge:)`): dragging from [leading] (the start edge)
 * or the end edge reveals the action's button; a tap on it runs it, and a full swipe runs it at once.
 * Tapping the open row closes it instead of opening the row. TalkBack gets the action as a custom
 * action, as VoiceOver lists swipe actions.
 */
@Composable
fun SwipeActionRow(
    key: Any,
    action: SwipeAction,
    modifier: Modifier = Modifier,
    leading: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val group = LocalSwipeRowGroup.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // + is towards the end edge; a leading action is revealed by a drag towards the end.
    val sign = (if (leading) 1f else -1f) * (if (rtl) -1f else 1f)
    val buttonPx = with(density) { 74.dp.toPx() }
    var widthPx by remember { mutableStateOf(1f) }
    val isOpen = group.open == key

    LaunchedEffect(isOpen) {
        if (!isOpen && offset.value != 0f) offset.animateTo(0f, spring())
    }

    fun settle(velocity: Float) {
        scope.launch {
            val revealed = abs(offset.value)
            when {
                revealed > widthPx * 0.6f -> {
                    offset.animateTo(sign * widthPx, spring())
                    action.onClick()
                    group.open = null
                    offset.animateTo(0f, spring())
                }
                revealed > buttonPx / 2 || abs(velocity) > 1500f && velocity * sign > 0 -> {
                    group.open = key
                    offset.animateTo(sign * buttonPx, spring())
                }
                else -> {
                    if (group.open == key) group.open = null
                    offset.animateTo(0f, spring())
                }
            }
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .semantics {
                customActions = listOf(CustomAccessibilityAction(action.label) { action.onClick(); true })
            },
    ) {
        // The action under the row, on the side the row moved away from.
        Box(
            Modifier
                .matchParentSize()
                .background(Palette.accent),
            contentAlignment = if (leading) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .width(with(density) { abs(offset.value).coerceAtLeast(buttonPx).toDp() })
                    .fillMaxHeight()
                    .clickable(onClickLabel = action.label) {
                        action.onClick()
                        group.open = null
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(action.icon, contentDescription = action.label, tint = Palette.onAccent)
            }
        }
        Box(
            Modifier
                .offset { IntOffset((if (rtl) -offset.value else offset.value).roundToInt(), 0) }
                .background(Palette.surface)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val d = if (rtl) -delta else delta
                        scope.launch {
                            val next = offset.value + d
                            // Only towards the action's side.
                            offset.snapTo(if (next * sign < 0) 0f else next)
                        }
                    },
                    onDragStarted = { if (group.open != key) group.open = null },
                    onDragStopped = { velocity -> settle(if (rtl) -velocity else velocity) },
                ),
        ) {
            content()
            if (isOpen) {
                // A tap on the open row closes it (iOS).
                Box(
                    Modifier.matchParentSize().clickable(interactionSource = null, indication = null) { group.open = null },
                )
            }
        }
    }
}
