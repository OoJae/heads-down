package xyz.headsdown.feature.reveal.haul

/**
 * The positioning rules as a check (docs/ECONOMICS.md §4, "Words never to use"). Tests run every
 * user-facing string through it; it is tiny, so it ships rather than living only in tests.
 */
object HonestCopy {
    private val BANNED = Regex(
        "\\b(" +
            "earn\\w*|yield\\w*|stak(e|es|ed|ing)|passive income|proof of focus|focus mining|" +
            "profit\\w*|guarantee\\w*|risk-free|free ore|" +
            "lotter(y|ies)|jackpot\\w*|gambl\\w*|bet|bets|betting|" +
            "apy|apr|returns|interest" +
            ")\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Every banned word or phrase found in [text], lower-cased. */
    fun violations(text: String): List<String> = BANNED.findAll(text).map { it.value.lowercase() }.toList()
}
