package xyz.headsdown.feature.reveal.haul

import java.time.ZoneId

data class RevealStat(val label: String, val value: String)

/** Every string the reveal shows for one haul. Pure, so it is tested for honesty. */
data class RevealCopy(
    val title: String,
    val subtitle: String,
    /** Shown prominently when the haul is not the user's own on-chain night. */
    val sampleBadge: String?,
    val stats: List<RevealStat>,
    val effectivePrice: String?,
    val marketPrice: String?,
    val verdict: String,
    val nearMiss: String?,
    val firstPickup: String?,
    val streak: String,
    val buyButton: String?,
    val buyDetail: String?,
    val buyStubMessage: String,
    val solPlacedNote: String,
    val footnote: String,
    val shareButton: String,
) {
    /** All of it, for the honesty test. */
    val allText: List<String>
        get() = listOfNotNull(
            title, subtitle, sampleBadge, effectivePrice, marketPrice, verdict, nearMiss, firstPickup,
            streak, buyButton, buyDetail, buyStubMessage, solPlacedNote, footnote, shareButton,
        ) + stats.flatMap { listOf(it.label, it.value) }
}

object RevealCopyBuilder {
    const val CLOCK_OUT_BUTTON = "Clock out"

    /** Under the button: what the next screen does, and that nothing is signed by opening it. */
    const val CLOCK_OUT_DETAIL =
        "Seals the shift and shows the ORE in your Miner. Nothing is signed until you approve it in your wallet."


    fun build(h: HaulSummary, zone: ZoneId): RevealCopy {
        val verdict = HaulMath.verdict(h)
        val buy = HaulMath.buyRest(h)
        return RevealCopy(
            title = "Morning haul",
            subtitle = "${HaulFormat.clock(h.startedAtWallMillis, zone)} – ${HaulFormat.clock(h.endedAtWallMillis, zone)} · " +
                "${HaulFormat.duration(h.darkMillis)} shift",
            sampleBadge = when (h.provenance) {
                HaulProvenance.SAMPLE -> "SAMPLE NIGHT · not your data"
                HaulProvenance.SIMULATED -> "SIMULATED · not on-chain data"
                HaulProvenance.ON_CHAIN -> null
            },
            stats = listOf(
                RevealStat("Rounds dark", HaulFormat.count(h.roundsDark)),
                RevealStat("Digs", HaulFormat.count(h.digs)),
                RevealStat("SOL placed", HaulFormat.sol(h.solPlacedLamports)),
                RevealStat("ORE mined", HaulFormat.ore(h.oreMinedAtoms)),
            ),
            effectivePrice = h.effectiveLamportsPerOre?.let { "${HaulFormat.price(it)} SOL per ORE" },
            marketPrice = h.marketLamportsPerOre?.let { price ->
                "Market ${HaulFormat.price(price)}" + (h.marketSource?.let { " ($it)" } ?: "")
            },
            verdict = verdictLine(verdict),
            nearMiss = h.motherlodeNearMiss?.let { round ->
                val at = if (round.endedAtWallMillis > 0) " at ${HaulFormat.clock(round.endedAtWallMillis, zone)}" else ""
                "The Motherlode hit the board$at, on a tile your rig did not dig."
            },
            firstPickup = h.firstPickupWallMillis?.let { "First pickup ${HaulFormat.clock(it, zone)}" },
            streak = when {
                h.streak.after > h.streak.before -> "Streak ${h.streak.before} → ${h.streak.after}"
                h.streak.after == h.streak.before -> "Streak holds at ${h.streak.after}"
                else -> "Streak reset to ${h.streak.after}"
            },
            buyButton = buy?.let { "Buy the rest at market · ${HaulFormat.ore(it.oreAtoms)}" },
            buyDetail = buy?.let {
                "About ${HaulFormat.sol(it.estimatedLamports)} at the market quote, before price impact and fees."
            },
            buyStubMessage = "The market buy leg is not connected yet. Nothing was bought.",
            solPlacedNote = "SOL placed is what your Automation put on the board" +
                (if (h.feesLamports > 0) ", plus ${HaulFormat.sol(h.feesLamports)} in executor fees" else "") +
                ". The effective price counts only the SOL that did not come back, plus crank fees.",
            footnote = "Mining is one route to ORE, not income. Some nights buying is cheaper, and this screen says so.",
            shareButton = "Share your night (no amounts)",
        )
    }

    fun verdictLine(v: RouteVerdict): String = when (v) {
        is RouteVerdict.MiningCheaper -> "Mining was the cheaper route last night: ${v.percent}% below market."
        is RouteVerdict.BuyingCheaper -> "Buying was the cheaper route last night: mining cost ${v.percent}% more than market."
        is RouteVerdict.AboutMarket -> "Mining came out at about the market price."
        is RouteVerdict.GateClosed -> if (v.market != null) {
            "The price gate stayed closed all night, so nothing was placed. Buying was the cheaper route."
        } else {
            "The price gate stayed closed all night, so nothing was placed."
        }
        is RouteVerdict.NothingMined -> "None of the rig's tiles came up last night, so there is no price per ORE yet."
        RouteVerdict.FocusOnly -> "Focus-only shift: no SOL placed. It still counts for your streak."
        is RouteVerdict.NoMarket -> "No market quote right now, so there is nothing to compare against."
    }
}
