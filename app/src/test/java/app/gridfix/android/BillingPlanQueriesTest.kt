package app.gridfix.android

import app.gridfix.android.billing.BillingPlanQueries
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingPlanQueriesTest {
    @Test
    fun `older empty response cannot erase successful retry plans`() {
        val queries = BillingPlanQueries()
        val initial = queries.begin()
        val retry = queries.begin()

        assertTrue(queries.complete(retry)) // The retry returns populated plans first.
        assertFalse(queries.complete(initial)) // The initial query then returns empty.
        assertFalse(queries.expire(initial))
        assertFalse(queries.expire(retry))
    }

    @Test
    fun `older failure cannot replace successful retry status`() {
        val queries = BillingPlanQueries()
        val initial = queries.begin()
        val retry = queries.begin()

        assertTrue(queries.complete(retry))
        // The production callback checks this before its response-code branches.
        assertFalse(queries.complete(initial))
    }

    @Test
    fun `older response and deadline cannot end loading for pending retry`() {
        val queries = BillingPlanQueries()
        val initial = queries.begin()
        val retry = queries.begin()

        assertFalse(queries.complete(initial))
        assertFalse(queries.expire(initial))
        assertTrue(queries.complete(retry))
        assertFalse(queries.expire(retry))
    }

    @Test
    fun `latest unanswered query times out once and can recover before another retry`() {
        val queries = BillingPlanQueries()
        val query = queries.begin()
        assertTrue(queries.expire(query))
        assertFalse(queries.expire(query))
        assertTrue(queries.complete(query))
        assertFalse(queries.complete(query))
    }

    @Test
    fun `timed out query cannot recover over a newer retry`() {
        val queries = BillingPlanQueries()
        val initial = queries.begin()
        assertTrue(queries.expire(initial))
        val retry = queries.begin()
        assertFalse(queries.complete(initial))
        assertTrue(queries.complete(retry))
    }

    @Test
    fun `closing or replacing client rejects its outstanding product response and timer`() {
        val queries = BillingPlanQueries()
        val previousClientQuery = queries.begin()
        queries.clear()
        assertFalse(queries.complete(previousClientQuery))
        assertFalse(queries.expire(previousClientQuery))
        val newClientQuery = queries.begin()
        assertFalse(queries.complete(previousClientQuery))
        assertFalse(queries.expire(previousClientQuery))
        assertTrue(queries.complete(newClientQuery))
    }
}
