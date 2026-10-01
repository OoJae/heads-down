package xyz.headsdown.core.chain.indexer

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.http.JsonHttp
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger

/** The indexer answered outside contract B. The message is fixed text, never the body. */
class HaulFormatException(what: String) : IOException("haul response: $what")

/** The indexer refused or failed the request (not a 404). */
class IndexerHttpException(val status: Int) : IOException("indexer HTTP status $status")

/** Shift mode as the ShiftLog records it (`mode` 0 night, 1 day, 2 focus-only). */
enum class HaulMode(val wire: String) { NIGHT("night"), DAY("day"), FOCUS_ONLY("focus_only") }

/** One ORE round of the shift. */
data class HaulRound(
    val roundId: ULong,
    /** A lease covered this round (the phone was face-down and heartbeating). */
    val dark: Boolean,
    /** Squares the rig's Automation deployed on this round (bit i = square i); 0 if not dug. */
    val dugMask: Int,
    /** The round's winning square, or null when the indexer has not seen its reset. */
    val winningSquare: Int?,
    val motherlode: Boolean,
    /** The winning square was split among its miners (not a solo win). */
    val split: Boolean,
)

/**
 * Contract B's `HaulSummary`, parsed strictly. Amounts are exact: lamports as [ULong], ORE in
 * atoms (11 decimals) as [BigInteger], prices as [BigDecimal] lamports per whole ORE.
 */
data class IndexerHaul(
    val rig: Pubkey,
    val shiftId: ULong,
    val mode: HaulMode,
    val startTs: Long,
    val endTs: Long,
    val startRound: ULong,
    val endRound: ULong,
    val rounds: List<HaulRound>,
    val darkRounds: ULong,
    val roundsDug: ULong,
    val solPlacedLamports: ULong,
    val feesLamports: ULong,
    val oreMinedAtoms: BigInteger,
    val effectiveLamportsPerOre: BigDecimal?,
    val marketLamportsPerOre: BigDecimal?,
    val marketSource: String?,
    val streakBefore: Long,
    val streakAfter: Long,
    /** `ShiftLog.break_reason` (0 completed … 8 unlocked), or null when not reported. */
    val breakReason: Int?,
    /** Not observable on-chain: the phone fills it from its own shift log. */
    val firstPickupTs: Long?,
    /** Simulated data (not the rig's own on-chain night): never shown as real, never on the widget. */
    val simulated: Boolean,
    val explorerShiftLog: String?,
    val explorerSampleDigs: List<String>,
)

/**
 * The indexer's morning-haul API (contract B):
 * `GET /v1/rigs/{rig}/haul/latest` and `GET /v1/rigs/{rig}/haul/{shift_id}` → `HaulSummary`, or
 * 404 when there is no finished shift. Numbers above 2^53 arrive as decimal strings; every
 * integer is accepted either way. A summary for another rig, an impossible mask or square, or
 * a non-https explorer link is refused rather than shown.
 */
class IndexerHaulClient(private val http: JsonHttp) {

    /** The most recent finished shift, or null when there is none (404). */
    suspend fun latest(rig: Pubkey): IndexerHaul? = fetch(rig, "/v1/rigs/${rig.toBase58()}/haul/latest")

    /** A given shift, or null when it is not finished or unknown (404). */
    suspend fun byShift(rig: Pubkey, shiftId: ULong): IndexerHaul? = fetch(rig, "/v1/rigs/${rig.toBase58()}/haul/$shiftId")

    private suspend fun fetch(rig: Pubkey, path: String): IndexerHaul? {
        val reply = http.get(path)
        if (reply.status == 404) return null
        if (!reply.isSuccessful) throw IndexerHttpException(reply.status)
        return parse(reply.body, rig)
    }

