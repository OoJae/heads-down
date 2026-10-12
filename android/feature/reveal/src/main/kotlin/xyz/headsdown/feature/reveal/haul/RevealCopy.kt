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
    /** What the phone itself signed, from its own journal: shown apart from [stats], never as an on-chain count. */
    val signedOnPhone: RevealStat?,
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
        ) + (stats + listOfNotNull(signedOnPhone)).flatMap { listOf(it.label, it.value) }
}

/** How far a shift with no haul yet has come. */
enum class NightStage {
    /** Still open on-chain: the user has to clock out. */
    NOT_SEALED,

    /** The chain could not be read: open or sealed is not known. The clock-out reads it for itself. */
    UNCHECKED,

    /** Sealed, and its haul is being asked for. */
    READING,

    /** Asked for long enough: the screen stops asking. */
    NOT_READY,
    ;

    /** The page holds the "Clock out" bar: the shift is open, or nothing says it is not. */
    val offersClockOut: Boolean get() = this == NOT_SEALED || this == UNCHECKED
}

/** Every string the reveal shows for a shift that has no haul yet. Pure, so it is tested for honesty. */
data class NightCopy(
    val title: String,
    /** What the phone itself recorded of the shift, most telling first. */
    val facts: List<String>,
    val statement: String,
    /** What clocking out does, when that is the next thing to do. */
    val clockOutDetail: String?,
) {
    val allText: List<String> get() = listOfNotNull(title, statement, clockOutDetail) + facts
}

object NightCopyBuilder {
    fun build(night: OpenNight, stage: NightStage, zone: ZoneId): NightCopy = NightCopy(
        title = when (stage) {
            NightStage.NOT_SEALED -> "Not sealed yet"
            NightStage.UNCHECKED -> "Not checked yet"
            NightStage.READING -> "Reading the haul"
            NightStage.NOT_READY -> "Haul not ready yet"
        },
        facts = listOfNotNull(
            "Clocked in ${HaulFormat.clock(night.armedAtWallMillis, zone)}",
            night.lastHeartbeatWallMillis?.let { "Last heartbeat ${HaulFormat.clock(it, zone)}" },
            when (night.roundsSigned) {
                0 -> "No round signed dark"
                1 -> "1 round signed dark"
                else -> "${HaulFormat.count(night.roundsSigned)} rounds signed dark"
            },
        ),
        statement = when (stage) {
            NightStage.NOT_SEALED ->
                "This shift is still open on-chain. Its haul is read from the sealed shift, so there is none to show before you clock out."
            NightStage.UNCHECKED ->
                "This shift could not be checked on-chain just now: the phone may be offline. Clock out reads the chain itself and says what it finds."
            NightStage.READING ->
                "The shift is sealed. Its haul is final once ORE has settled the last round it dug: a minute or two."
            NightStage.NOT_READY -> "The shift is sealed, and its haul could not be read yet. Open this screen again in a minute."
        },
        clockOutDetail = RevealCopyBuilder.CLOCK_OUT_DETAIL.takeIf { stage.offersClockOut },
    )
}

object RevealCopyBuilder {
    const val CLOCK_OUT_BUTTON = "Clock out"

    /** Under the button: what the next screen does, and that nothing is signed by opening it. */
    const val CLOCK_OUT_DETAIL =
        "Seals the shift and shows the ORE in your Miner. Nothing is signed until you approve it in your wallet."

    /** The label of the phone's own count of the rounds it signed: whose number it is, in the label itself. */
    const val SIGNED_ON_PHONE = "Signed on this phone"

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
            signedOnPhone = h.roundsSigned?.let { RevealStat(SIGNED_ON_PHONE, if (it == 1) "1 round" else "${HaulFormat.count(it)} rounds") },
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
            buyStubMessage = "Nothing was bought: this button is not connected. Where a build can buy ORE, it is on the clock-out sheet.",
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
        is RouteVerdict.GateClosed ->
            "No round was dug in this shift, so nothing was placed. Most often that is the price gate staying closed: " +
                "on those nights buying is the cheaper route."
        RouteVerdict.NeverDark -> "The phone never lay face-down and dark in this shift, so nothing was placed."
        is RouteVerdict.NothingMined -> "None of the rig's tiles came up last night, so there is no price per ORE yet."
        RouteVerdict.FocusOnly -> "Focus-only shift: no SOL placed. It still counts for your streak."
        is RouteVerdict.NoMarket -> "No market quote right now, so there is nothing to compare against."
    }
}
