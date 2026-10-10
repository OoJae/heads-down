package xyz.headsdown.core.design.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.HdDimens

/**
 * The dock: the actions of a screen, pinned to its bottom edge over the pit, above the navigation
 * bar. A hairline separates it from what scrolls above; with [seam] that line is 2dp of gold
 * light, the screen's content sitting on it like the slab on its seam.
 *
 * - It is one traversal group: a screen reader reaches its actions together, after the content.
 * - Its content is in a vertical scroll of its own. Normally there is nothing to scroll, and
 *   `performScrollTo()` on a button in the dock (the tests wrote that when the buttons lived in
 *   the page) finds a scroll parent and does nothing. On a short screen at a large font scale the
 *   dock scrolls instead of pushing its last action off the screen.
 */
@Composable
fun ActionDock(
    modifier: Modifier = Modifier,
    seam: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Hd.colors
    val rule = if (seam) colors.seamLight else colors.hairline
    val ruleHeight = if (seam) HdDimens.SeamLine else HdDimens.Hairline
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.pit)
            .drawBehind { drawRect(rule, Offset.Zero, Size(size.width, ruleHeight.toPx())) }
            .semantics { isTraversalGroup = true }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HdDimens.Margin, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(HdDimens.Rhythm),
        content = content,
    )
}

/**
 * A screen with a dock: [content] scrolls in the space above, [dock] (an [ActionDock]) stays at
 * the bottom. The content is padded for the status bar, the cutout and the 20dp margins; the dock
 * pads itself for the navigation bar.
 */
@Composable
fun PinnedBarScreen(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = HdDimens.Margin, vertical = 16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(16.dp),
    content: @Composable ColumnScope.() -> Unit,
    dock: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize().background(Hd.colors.pit)) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .verticalScroll(scrollState)
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
        dock()
    }
}
