package com.gallery.sockets

import com.auth0.jwt.JWT
import com.gallery.auth.JwtConfig
import com.gallery.db.Commands
import com.gallery.db.Devices
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

@Serializable
data class IncomingResult(
    val cmdId: String,
    val status: String, // "ok" | "error"
    val result: String? = null,
    val message: String? = null
)

private const val MAX_WS_FRAME_BYTES = 65536 // 64 KB limit

fun Route.configureDeviceWebSocket(registry: DeviceSocketRegistry) {
    val json = Json { ignoreUnknownKeys = true }

    webSocket("/ws/device") {
        val tokenFromQuery = call.request.queryParameters["token"]
        val tokenFromHeader = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
        val token = tokenFromQuery ?: tokenFromHeader

        val deviceId = token?.let { validateDeviceToken(it) }

        if (deviceId == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid_token"))
            return@webSocket
        }

        registry.register(deviceId, this)
        markOnline(deviceId, true)
        flushPendingCommands(deviceId, registry)

        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                if (text.length > MAX_WS_FRAME_BYTES) {
                    call.application.environment.log.warn("Device WebSocket frame payload exceeded size limit from $deviceId")
                    continue
                }

                val incoming = runCatching { json.decodeFromString<IncomingResult>(text) }.getOrNull()
                    ?: continue

                runCatching {
                    val cmdUuid = UUID.fromString(incoming.cmdId)
                    transaction {
                        Commands.update({ Commands.id eq cmdUuid }) {
                            it[status] = if (incoming.status == "ok") "acked" else "failed"
                            it[result] = incoming.result ?: incoming.message
                            it[completedAt] = Instant.now()
                        }
                    }
                }.onFailure { e ->
                    call.application.environment.log.error("Failed to process device WS result frame: ${incoming.cmdId}", e)
                }
            }
        } finally {
            registry.unregister(deviceId)
            markOnline(deviceId, false)
        }
    }
}

/** Verifies the JWT signature/claims AND that the device hasn't been revoked, same check as the HTTP auth path. */
private fun validateDeviceToken(token: String): String? {
    return try {
        val decoded = JWT.require(JwtConfig.algorithm).withIssuer(JwtConfig.issuer).build().verify(token)
        if (decoded.getClaim("type").asString() != "device") return null

        val deviceId = decoded.getClaim("deviceId").asString()
        val tokenVersion = decoded.getClaim("tokenVersion").asInt()

        val valid = transaction {
            Devices.selectAll().where { Devices.id eq UUID.fromString(deviceId) }.singleOrNull()
                ?.let { row -> !row[Devices.revoked] && row[Devices.tokenVersion] == tokenVersion }
                ?: false
        }
        if (valid) deviceId else null
    } catch (e: Exception) {
        null
    }
}

private fun markOnline(deviceId: String, online: Boolean) = transaction {
    Devices.update({ Devices.id eq UUID.fromString(deviceId) }) {
        it[isOnline] = online
        it[lastSeenAt] = Instant.now()
    }
}

/** Anything queued while the device was offline gets pushed as soon as it reconnects. */
private suspend fun flushPendingCommands(deviceId: String, registry: DeviceSocketRegistry) {
    val pending = transaction {
        Commands.selectAll().where {
            (Commands.deviceId eq UUID.fromString(deviceId)) and (Commands.status eq "pending")
        }.toList()
    }
    pending.forEach { row ->
        val delivered = registry.sendCommand(
            deviceId = deviceId,
            cmdId = row[Commands.id].value.toString(),
            type = row[Commands.type],
            payload = row[Commands.payload]
        )
        if (delivered) {
            transaction {
                Commands.update({ Commands.id eq row[Commands.id] }) { it[status] = "sent" }
            }
        }
    }
}
