package snd.komf.app.api

class RequestRateLimiter(
    private val requestsPerMinute: Int
) {
    private val requestTimestampsMs = ArrayDeque<Long>()
    private val windowMs = 60_000L

    fun tryAcquire(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (requestsPerMinute <= 0) return true

        synchronized(this) {
            while (requestTimestampsMs.isNotEmpty() && nowMs - requestTimestampsMs.first() >= windowMs) {
                requestTimestampsMs.removeFirst()
            }
            if (requestTimestampsMs.size >= requestsPerMinute) return false
            requestTimestampsMs.addLast(nowMs)
            return true
        }
    }
}
