package xyz.headsdown.core.design.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.headsdown.core.design.Hd
import xyz.headsdown.core.design.HdType

/**
 * Draws [content] with the system font scale held at [max]. Sizes in dp are untouched; only text
 * stops growing. For the display face, which is already large: body text is never capped.
 */
@Composable
fun ProvideCappedFontScale(max: Float = HdType.DISPLAY_SCALE_CAP, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val capped = remember(density, max) {
        if (density.fontScale <= max) density else Density(density.density, max)
    }
    CompositionLocalProvider(LocalDensity provides capped, content = content)
}

/**
 * A statement in the display face: one to three words, or a figure.
 *
 * With [caps] it DRAWS `text.uppercase()` and still tells a screen reader, a test and the emulator
 * script the string it was given: "Rig cold" is drawn "RIG COLD" and found as "Rig cold". A
 * `testTag` or any other semantics in [modifier] stays on the same node.
 *
 * The font scale is capped at 1.3x.
 */
@Composable
fun DisplayText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Hd.type.display,
    color: Color = Hd.colors.chalk,
    caps: Boolean = true,
    textAlign: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    ProvideCappedFontScale {
        BasicText(
            text = if (caps) text.uppercase() else text,
            modifier = if (caps) modifier.spokenAs(text) else modifier,
            style = style.copy(color = color, textAlign = textAlign),
            maxLines = maxLines,
        )
    }
}

/**
 * A label in the mono face: 13sp, tracked, drawn in capitals. Like [DisplayText] it exposes the
 * string it was given, not the capitals. With [heading] a screen reader lists it among the
 * screen's headings.
 */
@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Hd.colors.ash,
    heading: Boolean = false,
) {
    BasicText(
        text = text.uppercase(),
        modifier = modifier.spokenAs(text, heading),
        style = Hd.type.label.copy(color = color),
    )
}

/**
 * The one number on a screen: a single line in the display face, as large as fits between 56sp
 * and the hero size (112sp), growing with the system font scale up to 1.3x and no further.
 *
 * The face has no tabular figures, so a changing number changes width: keep it left-aligned and
 * do not count it up frame by frame.
 */
@Composable
fun HeroNumerals(
    value: String,
    modifier: Modifier = Modifier,
    color: Color = Hd.colors.chalk,
) {
    val hero = Hd.type.hero
    val density = LocalDensity.current
    val cap = HdType.DISPLAY_SCALE_CAP
    val (smallest, largest) = remember(density, hero) {
        with(density) {
            val largest = minOf(hero.fontSize.toDp(), hero.fontSize.value.dp * cap).toSp()
            val smallest = minOf(HERO_MIN.toDp(), HERO_MIN.value.dp * cap).toSp()
            smallest to largest
        }
    }
    BasicText(
        text = value,
        modifier = modifier,
        style = hero.copy(color = color),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = smallest, maxFontSize = largest, stepSize = 2.sp),
    )
}

private val HERO_MIN = 56.sp

/**
 * Replaces the semantics of the text this is applied to with [original]: what is drawn may be in
 * capitals, what is read out and matched is the string as written. Semantics set earlier in the
 * chain (a test tag) are kept.
 */
internal fun Modifier.spokenAs(original: String, heading: Boolean = false): Modifier = clearAndSetSemantics {
    text = AnnotatedString(original)
    if (heading) heading()
}
