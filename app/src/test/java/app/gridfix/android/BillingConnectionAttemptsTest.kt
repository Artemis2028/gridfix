package app.gridfix.android

import app.gridfix.android.billing.BillingConnectionAttempts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingConnectionAttemptsTest {
    @Test
    fun `unanswered connection expires and retry can complete without restarting`() {
        val attempts = BillingConnectionAttempts()
        val unanswered = attempts.begin()!!
        assertTrue(attempts.isConnecting)

        // The manager's deadline fires without any Play callback.
        assertTrue(attempts.expire(unanswered))
        assertFalse(attempts.isConnecting)
        assertFalse(attempts.isCurrent(unanswered))

        val retry = attempts.begin()!!
        assertTrue(attempts.complete(retry))
        assertFalse(attempts.isConnecting)
        assertTrue(attempts.isCurrent(retry))
        // Its old startup timer must not disconnect the now-ready client.
        assertFalse(attempts.expire(retry))
    }

    @Test
    fun `expired callbacks and timers cannot settle or expire the replacement`() {
        val attempts = BillingConnectionAttempts()
        val old = attempts.begin()!!
        assertTrue(attempts.expire(old))
        val retry = attempts.begin()!!

        assertFalse(attempts.complete(old))
        assertFalse(attempts.isCurrent(old))
        assertFalse(attempts.expire(old))
        assertTrue(attempts.isConnecting)
        assertTrue(attempts.isCurrent(retry))
        assertTrue(attempts.complete(retry))
    }

    @Test
    fun `repeated retries cannot replace or postpone a pending attempt`() {
        val attempts = BillingConnectionAttempts()
        val pending = attempts.begin()!!
        repeat(5) { assertNull(attempts.begin()) }
        assertTrue(attempts.isCurrent(pending))
        assertTrue(attempts.expire(pending))

        // Every replacement also has an expirable deadline, not only startup.
        repeat(3) {
            val retry = attempts.begin()!!
            assertTrue(attempts.expire(retry))
            assertFalse(attempts.isConnecting)
        }
        assertNotNull(attempts.begin())
    }

    @Test
    fun `clearing ownership rejects late callbacks and preserves fresh ownership`() {
        val attempts = BillingConnectionAttempts()
        val old = attempts.begin()!!
        assertTrue(attempts.complete(old))
        attempts.clear()
        assertFalse(attempts.complete(old))
        assertFalse(attempts.isCurrent(old))

        val fresh = attempts.begin()!!
        assertFalse(attempts.complete(old))
        assertTrue(attempts.isConnecting)
        assertTrue(attempts.complete(fresh))
    }
}
