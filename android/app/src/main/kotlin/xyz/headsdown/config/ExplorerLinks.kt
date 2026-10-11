package xyz.headsdown.config

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Where a confirmed transaction can be looked at by anyone. Pure: a signature and the build's
 * cluster in, an https address or nothing out.
 *
 * The signature comes back from a wallet app, so its shape is checked before it is put in an
 * address: only the base58 alphabet, only the length 64 bytes can have. Anything else gives no
 * link at all. (It is not decoded: whether such a transaction exists is the explorer's to say.)
 */
object ExplorerLinks {

    /** `BuildConfig.SOLANA_CHAIN` of a mainnet build, and of a devnet one. */
    const val MAINNET = "solana:mainnet"
    const val DEVNET = "solana:devnet"

    /** 64 bytes in base58 are 64 characters (all zero) to 88. */
    private val SIGNATURE = Regex("[1-9A-HJ-NP-Za-km-z]{64,88}")

    /**
     * The page for the transaction [signature] on [cluster] (CAIP-2, as in `BuildConfig`):
     * Solscan on mainnet, Solana's own explorer on devnet. Null on a local validator, which no
     * public explorer can see, and for anything that is not a signature.
     */
    fun tx(signature: String?, cluster: String): String? {
        if (signature == null || !SIGNATURE.matches(signature)) return null
        return when (cluster) {
            MAINNET -> "https://solscan.io/tx/$signature"
            DEVNET -> "https://explorer.solana.com/tx/$signature?cluster=devnet"
            else -> null
        }
    }
}

/**
 * Hands [url] to whatever the phone opens web pages with. False when nothing can: the link then
 * does nothing, and the screen has already said everything the page would.
 */
fun Context.openExplorerLink(url: String): Boolean =
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
