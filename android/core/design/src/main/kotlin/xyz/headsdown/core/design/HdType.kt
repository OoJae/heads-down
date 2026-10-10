package xyz.headsdown.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The three faces, named in this one place. The files are in `res/font`; where they come from,
 * their hashes and their licences are in `FONTS.md` beside this module's build file.
 */
object HdFonts {
    /**
     * Big Shoulders Display, one static weight (800), a Latin subset. For numerals and for
     * statements of one to three words. It has no tabular figures (a digit is about 0.47em wide,
     * "1" about 0.26em) and no check mark: left-align numerals, never change a number on every
     * frame, and draw a tick as a vector.
     */
    val Display: FontFamily = FontFamily(Font(R.font.big_shoulders_display_extrabold, FontWeight.ExtraBold))

    /** IBM Plex Mono 400 and 500, unmodified: labels, addresses, lamports, round numbers. */
    val Mono: FontFamily = FontFamily(
        Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    )

    /** The platform's own face, for everything a person reads as a sentence. */
    val Body: FontFamily = FontFamily.Default
}

/**
 * The type scale, in sp. The styles carry no colour: the component that draws them picks the ink.
 *
 * [hero] and [display] are drawn through `HeroNumerals` and `DisplayText`, which stop them growing
 * past 1.3x with the system font scale. Body text is never below 16sp.
 */
@Immutable
class HdType internal constructor() {
    /** 112sp. The one number on a screen. */
    val hero: TextStyle = displayFace(112, lineHeightEm = 1.0f)

    /** 56sp. A statement of one to three words, or a figure. */
    val display: TextStyle = displayFace(56, lineHeightEm = 1.05f)

    /** 30sp, in the display face. A screen's title. */
    val title: TextStyle = displayFace(30, lineHeightEm = 1.1f)

    /** 19sp semibold. A heading inside a screen. */
    val headline: TextStyle = TextStyle(fontFamily = HdFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp)

    /** 16sp on a 24sp line. Sentences. */
    val body: TextStyle = TextStyle(fontFamily = HdFonts.Body, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)

    /** 16sp semibold on a 20sp line. The label of a bar, a choice, a link. */
    val button: TextStyle = TextStyle(fontFamily = HdFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp)

    /** 13sp mono, tracked +0.08em. Drawn in capitals by `Label`. */
    val label: TextStyle = TextStyle(
        fontFamily = HdFonts.Mono,
        fontWeight = FontWeight.Medium,
        fontSynthesis = FontSynthesis.None,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.08.em,
    )

    /** 12sp mono. A footnote to a figure. */
    val caption: TextStyle = TextStyle(
        fontFamily = HdFonts.Mono,
        fontWeight = FontWeight.Normal,
        fontSynthesis = FontSynthesis.None,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

    /** 16sp mono. An amount, an address, a round number: every digit the same width. */
    val figure: TextStyle = TextStyle(
        fontFamily = HdFonts.Mono,
        fontWeight = FontWeight.Normal,
        fontSynthesis = FontSynthesis.None,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )

    companion object {
        /** The scale is the same in both palettes, so one instance serves everything. */
        val Default: HdType = HdType()

        /** How far [hero] and [display] may grow with the system font scale. */
        const val DISPLAY_SCALE_CAP = 1.3f

        private fun displayFace(sizeSp: Int, lineHeightEm: Float) = TextStyle(
            fontFamily = HdFonts.Display,
            fontWeight = FontWeight.ExtraBold,
            // One weight exists. A request for another draws this one, never a smeared copy of it.
            fontSynthesis = FontSynthesis.None,
            fontSize = sizeSp.sp,
            lineHeight = lineHeightEm.em,
            letterSpacing = (-0.02).em,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None),
        )
    }
}
