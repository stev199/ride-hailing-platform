package com.ridehailing.mesh.routing

import com.ridehailing.mesh.protocol.Flags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceRouterTest {
    private class CountingLink(
        private val failures: MutableMap<String, Int>, // node -> remaining failures before success
    ) : SourceRouter.HopLink {
        val attemptsByNode = mutableMapOf<String, Int>()
        override fun deliver(to: NodeAddress, envelope: com.ridehailing.mesh.protocol.Envelope): Boolean {
            attemptsByNode[to.value] = (attemptsByNode[to.value] ?: 0) + 1
            val remaining = failures[to.value] ?: 0
            return if (remaining > 0) {
                failures[to.value] = remaining - 1
                false
            } else {
                true
            }
        }
    }

    private fun path(vararg nodes: String) = CandidatePath(
        nodes.map { PathHop(NodeAddress(it), 1.0, 0.0, 0.0, 0.0) },
    )

    @Test
    fun `delivers along the full path when every hop accepts`() {
        val link = CountingLink(mutableMapOf())
        val router = SourceRouter()
        val env = testEnvelope("rider", "q1", type = com.ridehailing.mesh.protocol.MessageType.QUOTE)
        val res = router.send(env, path("a", "b", "gw"), null, link)
        assertEquals(SourceRouter.SendResult.Delivered, res)
        assertEquals(listOf(1, 1, 1), listOf("a", "b", "gw").map { link.attemptsByNode[it] })
    }

    @Test
    fun `failing hop retries 3 times then switches to the backup path`() {
        val link = CountingLink(mutableMapOf("b" to 4)) // all 4 attempts (1 try + 3 retries) die at b
        val router = SourceRouter()
        val env = testEnvelope("rider", "q2")
        val res = router.send(env, path("a", "b", "gw"), path("x", "gw"), link)
        assertTrue(res is SourceRouter.SendResult.Failed && res.usedBackup, "backup used: $res")
        assertEquals(4, link.attemptsByNode["b"], "initial try + 3 retries on the dead hop")
        assertEquals(1, link.attemptsByNode["x"])
    }

    @Test
    fun `retry plan is 100-200-400ms per spec`() {
        assertEquals(listOf(100L, 200L, 400L), SourceRouter().retryPlan())
    }

    @Test
    fun `both paths dead falls back to scoped flood then store-and-forward`() {
        val link = CountingLink(mutableMapOf("b" to 4, "x" to 4))
        val router = SourceRouter()
        val env = testEnvelope("rider", "q3")
        val store = StoreAndForward(MeshClock { 0L })
        var flooded = false
        var storedDelivered = false
        val res = router.send(
            env, path("a", "b", "gw"), path("x", "gw"), link,
            storeAndForward = store,
            onScopedFlood = {
                flooded = true
                false // flood also fails
            },
        )
        assertTrue(res is SourceRouter.SendResult.Failed && res.storedForLater, "stored: $res")
        assertTrue(flooded)
        assertEquals(1, store.size())
        // Later the store delivers (scenario 10: queued messages deliver after healing).
        val receipt = object : StoreAndForward.Receipt {
            override fun onOutcome(queued: StoreAndForward.Queued, delivered: Boolean) {
                storedDelivered = delivered
            }
        }
        store.enqueue(env, null, 10_000, receipt)
        store.acknowledge(store.drainReady().last().id)
        assertTrue(storedDelivered)
    }

    @Test
    fun `scoped flood success reports success without storage`() {
        val link = CountingLink(mutableMapOf("b" to 4, "x" to 4))
        val router = SourceRouter()
        val env = testEnvelope("rider", "q4")
        val store = StoreAndForward(MeshClock { 0L })
        val res = router.send(
            env, path("a", "b", "gw"), path("x", "gw"), link,
            storeAndForward = store,
            onScopedFlood = { true },
        )
        assertTrue(res is SourceRouter.SendResult.Failed && res.scopedFlooded && !res.storedForLater)
        assertEquals(0, store.size())
    }

    @Test
    fun `critical messages may ride the top-2 paths in parallel (admin toggle on)`() {
        val router = SourceRouter(SourceRouter.Config(parallelCritical = true))
        val env = testEnvelope("rider", "q5", type = com.ridehailing.mesh.protocol.MessageType.QUOTE).let {
            it.copy(flags = it.flags or Flags.CRITICAL)
        }
        val link = CountingLink(mutableMapOf())
        val res = router.send(env, path("a", "gw"), path("x", "gw"), link)
        assertEquals(SourceRouter.SendResult.Delivered, res)
        assertEquals(1, link.attemptsByNode["a"])
        assertEquals(0, link.attemptsByNode["x"] ?: 0, "non-critical flag or toggle-off stops after best path")
    }

    @Test
    fun `without critical flag only the best path is attempted even with toggle on`() {
        val router = SourceRouter(SourceRouter.Config(parallelCritical = true))
        val env = testEnvelope("rider", "q6")
        val link = CountingLink(mutableMapOf())
        router.send(env, path("a", "gw"), path("x", "gw"), link)
        assertEquals(1, link.attemptsByNode["a"])
        assertEquals(0, link.attemptsByNode["x"] ?: 0)
    }
}

class StoreAndForwardTest {
    @Test
    fun `expired entries are dropped and reported (scenario 10)`() {
        var now = 0L
        val store = StoreAndForward(MeshClock { now })
        val outcomes = mutableListOf<Boolean>()
        val id = store.enqueue(
            testEnvelope("o", "s1"),
            null,
            expiresAtMs = 1_000,
            StoreAndForward.Receipt { _, delivered -> outcomes.add(delivered) },
        )
        now = 2_000
        assertEquals(0, store.drainReady().size, "expired entries not returned as ready")
        assertEquals(listOf(false), outcomes, "expiry reported as not delivered")
    }

    @Test
    fun `capacity cap drops oldest with receipt`() {
        var now = 0L
        val store = StoreAndForward(MeshClock { now }, capacity = 2)
        val outcomes = mutableListOf<String>()
        repeat(3) { i ->
            store.enqueue(
                testEnvelope("o", "s$i"),
                null,
                expiresAtMs = 10_000,
                StoreAndForward.Receipt { q, delivered -> outcomes.add("${q.envelope.msgId.decodeToString()}:$delivered") },
            )
        }
        assertEquals(2, store.size())
        assertEquals(listOf("s0:false"), outcomes, "oldest evicted with receipt")
    }
}
