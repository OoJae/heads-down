package xyz.headsdown.rig

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.headsdown.core.chain.Pubkey
import xyz.headsdown.core.chain.registrar.RegistrarVoucher
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The registrar voucher for this phone's rig key (INTERFACE v1.1 §4.2), kept until clock-in puts
 * it into `register_rig` / `rotate_key`. It is public data (the registrar publishes every voucher
 * in its transparency log), so it is stored plainly in private prefs, which are excluded from
 * backup and transfer. Every load re-checks it against the HDreg preimage; anything that does not
 * check out is dropped and the rig registers as a guest.
 */
@Singleton
class VoucherStore @Inject constructor(
    @ApplicationContext context: Context,
) : VoucherSink {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // commit(): the voucher must be on disk before the key it vouches for is used at clock-in.
    @SuppressLint("ApplySharedPref")
    override fun save(voucher: RegistrarVoucher) {
        prefs.edit(commit = true) {
            putString(K_AUTHORITY, voucher.authority.toBase58())
            putString(K_P256, voucher.p256Pubkey.joinToString("") { "%02x".format(it) })
            putInt(K_LEVEL, voucher.level)
            putString(K_EXPIRY, voucher.expirySlot.toString())
            putString(K_DATA, Base64.getEncoder().encodeToString(voucher.instructionData))
        }
    }

    @SuppressLint("ApplySharedPref")
    override fun clear() = prefs.edit(commit = true) { clear() }

    /** The stored voucher, re-verified, or null. */
    fun load(): RegistrarVoucher? = runCatching {
        val authority = Pubkey.fromBase58(prefs.getString(K_AUTHORITY, null) ?: return null)
        val p256 = prefs.getString(K_P256, null)?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray() ?: return null
        val data = Base64.getDecoder().decode(prefs.getString(K_DATA, null) ?: return null)
        val expiry = prefs.getString(K_EXPIRY, null)?.toULong() ?: return null
        RegistrarVoucher.verify(data, authority, p256, prefs.getInt(K_LEVEL, 0), expiry)
    }.getOrNull()

    /** The voucher if it attests exactly [p256] (the current rig key). */
    fun forKey(p256: ByteArray?): RegistrarVoucher? = load()?.takeIf { p256 != null && it.p256Pubkey.contentEquals(p256) }

    private companion object {
        const val FILE = "hd_rig_voucher"
        const val K_AUTHORITY = "authority"
        const val K_P256 = "p256"
        const val K_LEVEL = "level"
        const val K_EXPIRY = "expiry_slot"
        const val K_DATA = "ed25519_ix"
    }
}
