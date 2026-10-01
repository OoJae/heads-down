package xyz.headsdown.ml

/**
 * Bytes allocated by the current thread, from HotSpot's `com.sun.management.ThreadMXBean`. Looked
 * up by reflection because Android unit tests compile against android.jar, which has no
 * `java.lang.management`. Tests that need it skip themselves on a JVM without it.
 */
object Allocations {
    private val bean: Any? = runCatching {
        Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
    }.getOrNull()

    private val allocatedBytes = runCatching {
        Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
    }.getOrNull()

    val supported: Boolean = runCatching { read() >= 0 }.getOrDefault(false)

    @Suppress("DEPRECATION") // Thread.threadId() only exists from Java 19
    private fun read(): Long = allocatedBytes!!.invoke(bean, Thread.currentThread().id) as Long

    /** What the two reflective reads themselves allocate (boxing, argument arrays). */
    private fun overhead(): Long {
        var least = Long.MAX_VALUE
        repeat(20) {
            val before = read()
            val after = read()
            least = minOf(least, after - before)
        }
        return least
    }

    /** Bytes [block] allocated on this thread (0 for an allocation-free block). */
    fun during(block: () -> Unit): Long {
        val overhead = overhead()
        val before = read()
        block()
        val after = read()
        return (after - before - overhead).coerceAtLeast(0)
    }
}
