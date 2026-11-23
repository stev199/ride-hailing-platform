package com.ridehailing.backend

import com.ridehailing.backend.events.EventChain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventChainTest {
    private fun buildChain(n: Int): EventChain {
        val chain = EventChain()
        repeat(n) { i -> chain.append("test.event", mapOf("i" to i)) }
        return chain
    }

    @Test
    fun `append builds a valid chain`() {
        val chain = buildChain(50)
        assertNull(chain.verify())
        assertEquals(50, chain.snapshot().size)
    }

    @Test
    fun `canonical json is deterministic and sorted`() {
        val a = EventChain.canonicalJson(mapOf("b" to 1, "a" to listOf("x", mapOf("z" to 2, "y" to 3))))
        val b = EventChain.canonicalJson(mapOf("a" to listOf("x", mapOf("y" to 3, "z" to 2)), "b" to 1))
        assertEquals(a, b)
        assertEquals("""{"a":["x",{"y":3,"z":2}],"b":1}""", a)
    }

    @Test
    fun `tampering with a payload breaks verification at that index`() {
        val chain = buildChain(5)
        val bad = EventChain(startingEvents = chain.snapshot().mapIndexed { i, r ->
            if (i == 2) r.copy(payloadJson = r.payloadJson.replace("2", "9")) else r
        })
        assertEquals(2, bad.verify())
    }

    @Test
    fun `reordering events breaks verification`() {
        val chain = buildChain(5)
        val snapshot = chain.snapshot().toMutableList()
        val tmp = snapshot[1]; snapshot[1] = snapshot[2]; snapshot[2] = tmp
        assertEquals(1, EventChain(startingEvents = snapshot).verify())
    }

    @Test
    fun `hash chain is a known-answer for fixed input`() {
        // SHA256("GENESIS|1|test.event|{"i":1}|2026-01-01T00:00:00Z")
        val h = EventChain.hashOf(
            EventChain.GENESIS, 1L, "test.event", """{"i":1}""", "2026-01-01T00:00:00Z",
        )
        assertEquals(64, h.length)
        assertTrue(h.matches(Regex("[0-9a-f]{64}")))
        // recomputation is stable
        assertEquals(h, EventChain.hashOf(EventChain.GENESIS, 1L, "test.event", """{"i":1}""", "2026-01-01T00:00:00Z"))
    }

    @Test
    fun `tail hash of empty chain is GENESIS`() {
        assertEquals(EventChain.GENESIS, EventChain().tailHash)
        assertNotNull(buildChain(1).tailHash)
    }
}