    companion object {
        const val MAX_ROUNDS = 5_000
        const val MAX_SAMPLE_DIGS = 16
        private const val TILES = 25
        private val ORE_ATOMS_PER_ORE = BigDecimal.TEN.pow(11)

        /** Parses one HaulSummary for [expectedRig]. */
        fun parse(body: String, expectedRig: Pubkey): IndexerHaul {
            val o = try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: throw HaulFormatException("not a JSON object")
            val rig = try {
                Pubkey.fromBase58(o.string("rig"))
            } catch (_: IllegalArgumentException) {
                throw HaulFormatException("rig")
            }
            if (rig != expectedRig) throw HaulFormatException("summary for another rig")
            val mode = HaulMode.entries.firstOrNull { it.wire == o.string("mode") } ?: throw HaulFormatException("mode")
            val roundsJson = o["rounds"] as? JsonArray ?: throw HaulFormatException("rounds")
            if (roundsJson.size > MAX_ROUNDS) throw HaulFormatException("too many rounds")
            val rounds = roundsJson.map { round(it) }
            val startTs = o.i64("start_ts")
            val endTs = o.i64("end_ts")
            if (endTs < startTs) throw HaulFormatException("end before start")
            return IndexerHaul(
                rig = rig,
                shiftId = o.u64("shift_id"),
                mode = mode,
                startTs = startTs,
                endTs = endTs,
                startRound = o.u64("start_round"),
                endRound = o.u64("end_round"),
                rounds = rounds,
                darkRounds = o.u64("dark_rounds"),
                roundsDug = o.u64("rounds_dug"),
                solPlacedLamports = o.u64("sol_placed_lamports"),
                feesLamports = o.u64("fees_lamports"),
                oreMinedAtoms = oreAtoms(o["ore_mined_atoms"]),
                effectiveLamportsPerOre = price(o["effective_lamports_per_ore"]),
                marketLamportsPerOre = price(o["market_lamports_per_ore"]),
                marketSource = o.optionalString("market_source")?.take(64),
                streakBefore = o.u64("streak_before").toLongCapped(),
                streakAfter = o.u64("streak_after").toLongCapped(),
                breakReason = breakReason(o["break_reason"]),
                firstPickupTs = o["first_pickup_ts"].takeUnless { it == null || it is JsonNull }?.let { i64(it, "first_pickup_ts") },
                simulated = o.bool("simulated"),
                explorerShiftLog = (o["explorer"] as? JsonObject)?.optionalString("shift_log")?.let(::httpsUrl),
                explorerSampleDigs = ((o["explorer"] as? JsonObject)?.get("sample_digs") as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(::httpsUrl) }
                    .take(MAX_SAMPLE_DIGS),
            )
        }

        private fun round(e: JsonElement): HaulRound {
            val r = e as? JsonObject ?: throw HaulFormatException("round")
            val mask = r.u64("dug_mask")
            if (mask >= (1uL shl TILES)) throw HaulFormatException("dug_mask beyond 25 squares")
            val winning = r["winning_square"]?.takeUnless { it is JsonNull }?.let { i64(it, "winning_square") }
            if (winning != null && winning !in 0 until TILES) throw HaulFormatException("winning_square")
            return HaulRound(
                roundId = r.u64("round_id"),
                dark = r.bool("dark"),
                dugMask = mask.toInt(),
                winningSquare = winning?.toInt(),
                motherlode = r.bool("motherlode"),
                split = r.bool("split"),
            )
        }

        /** Atoms as an integer, or an ORE amount with at most 11 decimals ("0.00582000000"). */
        private fun oreAtoms(e: JsonElement?): BigInteger {
            val text = (e as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: throw HaulFormatException("ore_mined_atoms")
            if (text.all { it in '0'..'9' } && text.isNotEmpty() && text.length <= 30) return BigInteger(text)
            val ore = decimal(text, "ore_mined_atoms")
            val atoms = ore.multiply(ORE_ATOMS_PER_ORE)
            if (ore.signum() < 0 || atoms.stripTrailingZeros().scale() > 0) throw HaulFormatException("ore_mined_atoms")
            return atoms.toBigIntegerExact()
        }

        private fun price(e: JsonElement?): BigDecimal? {
            val p = e as? JsonPrimitive ?: return null
            if (p is JsonNull) return null
            val v = decimal(p.content, "price")
            if (v.signum() <= 0) throw HaulFormatException("price")
            return v
        }

        private fun breakReason(e: JsonElement?): Int? {
            val p = e as? JsonPrimitive ?: return null
            if (p is JsonNull) return null
            val names = listOf("completed", "pickup", "screen_on", "freeze", "lease_lapse", "budget", "manual", "unplugged", "unlocked")
            val code = if (p.isString && p.content in names) names.indexOf(p.content) else p.content.toIntOrNull()
            return code?.takeIf { it in 0..255 } ?: throw HaulFormatException("break_reason")
        }

        private fun decimal(text: String, what: String): BigDecimal {
            if (text.length > 64 || !text.matches(Regex("-?[0-9]+(\\.[0-9]+)?"))) throw HaulFormatException(what)
            return BigDecimal(text)
        }

        /** Only https links (the reveal opens them in a browser). */
        private fun httpsUrl(text: String): String? {
            val url = text.toHttpUrlOrNull() ?: return null
            return if (url.isHttps && text.length <= 512) url.toString() else null
        }

        private fun JsonObject.string(key: String): String =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw HaulFormatException(key)

        private fun JsonObject.optionalString(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.bool(key: String): Boolean {
            val p = this[key] as? JsonPrimitive ?: throw HaulFormatException(key)
            if (p.isString) throw HaulFormatException(key)
            return p.content.toBooleanStrictOrNull() ?: throw HaulFormatException(key)
        }

        /** A u64 as a JSON number or a decimal string. */
        private fun JsonObject.u64(key: String): ULong {
            val text = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: throw HaulFormatException(key)
            if (text.isEmpty() || text.length > 20 || !text.all { it in '0'..'9' }) throw HaulFormatException(key)
            return text.toULongOrNull() ?: throw HaulFormatException(key)
        }

        private fun JsonObject.i64(key: String): Long = i64(this[key] ?: throw HaulFormatException(key), key)

        private fun i64(e: JsonElement, key: String): Long {
            val text = (e as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: throw HaulFormatException(key)
            if (!text.matches(Regex("-?[0-9]{1,19}"))) throw HaulFormatException(key)
            return text.toLongOrNull() ?: throw HaulFormatException(key)
        }

        private fun ULong.toLongCapped(): Long = if (this > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else toLong()
    }
}
