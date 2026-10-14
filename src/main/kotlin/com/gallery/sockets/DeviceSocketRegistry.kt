package com.gallery.sockets

import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class OutgoingCommand(
    val cmdId: String,
    val type: String,
    val payload: String? = null
)

/**
 * Tracks which devices currently have an open WebSocket, so admin commands
 * can be routed to the right live connection.
 *
 * Single-instance in-memory map is fine for one backend process. If you ever
 * run multiple backend instances behind a load balancer, this needs to move
 * to something shared (Redis pub/sub) so a command sent to instance A can
 * reach a device connected to instance B.
 */
class DeviceSocketRegistry {
    private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
    private val json = Json { encodeDefaults = true }

    fun register(deviceId: String, session: DefaultWebSocketServerSession) {
        sessions[deviceId] = session
    }

    fun unregister(deviceId: String) {
        sessions.remove(deviceId)
    }

    fun isOnline(deviceId: String): Boolean = sessions.containsKey(deviceId)

    suspend fun disconnect(deviceId: String) {
        sessions[deviceId]?.let { session ->
            runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "revoked")) }
        }
        sessions.remove(deviceId)
    }

    /** Returns true if the command was actually delivered (device currently connected). */
    suspend fun sendCommand(deviceId: String, cmdId: String, type: String, payload: String?): Boolean {
        val session = sessions[deviceId] ?: return false
        return try {
            session.send(Frame.Text(json.encodeToString(OutgoingCommand(cmdId, type, payload))))
            true
        } catch (e: Exception) {
            false
        }
    }
}
