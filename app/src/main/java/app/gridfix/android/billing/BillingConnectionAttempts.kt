package app.gridfix.android.billing

/**
 * Ownership of the current Play connection and its startup timeout. All calls
 * run on the billing manager's main dispatcher. An expired attempt must never
 * finish, disconnect, or time out a replacement connection.
 */
internal class BillingConnectionAttempts {
    internal class Attempt internal constructor()

    private var current: Attempt? = null
    var isConnecting: Boolean = false
        private set

    /** Repeated retries while an attempt is pending do not extend its timeout. */
    fun begin(): Attempt? {
        if (isConnecting) return null
        return Attempt().also {
            current = it
            isConnecting = true
        }
    }

    fun isCurrent(attempt: Attempt): Boolean = current === attempt

    /** The connected client keeps ownership of subsequent callbacks. */
    fun complete(attempt: Attempt): Boolean {
        if (!isCurrent(attempt)) return false
        isConnecting = false
        return true
    }

    /** Called by this attempt's deadline; a completed connection is unaffected. */
    fun expire(attempt: Attempt): Boolean {
        if (!isConnecting || !isCurrent(attempt)) return false
        clear()
        return true
    }

    fun clear() {
        current = null
        isConnecting = false
    }
}
