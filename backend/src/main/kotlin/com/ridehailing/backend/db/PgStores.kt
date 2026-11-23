package com.ridehailing.backend.db

import com.ridehailing.backend.auth.Device
import com.ridehailing.backend.auth.DeviceStore
import com.ridehailing.backend.auth.Ids
import com.ridehailing.backend.auth.Role
import com.ridehailing.backend.auth.User
import com.ridehailing.backend.auth.UserStatus
import com.ridehailing.backend.auth.UserStore
import com.ridehailing.backend.events.EventChain
import com.ridehailing.backend.events.EventRecord
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Postgres-backed stores (P1-3e). One connection per operation (no pool yet -
 * the CI/compose volumes are low-traffic; a pool lands with the P1-5 config
 * service if load demands it). Schema: Flyway V1/V2 (db/migration).
 *
 * The PG stores implement the SAME ports as the in-memory ones, so tests run
 * against in-memory locally (no Docker in the sandbox, STATE degraded notes)
 * and against real Postgres in CI (backend-test job service container).
 */
class PgUserStore(private val connect: () -> Connection) : UserStore {

    private fun <T> withConn(block: (Connection) -> T): T = connect().use { block(it) }

    private fun ResultSet.toUser(): User = User(
        id = getString("id"),
        phone = getString("phone"),
        email = getString("email"),
        passwordHash = getString("password_hash"),
        displayName = getString("display_name"),
        role = Role.fromStored(getString("role")) ?: Role.RIDER,
        status = UserStatus.entries.firstOrNull { it.name == getString("status") } ?: UserStatus.ACTIVE,
        locale = getString("locale"),
        createdAtEpochSec = getTimestamp("created_at").toInstant().epochSecond,
    )

    override fun findById(id: String): User? = withConn { c ->
        c.prepareStatement("SELECT * FROM app_user WHERE id = ?::uuid").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toUser() else null }
        }
    }

    override fun findByPhone(phone: String): User? = withConn { c ->
        c.prepareStatement("SELECT * FROM app_user WHERE phone = ?").use { ps ->
            ps.setString(1, phone)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toUser() else null }
        }
    }

    override fun findByEmail(email: String): User? = withConn { c ->
        c.prepareStatement("SELECT * FROM app_user WHERE email = ?").use { ps ->
            ps.setString(1, email)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toUser() else null }
        }
    }

    override fun insert(user: User): User = withConn { c ->
        c.prepareStatement(
            "INSERT INTO app_user (id, phone, email, password_hash, display_name, role, status, locale, created_at) " +
                "VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, user.id)
            ps.setString(2, user.phone)
            ps.setString(3, user.email)
            ps.setString(4, user.passwordHash)
            ps.setString(5, user.displayName)
            ps.setString(6, user.role.name)
            ps.setString(7, user.status.name)
            ps.setString(8, user.locale)
            ps.setTimestamp(9, Timestamp.from(Instant.ofEpochSecond(user.createdAtEpochSec)))
            ps.executeUpdate()
        }
        user
    }

    override fun update(user: User): User = withConn { c ->
        c.prepareStatement(
            "UPDATE app_user SET phone = ?, email = ?, password_hash = ?, display_name = ?, role = ?, status = ?, locale = ? " +
                "WHERE id = ?::uuid",
        ).use { ps ->
            ps.setString(1, user.phone)
            ps.setString(2, user.email)
            ps.setString(3, user.passwordHash)
            ps.setString(4, user.displayName)
            ps.setString(5, user.role.name)
            ps.setString(6, user.status.name)
            ps.setString(7, user.locale)
            ps.setString(8, user.id)
            ps.executeUpdate()
        }
        user
    }

    override fun search(query: String?, limit: Int, offset: Int): List<User> = withConn { c ->
        val like = query?.trim()?.lowercase()?.let { "%$it%" }
        val sql = if (like == null) {
            "SELECT * FROM app_user ORDER BY created_at ASC, id ASC LIMIT ? OFFSET ?"
        } else {
            "SELECT * FROM app_user WHERE lower(phone) LIKE ? OR lower(email) LIKE ? OR lower(display_name) LIKE ? " +
                "ORDER BY created_at ASC, id ASC LIMIT ? OFFSET ?"
        }
        c.prepareStatement(sql).use { ps ->
            if (like == null) {
                ps.setInt(1, limit)
                ps.setInt(2, offset)
            } else {
                ps.setString(1, like)
                ps.setString(2, like)
                ps.setString(3, like)
                ps.setInt(4, limit)
                ps.setInt(5, offset)
            }
            ps.executeQuery().use { rs ->
                val out = mutableListOf<User>()
                while (rs.next()) out.add(rs.toUser())
                out
            }
        }
    }
}

class PgDeviceStore(private val connect: () -> Connection) : DeviceStore {

    private fun <T> withConn(block: (Connection) -> T): T = connect().use { block(it) }

    // mesh_public_key is BYTEA on the wire; the port carries a hex string.
    private fun hexToBytes(hex: String?): ByteArray? = hex?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()

    private fun bytesToHex(bytes: ByteArray?): String? = bytes?.joinToString("") { "%02x".format(it) }

    private fun ResultSet.toDevice(): Device = Device(
        id = getString("id"),
        userId = getString("user_id"),
        deviceName = getString("device_name"),
        meshPublicKeyHex = bytesToHex(getBytes("mesh_public_key")),
        lastSeenEpochSec = getTimestamp("last_seen")?.toInstant()?.epochSecond,
        createdAtEpochSec = getTimestamp("created_at").toInstant().epochSecond,
    )

