package com.ridehailing.core.data.outbox

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ridehailing.core.data.db.AppDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeClock(startMs: Long) : Clock() {
    private var now = startMs
    fun advance(ms: Long) {
        now += ms
    }

    override fun getZone(): ZoneId = ZoneId.of("UTC")
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(now)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: OutboxRepository
    private lateinit var clock: FakeClock

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = FakeClock(1_000)
        repo = OutboxRepository(db.outboxDao(), clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun drain_marksAcceptedRowsSent_keepsLocalTruth() = runTest {
        repo.enqueue("ride.request", "ride-1", """{"rideId":"ride-1"}""")
        repo.enqueue("ride.cancel", "ride-2", """{"rideId":"ride-2"}""")
        assertEquals(2, repo.pendingCount())

        val report = repo.drain(transportOf { _type, _entityId, _payload -> OutboxResult.Accepted("srv-1") })

        assertEquals(DrainReport(sent = 2, rejected = 0, errored = 0, skipped = 0), report)
        assertEquals(0, repo.pendingCount())
        // SENT rows stay as the ack record: unsent queue is empty
        assertEquals(0, db.outboxDao().observeUnsent().first().size)
    }

    @Test
    fun drain_rejectedRow_staysFailedWithServerError() = runTest {
        repo.enqueue("ride.request", "ride-1", "{}")

        val report = repo.drain(transportOf { _type, _entityId, _payload -> OutboxResult.Rejected("invalid_dropoff") })

        assertEquals(1, report.rejected)
        val unsent = db.outboxDao().observeUnsent().first().single()
        assertEquals(OutboxState.FAILED, unsent.state)
        assertEquals("rejected: invalid_dropoff", unsent.lastError)
        assertEquals(1, unsent.attempts)
    }

    @Test
    fun drain_transportException_rowFailed_drainNeverThrows() = runTest {
        repo.enqueue("ride.request", "ride-1", "{}")

        val report = repo.drain(transportOf { _type, _entityId, _payload -> error("socket died") })

        assertEquals(1, report.errored)
        assertEquals(0, report.sent)
        val row = db.outboxDao().observeUnsent().first().single()
        assertEquals(OutboxState.FAILED, row.state)
        assertTrue(row.lastError!!.startsWith("error:"))
    }

    @Test
    fun failedRow_retriesOnNextDrain_andEventuallySends() = runTest {
        repo.enqueue("ride.request", "ride-1", "{}")

        repo.drain(transportOf { _type, _entityId, _payload -> error("offline") })
        clock.advance(30_000)
        val report = repo.drain(transportOf { _type, _entityId, _payload -> OutboxResult.Accepted(null) })

        assertEquals(DrainReport(sent = 1, rejected = 0, errored = 0, skipped = 0), report)
        assertEquals(0, repo.pendingCount())
        assertEquals(0, db.outboxDao().observeUnsent().first().size)
    }

    @Test
    fun drain_withoutTransport_skipsAll_dueRowsUntouched() = runTest {
        repo.enqueue("ride.request", "ride-1", "{}")

        val report = repo.drain(transport = null)

        assertEquals(DrainReport(sent = 0, rejected = 0, errored = 0, skipped = 1), report)
        assertEquals(1, repo.pendingCount())
        assertEquals(OutboxState.PENDING, db.outboxDao().observeUnsent().first().single().state)
    }

    @Test
    fun drain_respectsBatch_oldestFirst() = runTest {
        (1..5).forEach { i ->
            repo.enqueue("ride.request", "ride-$i", "{}")
            clock.advance(10)
        }

        val seen = mutableListOf<String>()
        val report = repo.drain(batch = 3, transport = transportOf { _type, _entityId, _payload ->
            seen.add(_entityId)
            OutboxResult.Accepted(null)
        })

        assertEquals(listOf("ride-1", "ride-2", "ride-3"), seen)
        assertEquals(3, report.sent)
        assertEquals(2, repo.pendingCount())
    }

    @Test
    fun enqueue_rejectsBlankTypeOrEntityId() = runTest {
        try {
            repo.enqueue("  ", "ride-1", "{}")
            throw AssertionError("blank type must throw")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
        try {
            repo.enqueue("ride.request", "", "{}")
            throw AssertionError("blank entityId must throw")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    private fun transportOf(block: suspend (String, String, String) -> OutboxResult): OutboxTransport =
        object : OutboxTransport {
            override suspend fun send(type: String, entityId: String, payloadJson: String): OutboxResult =
                block(type, entityId, payloadJson)
        }
}
