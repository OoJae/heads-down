package xyz.headsdown.core.chain

import xyz.headsdown.core.chain.rpc.AccountInfo
import xyz.headsdown.core.keys.RigSignalState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** heads_down / ORE accounts laid out per INTERFACE / docs/ORE.md, for composer and service tests. */
object TestAccounts {
    fun configBytes(executorFee: Long = 10_000, paused: Boolean = false): ByteArray =
        ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0, 1); put(1, 1); put(2, HeadsDownProgram.config.bump.toByte())
            position(8); put(ByteArray(32) { 1 })
            position(40); put(ByteArray(32) { 2 })
            putLong(72, 7_000); putLong(80, executorFee); putShort(88, 2_000); put(90, if (paused) 1 else 0)
            put(91, HeadsDownProgram.executor.bump.toByte())
        }.array()

    fun rigBytes(
        authority: Pubkey,
        p256: ByteArray,
        state: RigSignalState = RigSignalState.IDLE,
        shiftId: Long = 0,
        hbCounter: Long = 0,
    ): ByteArray = ByteBuffer.allocate(384).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(0, 2); put(1, 1); put(2, HeadsDownProgram.rig(authority).bump.toByte())
        position(8); put(authority.bytes)
        position(40); put(p256)
        put(75, state.wire.toByte())
        putLong(200, shiftId); putLong(208, hbCounter)
    }.array()

    fun automationBytes(
        authority: Pubkey,
        amount: Long,
        balance: Long,
        executor: Pubkey = HeadsDownProgram.executor.address,
        fee: Long = 10_000,
        strategy: Long = 2,
        reload: Long = 1,
    ): ByteArray = ByteBuffer.allocate(160).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(0, 100)
        putLong(8, amount)
        position(16); put(authority.bytes)
        putLong(48, balance)
        position(56); put(executor.bytes)
        putLong(88, fee); putLong(96, strategy); putLong(112, reload)
        putLong(136, -1); putShort(146, -1)
    }.array()

    fun info(owner: Pubkey, data: ByteArray) = AccountInfo(1_000_000uL, owner, data, executable = false)

    /** An account as the RPC `value` JSON. */
    fun json(owner: Pubkey, data: ByteArray): String =
        """{"data":["${Base64.getEncoder().encodeToString(data)}","base64"],"executable":false,"lamports":1000000,"owner":"$owner","rentEpoch":18446744073709551615,"space":${data.size}}"""
}
