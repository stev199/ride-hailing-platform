package com.ridehailing.backend.db

import com.ridehailing.backend.rides.Ride
import com.ridehailing.backend.rides.RideStatus
import com.ridehailing.backend.rides.RideStore
import java.sql.Connection
import java.sql.ResultSet

/**
 * P4-2 PG-backed RideStore (V5 migration: rides + ride_stops). Same contract as
 * [com.ridehailing.backend.rides.InMemoryRideStore]; RideStoreContractTest runs
 * identical scenarios against both (in-memory locally, this one on CI's PG container).
 */
class PgRideStore(private val connect: () -> Connection) : RideStore {

    private fun ResultSet.toRide(): Ride = Ride(
        id = getString("id"),
        riderId = getString("rider_id"),
        driverId = getString("driver_id"),
        city = getString("city"),
        rideType = getString("ride_type"),
        status = RideStatus.valueOf(getString("status")),
        paymentMethod = getString("payment_method"),
        note = getString("note"),
        pickupLat = getDouble("pickup_lat"),
        pickupLon = getDouble("pickup_lon"),
        dropoffLat = getDouble("dropoff_lat"),
        dropoffLon = getDouble("dropoff_lon"),
        pickupName = null, // stop names live in ride_stops; the ride row is the source of truth for coords
        dropoffName = null,
        quoteJson = getString("quote_json"),
        quoteSignature = getString("quote_signature"),
        quoteAmountMinor = getLong("quote_amount_minor"),
        quoteCurrency = getString("quote_currency"),
        quoteMode = getString("quote_mode"),
        quoteMeteredCapMinor = getLong("quote_metered_cap_minor"),
        quoteIssuedAtEpochSec = getTimestamp("quote_issued_at").time / 1000,
        quoteValidUntilEpochSec = getTimestamp("quote_valid_until").time / 1000,
        cancelFeeMinor = getLong("cancel_fee_minor"),
        createdAtEpochSec = getTimestamp("created_at").time / 1000,
        updatedAtEpochSec = getTimestamp("updated_at").time / 1000,
    )

    override fun insert(ride: Ride): Ride = connect().use { c ->
        c.autoCommit = false
        try {
            c.prepareStatement(
                "INSERT INTO rides (id, rider_id, status, city, ride_type, payment_method, note, " +
                    "pickup_lat, pickup_lon, dropoff_lat, dropoff_lon, quote_json, quote_signature, " +
                    "quote_amount_minor, quote_currency, quote_mode, quote_metered_cap_minor, " +
                    "quote_valid_until, quote_issued_at, cancel_fee_minor, created_at, updated_at) " +
                    "VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, to_timestamp(?), to_timestamp(?))",
            ).use { ps ->
                ps.setString(1, ride.id)
                ps.setString(2, ride.riderId)
                ps.setString(3, ride.status.name)
                ps.setString(4, ride.city)
                ps.setString(5, ride.rideType)
                ps.setString(6, ride.paymentMethod)
                ps.setString(7, ride.note)
                ps.setDouble(8, ride.pickupLat)
                ps.setDouble(9, ride.pickupLon)
                ps.setDouble(10, ride.dropoffLat)
                ps.setDouble(11, ride.dropoffLon)
                ps.setString(12, ride.quoteJson)
                ps.setString(13, ride.quoteSignature)
                ps.setLong(14, ride.quoteAmountMinor)
                ps.setString(15, ride.quoteCurrency)
                ps.setString(16, ride.quoteMode)
                ps.setLong(17, ride.quoteMeteredCapMinor)
                ps.setLong(18, ride.quoteValidUntilEpochSec)
                ps.setLong(19, ride.quoteIssuedAtEpochSec)
                ps.setLong(20, ride.cancelFeeMinor)
                ps.setLong(21, ride.createdAtEpochSec)
                ps.setLong(22, ride.updatedAtEpochSec)
                ps.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO ride_stops (ride_id, kind, sequence, name, lat, lon) VALUES (?::uuid, 'PICKUP', 0, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, ride.id)
                ps.setString(2, ride.pickupName)
                ps.setDouble(3, ride.pickupLat)
                ps.setDouble(4, ride.pickupLon)
                ps.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO ride_stops (ride_id, kind, sequence, name, lat, lon) VALUES (?::uuid, 'DROPOFF', 1, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, ride.id)
                ps.setString(2, ride.dropoffName)
                ps.setDouble(3, ride.dropoffLat)
                ps.setDouble(4, ride.dropoffLon)
                ps.executeUpdate()
            }
            c.commit()
            ride
        } catch (e: Exception) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    override fun byId(id: String): Ride? = connect().use { c ->
        c.prepareStatement("SELECT * FROM rides WHERE id = ?::uuid").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toRide() else null }
        }
    }

    override fun updateStatus(id: String, status: RideStatus, nowEpochSec: Long, cancelFeeMinor: Long): Ride? =
        connect().use { c ->
            c.prepareStatement(
                "UPDATE rides SET status = ?, updated_at = to_timestamp(?), " +
                    "cancel_fee_minor = GREATEST(cancel_fee_minor, ?) WHERE id = ?::uuid " +
                    "RETURNING *",
            ).use { ps ->
                ps.setString(1, status.name)
                ps.setLong(2, nowEpochSec)
                ps.setLong(3, cancelFeeMinor)
                ps.setString(4, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toRide() else null }
            }
        }

    override fun forRider(riderId: String, limit: Int, offset: Int): List<Ride> = connect().use { c ->
        c.prepareStatement(
            "SELECT * FROM rides WHERE rider_id = ?::uuid ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
        ).use { ps ->
            ps.setString(1, riderId)
            ps.setInt(2, limit)
            ps.setInt(3, offset)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Ride>()
                while (rs.next()) out.add(rs.toRide())
                out
            }
        }
    }

    override fun available(limit: Int): List<Ride> = connect().use { c ->
        c.prepareStatement(
            "SELECT * FROM rides WHERE status = 'REQUESTED' ORDER BY created_at ASC, id LIMIT ?",
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Ride>()
                while (rs.next()) out.add(rs.toRide())
                out
            }
        }
    }

    override fun claimForDriver(id: String, driverId: String, nowEpochSec: Long): Ride? = connect().use { c ->
        // ATOMIC single-winner: the status guard is IN the UPDATE (row-locked); two
        // concurrent claims yield exactly one RETURNING row (spec edge case list).
        c.prepareStatement(
            "UPDATE rides SET status = 'ACCEPTED', driver_id = ?::uuid, updated_at = to_timestamp(?) " +
                "WHERE id = ?::uuid AND status = 'REQUESTED' RETURNING *",
        ).use { ps ->
            ps.setString(1, driverId)
            ps.setLong(2, nowEpochSec)
            ps.setString(3, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toRide() else null }
        }
    }

    override fun forDriver(driverId: String, limit: Int, offset: Int): List<Ride> = connect().use { c ->
        c.prepareStatement(
            "SELECT * FROM rides WHERE driver_id = ?::uuid ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
        ).use { ps ->
            ps.setString(1, driverId)
            ps.setInt(2, limit)
            ps.setInt(3, offset)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<Ride>()
                while (rs.next()) out.add(rs.toRide())
                out
            }
        }
    }
}
