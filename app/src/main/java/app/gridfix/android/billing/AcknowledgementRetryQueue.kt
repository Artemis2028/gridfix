package app.gridfix.android.billing

/**
 * In-memory acknowledgement work, driven by Play's purchases on every reconnect
 * and resume. Tokens are never written to logs or included in user messages.
 * Call only from the billing manager's main dispatcher; [now] is monotonic time.
 */
internal class AcknowledgementRetryQueue {
    internal class Attempt internal constructor(val token: String, internal val id: Long)

    private class Pending(var dueAt: Long) {
        var failures = 0
        var attemptId: Long? = null
    }

    private val pending = linkedMapOf<String, Pending>()
    // A query already in flight may still report an acknowledged token as false.
    private val acknowledged = mutableSetOf<String>()
    private var nextAttemptId = 0L

    val hasFailures: Boolean get() = pending.values.any { it.failures > 0 }

    fun observe(token: String, isAcknowledged: Boolean, now: Long) {
        if (isAcknowledged) {
            pending.remove(token)
            acknowledged.add(token)
        } else if (token !in acknowledged) {
            pending.getOrPut(token) { Pending(now) }
        }
    }

    /** Only a full, successful purchase query may retire refunded/replaced tokens. */
    fun retainActiveTokens(active: Set<String>) {
        pending.keys.retainAll(active)
        acknowledged.retainAll(active)
    }

    fun nextAttempt(now: Long): Attempt? {
        val entry = pending.entries.firstOrNull {
            it.value.attemptId == null && it.value.dueAt <= now
        } ?: return null
        val id = ++nextAttemptId
        entry.value.attemptId = id
        return Attempt(entry.key, id)
    }

    fun delayUntilNextAttempt(now: Long): Long? = pending.values
        .filter { it.attemptId == null }
        .minOfOrNull { (it.dueAt - now).coerceAtLeast(0L) }

    fun succeeded(attempt: Attempt) {
        if (pending[attempt.token]?.attemptId != attempt.id) return
        pending.remove(attempt.token)
        acknowledged.add(attempt.token)
    }

    fun failed(attempt: Attempt, now: Long) {
        val item = pending[attempt.token] ?: return
        if (item.attemptId != attempt.id) return
        item.attemptId = null
        item.failures = (item.failures + 1).coerceAtMost(5)
        val backoff = (5_000L shl (item.failures - 1)).coerceAtMost(60_000L)
        item.dueAt = now + backoff
    }

    /** A user retry/resume wakes waiting work without duplicating an in-flight call. */
    fun retryNow(now: Long) {
        pending.values.filter { it.attemptId == null }.forEach { it.dueAt = now }
    }
}