    override fun upsert(userId: String, label: String, meshPublicKeyHex: String?, atSec: Long): Device =
        withConn { c ->
            c.setAutoCommit(false)
            try {
                var existing: Device? = null
                c.prepareStatement("SELECT * FROM device WHERE user_id = ?::uuid AND device_name = ? FOR UPDATE").use { ps ->
                    ps.setString(1, userId)
                    ps.setString(2, label)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) existing = rs.toDevice()
                    }
                }
                val e = existing
                if (e != null) {
                    val updated = e.copy(
                        meshPublicKeyHex = meshPublicKeyHex ?: e.meshPublicKeyHex,
                        lastSeenEpochSec = atSec,
                    )
                    c.prepareStatement("UPDATE device SET mesh_public_key = ?, last_seen = ? WHERE id = ?::uuid").use { up ->
                        up.setBytes(1, hexToBytes(updated.meshPublicKeyHex))
                        up.setTimestamp(2, Timestamp.from(Instant.ofEpochSecond(atSec)))
                        up.setString(3, updated.id)
                        up.executeUpdate()
                    }
                    c.commit()
                    return@withConn updated
                }
                val id = Ids.newId()
                c.prepareStatement(
                    "INSERT INTO device (id, user_id, device_name, mesh_public_key, last_seen, created_at) " +
                        "VALUES (?::uuid, ?::uuid, ?, ?, ?, ?)",
                ).use { ps ->
                    ps.setString(1, id)
                    ps.setString(2, userId)
                    ps.setString(3, label)
                    ps.setBytes(4, hexToBytes(meshPublicKeyHex))
                    ps.setTimestamp(5, Timestamp.from(Instant.ofEpochSecond(atSec)))
                    ps.setTimestamp(6, Timestamp.from(Instant.ofEpochSecond(atSec)))
                    ps.executeUpdate()
                }
                c.commit()
                Device(id, userId, label, meshPublicKeyHex, atSec, atSec)
            } catch (t: Throwable) {
                c.rollback()
                throw t
            } finally {
                c.autoCommit = true
            }
        }

    override fun findById(id: String): Device? = withConn { c ->
        c.prepareStatement("SELECT * FROM device WHERE id = ?::uuid").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toDevice() else null }
        }
    }

    override fun listForUser(userId: String): List<Device> = withConn { c ->
        c.prepareStatement("SELECT * FROM device WHERE user_id = ?::uuid ORDER BY created_at ASC").use { ps ->
            ps.setString(1, userId)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Device>()
                while (rs.next()) out.add(rs.toDevice())
                out
            }
        }
    }

    override fun touch(deviceId: String, atSec: Long) {
        withConn { c ->
            c.prepareStatement("UPDATE device SET last_seen = ? WHERE id = ?::uuid").use { ps ->
                ps.setTimestamp(1, Timestamp.from(Instant.ofEpochSecond(atSec)))
                ps.setString(2, deviceId)
                ps.executeUpdate()
            }
        }
    }
}

/**
 * PG-backed event chain (P1-3e): the in-memory chain stays the runtime truth; every
 * appended record is persisted into event_log (V1 schema + V2 occurred_at_text).
 * On process start the chain RELOADS from the table so seq/hash continuity survives
 * restarts - the exact occurredAt STRING (V2) keeps every hash recomputable.
 */
class PgEventStore private constructor(
    private val connect: () -> Connection,
    startingEvents: List<EventRecord>,
) : EventChain(startingEvents = startingEvents) {

    override fun append(type: String, payload: Map<String, Any?>, occurredAt: Instant): EventRecord {
        val record = super.append(type, payload, occurredAt)
        connect().use { c ->
            c.prepareStatement(
                "INSERT INTO event_log (seq, event_id, type, actor_id, payload, payload_text, occurred_at, occurred_at_text, prev_hash, hash) " +
                    "VALUES (?, ?::uuid, ?, NULL, ?::jsonb, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setLong(1, record.seq)
                ps.setString(2, UUID.randomUUID().toString())
                ps.setString(3, record.type)
                ps.setString(4, record.payloadJson)
                ps.setString(5, record.payloadJson)
                ps.setTimestamp(6, Timestamp.from(Instant.parse(record.occurredAt)))
                ps.setString(7, record.occurredAt)
                ps.setString(8, record.prevHash)
                ps.setString(9, record.hash)
                ps.executeUpdate()
            }
        }
        return record
    }

    companion object {
        /** Migrates (V1+V2) and loads the persisted chain for continuity across restarts. */
        fun open(url: String, user: String, password: String): PgEventStore {
            org.flywaydb.core.Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            val records = mutableListOf<EventRecord>()
            DriverManager.getConnection(url, user, password).use { c ->
                c.prepareStatement(
                    "SELECT seq, type, payload_text, occurred_at_text, prev_hash, hash FROM event_log ORDER BY seq ASC",
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            records.add(
                                EventRecord(
                                    seq = rs.getLong("seq"),
                                    type = rs.getString("type"),
                                    payloadJson = rs.getString("payload_text"),
                                    occurredAt = rs.getString("occurred_at_text"),
                                    prevHash = rs.getString("prev_hash"),
                                    hash = rs.getString("hash"),
                                ),
                            )
                        }
                    }
                }
            }
            return PgEventStore({ DriverManager.getConnection(url, user, password) }, records)
        }
    }
}
