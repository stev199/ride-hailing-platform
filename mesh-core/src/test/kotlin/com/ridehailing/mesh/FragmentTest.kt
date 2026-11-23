package com.ridehailing.mesh

import com.ridehailing.mesh.protocol.Fragmenter
import com.ridehailing.mesh.protocol.Reassembler
import com.ridehailing.mesh.protocol.ReassemblyResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FragmentTest {

    private val msgId = ByteArray(16) { (it * 11).toByte() }
    private val origin = ByteArray(8) { 1 }

    @Test
    fun `split and reassemble across mtu sizes in order`() {
        val payload = ByteArray(500) { (it * 13).toByte() }
        for (mtu in listOf(20, 21, 40, 64, 185)) {
            val frags = Fragmenter.split(msgId, payload, mtu)
            val r = Reassembler()
            var result: ReassemblyResult = ReassemblyResult.Waiting
            for (f in frags) {
                val step = r.add(origin, f, nowMs = 0)
                if (step is ReassemblyResult.Complete) result = step
            }
            val completed = result as ReassemblyResult.Complete
            assertTrue(payload.contentEquals(completed.payload), "payload must roundtrip at mtu=$mtu")
        }
    }

    @Test
    fun `reordered fragments still reassemble`() {
        val payload = ByteArray(200) { it.toByte() }
        val frags = Fragmenter.split(msgId, payload, 40)
        val shuffled = frags.reversed() // complete permutation: all fragments, reversed order
        val r = Reassembler()
        var done: ReassemblyResult? = null
        for (f in shuffled) {
            val step = r.add(origin, f, nowMs = 0)
            if (step is ReassemblyResult.Complete) done = step
        }
        val completed = done as ReassemblyResult.Complete
        assertTrue(payload.contentEquals(completed.payload))
    }

    @Test
    fun `duplicate fragments are idempotent`() {
        val payload = ByteArray(120) { (it * 3).toByte() }
        val frags = Fragmenter.split(msgId, payload, 30)
        val r = Reassembler()
        var done: ReassemblyResult? = null
        for (f in frags + frags) { // send twice
            val step = r.add(origin, f, nowMs = 0)
            if (step is ReassemblyResult.Complete) done = step
        }
        val completed = done as ReassemblyResult.Complete
        assertTrue(payload.contentEquals(completed.payload))
    }

    @Test
    fun `loss times out cleanly with bounded memory`() {
        val payload = ByteArray(300) { it.toByte() }
        val frags = Fragmenter.split(msgId, payload, 30).dropLast(1) // lose last fragment
        val r = Reassembler(timeoutMs = 100)
        for (f in frags) r.add(origin, f, nowMs = 0)
        assertEquals(0, r.sweep(nowMs = 50))
        assertEquals(1, r.sweep(nowMs = 200)) // evicted after timeout
        assertEquals(0, r.sweep(nowMs = 300)) // memory bounded
    }

    @Test
    fun `per-origin in-flight cap rejects spam`() {
        val r = Reassembler(maxInFlightPerOrigin = 2)
        for (n in 0 until 2) {
            val id = ByteArray(16) { (it + n * 16).toByte() }
            val frag = Fragmenter.split(id, ByteArray(100), 30).first()
            assertEquals(ReassemblyResult.Waiting, r.add(origin, frag, nowMs = 0))
        }
        val third = Fragmenter.split(ByteArray(16) { 99 }, ByteArray(100), 30).first()
        val rejected = r.add(origin, third, nowMs = 0)
        assertEquals(ReassemblyResult.Rejected("per-origin in-flight cap"), rejected)
    }

    @Test
    fun `byte cap bounds a single oversized message`() {
        val r = Reassembler(maxMessageBytes = 100)
        val id = ByteArray(16) { 5 }
        val frags = Fragmenter.split(id, ByteArray(400), 30)
        var last: ReassemblyResult? = null
        for (f in frags) last = r.add(origin, f, nowMs = 0)
        assertTrue(last is ReassemblyResult.Rejected || last is ReassemblyResult.Waiting)
    }
}
