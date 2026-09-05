package app.gridfix.android

import app.gridfix.android.billing.AcknowledgementRetryQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcknowledgementRetryQueueTest {
    @Test
    fun `duplicate purchase callbacks cannot acknowledge the same token concurrently`() {
        val queue = AcknowledgementRetryQueue()
        queue.observe("purchase", false, 0)
        val first = queue.nextAttempt(0)!!
        queue.observe("purchase", false, 10)
        queue.retryNow(10)
        assertNull(queue.nextAttempt(10))
        queue.succeeded(first)
        // A query that began before the acknowledgement may still say false.
        queue.observe("purchase", false, 20)
        assertNull(queue.nextAttempt(20))
        assertFalse(queue.hasFailures)
    }

    @Test
    fun `failed acknowledgement retries with bounded backoff and clears on success`() {
        val queue = AcknowledgementRetryQueue()
        queue.observe("purchase", false, 0)
        var now = 0L
        for (backoff in listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L)) {
            queue.failed(queue.nextAttempt(now)!!, now)
            assertTrue(queue.hasFailures)
            assertEquals(backoff, queue.delayUntilNextAttempt(now))
            assertNull(queue.nextAttempt(now + backoff - 1))
            now += backoff
        }
        queue.succeeded(queue.nextAttempt(now)!!)
        assertFalse(queue.hasFailures)
        assertNull(queue.delayUntilNextAttempt(now))
    }

    @Test
    fun `resume and user retry wake failed work without prematurely hiding the error`() {
        val queue = AcknowledgementRetryQueue()
        queue.observe("purchase", false, 0)
        queue.failed(queue.nextAttempt(0)!!, 0)
        queue.retryNow(100)
        assertNotNull(queue.nextAttempt(100))
        assertTrue(queue.hasFailures)
        queue.retryNow(101)
        assertNull(queue.nextAttempt(101))
    }

    @Test
    fun `Play acknowledgement from another query retires work despite a late failure`() {
        val queue = AcknowledgementRetryQueue()
        queue.observe("purchase", false, 0)
        val inFlight = queue.nextAttempt(0)!!
        queue.observe("purchase", true, 10)
        queue.failed(inFlight, 20)
        assertFalse(queue.hasFailures)
        assertNull(queue.nextAttempt(100_000))
    }

    @Test
    fun `refund snapshot retires failed work and ignores its old callback`() {
        val queue = AcknowledgementRetryQueue()
        queue.observe("refunded", false, 0)
        val inFlight = queue.nextAttempt(0)!!
        queue.retainActiveTokens(emptySet())
        queue.failed(inFlight, 10)
        assertFalse(queue.hasFailures)
        assertNull(queue.nextAttempt(100_000))
    }

    @Test
    fun `late responses from an expired attempt cannot resolve a newer attempt`() {
        val queue = AcknowledgementRetryQueue()
        queue.observe("purchase", false, 0)
        val old = queue.nextAttempt(0)!!
        queue.failed(old, 10_000)
        val current = queue.nextAttempt(15_000)!!
        queue.succeeded(old)
        queue.failed(old, 15_000)
        assertTrue(queue.hasFailures)
        assertNull(queue.nextAttempt(15_000))
        queue.succeeded(current)
        assertFalse(queue.hasFailures)
    }

    @Test
    fun `acknowledging one subscription cannot hide another subscriptions failure`() {
        val queue = AcknowledgementRetryQueue()
        queue.observe("monthly", false, 0)
        queue.observe("annual", false, 0)
        queue.failed(queue.nextAttempt(0)!!, 0)
        queue.succeeded(queue.nextAttempt(0)!!)
        assertTrue(queue.hasFailures)
        queue.observe("monthly", true, 10)
        assertFalse(queue.hasFailures)
    }

    @Test
    fun `fresh manager reconstructs unacknowledged work from a purchase query`() {
        val restarted = AcknowledgementRetryQueue()
        restarted.observe("already-acknowledged", true, 0)
        restarted.observe("still-purchased", false, 0)
        val attempt = restarted.nextAttempt(0)!!
        assertEquals("still-purchased", attempt.token)
        assertNull(restarted.nextAttempt(0))
    }
}
