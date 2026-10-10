package xyz.headsdown.core.design

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Slabs and squares, not pills and circles. */
@Immutable
class HdShapes internal constructor() {
    /** 6dp: plates and bars. */
    val plate: CornerBasedShape = RoundedCornerShape(6.dp)

    /** 6dp: a bar button. */
    val bar: CornerBasedShape = plate

    /** 4dp: chips, badges, the squares of a choice. */
    val chip: CornerBasedShape = RoundedCornerShape(4.dp)

    /** 14dp on the top corners only: a sheet rising from the bottom edge. */
    val sheet: CornerBasedShape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)

    companion object {
        val Default: HdShapes = HdShapes()
    }
}

/** The grid: 4dp units, an 8dp rhythm, 20dp screen margins. */
object HdDimens {
    val Unit: Dp = 4.dp
    val Rhythm: Dp = 8.dp

    /** The margin between the screen's edge and its content. */
    val Margin: Dp = 20.dp

    /** The width of a hairline rule. */
    val Hairline: Dp = 1.dp

    /** The width of the gold line under a slab. */
    val SeamLine: Dp = 2.dp

    /** The height of a bar. */
    val Bar: Dp = 56.dp

    /** The smallest thing a finger is asked to hit. */
    val Touch: Dp = 48.dp

    /** The stroke of a glyph on its 24dp grid: square caps, no fills. */
    val GlyphStroke: Dp = 2.dp
}
