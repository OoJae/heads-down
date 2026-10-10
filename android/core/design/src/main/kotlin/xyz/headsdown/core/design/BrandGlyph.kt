package xyz.headsdown.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import xyz.headsdown.core.design.components.ProvideCappedFontScale

/**
 * THE MARK: a dark slab seen from a low angle, hovering over a thin line of light. As a
 * silhouette, a bar over a line.
 *
 * These are the canonical paths on a 24-unit grid (a bounding box of 20 by 14, centred on 12,12).
 * The Quick Settings tile icon and the notification's small icon carry the same path data, and
 * SingleSourceTest fails if either drifts; the launcher icon and the splash draw the same slab on
 * the 108-unit grid ([LAUNCHER_FACE], [LAUNCHER_UNDERSIDE], [LAUNCHER_SEAM]).
 */
object HdGlyph {
    const val VIEWPORT = 24f

    /** The slab: its face and its underside as one silhouette. */
    const val HULL = "M2,5h20v4l-3,4h-14l-3,-4z"

    /** The line of light under it. */
    const val SEAM = "M4,17h16v2h-16z"

    const val LAUNCHER_FACE = "M29,36h50v10h-50z"
    const val LAUNCHER_EDGE = "M29,36h50v1h-50z"
    const val LAUNCHER_UNDERSIDE = "M29,46h50l-7,10h-36z"
    const val LAUNCHER_SEAM = "M34,66h40v5h-40z"

    /** [LAUNCHER_FACE] and [LAUNCHER_UNDERSIDE] as one silhouette, for the themed (monochrome) icon. */
    const val LAUNCHER_HULL = "M29,36h50v10l-7,10h-36l-7,-10z"
}

/**
 * The mark, drawn. It is a drawing and nothing else: no semantics, and no size of its own (give
 * it one with [modifier]; the 24-unit grid is fitted to the smaller side and centred).
 *
 * The slab takes [hull] (ink by default) and the line takes [seam] (the gold light). For the
 * one-colour mark pass the same colour twice.
 */
@Composable
fun BrandGlyph(
    modifier: Modifier = Modifier,
    hull: Color = Hd.colors.chalk,
    seam: Color = Hd.colors.seamLight,
) {
    val hullPath = remember { PathParser().parsePathString(HdGlyph.HULL).toPath() }
    val seamPath = remember { PathParser().parsePathString(HdGlyph.SEAM).toPath() }
    Canvas(modifier) {
        val side = size.minDimension
        val unit = side / HdGlyph.VIEWPORT
        withTransform({
            translate((size.width - side) / 2f, (size.height - side) / 2f)
            scale(unit, unit, pivot = Offset.Zero)
        }) {
            drawPath(hullPath, hull)
            drawPath(seamPath, seam)
        }
    }
}

/**
 * THE WORDMARK: "HEADS" over "DOWN" in the display face, a thin seam line between them. "DOWN",
 * under the line, takes the gold. With [oneColor] both words and the line are [ink].
 *
 * One node to a screen reader: the name, as a person says it.
 */
@Composable
fun Wordmark(
    modifier: Modifier = Modifier,
    style: TextStyle = Hd.type.display,
    ink: Color = Hd.colors.chalk,
    oneColor: Boolean = false,
) {
    val line = if (oneColor) ink else Hd.colors.seamLight
    // "DOWN" is ink on the page, so it takes the gold that reads as ink in this palette.
    val down = if (oneColor) ink else Hd.colors.seam
    ProvideCappedFontScale(HdType.DISPLAY_SCALE_CAP) {
        Column(modifier.width(IntrinsicSize.Max).clearAndSetSemantics { contentDescription = "Heads Down" }) {
            BasicText("HEADS", style = style.copy(color = ink), maxLines = 1, softWrap = false)
            Spacer(Modifier.padding(vertical = 3.dp).fillMaxWidth().height(HdDimens.SeamLine).background(line))
            BasicText("DOWN", style = style.copy(color = down), maxLines = 1, softWrap = false)
        }
    }
}
