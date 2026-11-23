package com.ridehailing.backend.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * Device record (P1-3 sessions/devices; device table in Flyway V1). One user may
 * have many devices; sessions (refresh families) link to the device they were
 * created from, so "sessions/devices" and force-logout-by-device compose.
 */
data class Device(
    val id: String,
    val userId: String,
    val deviceName: String,
    val meshPublicKeyHex: String?,
    val lastSeenEpochSec: Long?,
    val createdAtEpochSec: Long,
)

/**
 * Persistence port for devices. Upsert is keyed on (userId, deviceName): the same
 * physical device logging in again keeps ONE row and refreshes lastSeen. The PG
 * implementation lands with the P1-3e database wiring (same V1 schema).
 */
interface DeviceStore {
    fun upsert(userId: String, label: String, meshPublicKeyHex: String? = null, atSec: Long): Device
    fun findById(id: String): Device?
    fun listForUser(userId: String): List<Device>
    fun touch(deviceId: String, atSec: Long)
}

class InMemoryDeviceStore : DeviceStore {
    private val byId = LinkedHashMap<String, Device>()

    @Synchronized
    override fun upsert(userId: String, label: String, meshPublicKeyHex: String?, atSec: Long): Device {
        val existing = byId.values.firstOrNull { it.userId == userId && it.deviceName == label }
        if (existing != null) {
            val updated = existing.copy(meshPublicKeyHex = meshPublicKeyHex ?: existing.meshPublicKeyHex, lastSeenEpochSec = atSec)
            byId[updated.id] = updated
            return updated
        }
        val device = Device(
            id = Ids.newId(), userId = userId, deviceName = label,
            meshPublicKeyHex = meshPublicKeyHex, lastSeenEpochSec = atSec, createdAtEpochSec = atSec,
        )
        byId[device.id] = device
        return device
    }

    @Synchronized
    override fun findById(id: String): Device? = byId[id]

    @Synchronized
    override fun listForUser(userId: String): List<Device> =
        byId.values.filter { it.userId == userId }.sortedBy { it.createdAtEpochSec }

    @Synchronized
    override fun touch(deviceId: String, atSec: Long) {
        val d = byId[deviceId] ?: return
        byId[deviceId] = d.copy(lastSeenEpochSec = atSec)
    }
}
