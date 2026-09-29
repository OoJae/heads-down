package xyz.headsdown.feature.reveal.haul

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Number and time formatting for the reveal. Amounts are rounded down, never up. */
object HaulFormat {
    private const val SOL_DECIMALS = 9
    private const val ORE_DECIMALS = 11
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    fun sol(lamports: Long): String = amount(lamports, SOL_DECIMALS, "SOL")

    fun ore(atoms: Long): String = amount(atoms, ORE_DECIMALS, "ORE")

    /** Lamports per whole ORE as SOL per ORE, 3 decimals: "0.680". */
    fun price(lamportsPerOre: Long): String =
        BigDecimal.valueOf(lamportsPerOre, SOL_DECIMALS).setScale(3, RoundingMode.HALF_EVEN).toPlainString()

    fun count(n: Int): String = "%,d".format(Locale.ROOT, n)

    /** "7 h 40 m", or "45 m" under an hour. */
    fun duration(millis: Long): String {
        val minutes = (millis.coerceAtLeast(0) / 60_000)
        val h = minutes / 60
        val m = minutes % 60
        return if (h == 0L) "$m m" else "$h h ${"%02d".format(Locale.ROOT, m)} m"
    }

    fun clock(wallMillis: Long, zone: ZoneId): String = CLOCK.format(Instant.ofEpochMilli(wallMillis).atZone(zone))

    private fun amount(units: Long, decimals: Int, symbol: String): String {
        if (units <= 0) return "0 $symbol"
        val value = BigDecimal.valueOf(units, decimals)
        val scale = if (value >= BigDecimal.ONE) 3 else 4
        val shown = value.setScale(scale, RoundingMode.DOWN)
        return if (shown.signum() == 0) "<0.0001 $symbol" else "${shown.toPlainString()} $symbol"
    }
}
