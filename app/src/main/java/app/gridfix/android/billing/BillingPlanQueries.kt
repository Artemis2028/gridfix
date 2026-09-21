package app.gridfix.android.billing

/**
 * Ownership of product-query responses and deadlines. Use on the billing
 * manager's main dispatcher: a Retry supersedes every older query, even on
 * the same BillingClient connection.
 */
internal class BillingPlanQueries {
    internal class Query internal constructor()

    private var current: Query? = null
    private var waiting = false

    fun begin(): Query = Query().also {
        current = it
        waiting = true
    }

    /** Success, an empty inventory, and failure all use the same acceptance rule. */
    fun complete(query: Query): Boolean {
        if (current !== query) return false
        clear()
        return true
    }

    /**
     * A delayed response may still recover this query after its deadline, but
     * only if no replacement query has begun in the meantime.
     */
    fun expire(query: Query): Boolean {
        if (current !== query || !waiting) return false
        waiting = false
        return true
    }

    fun clear() {
        current = null
        waiting = false
    }
}
