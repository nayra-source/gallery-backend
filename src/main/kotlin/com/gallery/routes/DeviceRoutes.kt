package com.gallery.routes

import com.gallery.common.respondError
import com.gallery.db.Commands
import com.gallery.db.Devices
import com.gallery.sockets.DeviceSocketRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

@Serializable
data class DeviceSummary(
    val id: String,
    val name: String?,
    val isOnline: Boolean,
    val lastSeenAt: String?,
    val hasMediaPermission: Boolean = true,
    val permissionState: String = "granted"
)

@Serializable
data class SendCommandRequest(val type: String, val payload: String? = null)

@Serializable
data class SendCommandResponse(val commandId: String, val delivered: Boolean)

@Serializable
data class CommandStatusResponse(
    val commandId: String,
    val status: String,
    val result: String? = null
)

@Serializable
data class SimpleStatusResponse(val status: String)

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

private val ALLOWED_COMMAND_TYPES = setOf("LIST_MEDIA", "GET_THUMBNAIL", "GET_FULL_MEDIA", "DELETE_MEDIA", "RENAME_MEDIA")

fun Route.configureDeviceRoutes(registry: DeviceSocketRegistry) {
    route("/api/devices") {
        authenticate("auth-admin") {

            // List every paired device and whether it's currently connected.
            get {
                val devices = transaction {
                    Devices.selectAll().where { Devices.revoked eq false }.map {
                        DeviceSummary(
                            id = it[Devices.id].value.toString(),
                            name = it[Devices.name],
                            isOnline = registry.isOnline(it[Devices.id].value.toString()),
                            lastSeenAt = it[Devices.lastSeenAt]?.toString(),
                            hasMediaPermission = it[Devices.hasMediaPermission],
                            permissionState = it[Devices.permissionState]
                        )
                    }
                }
                call.respond(devices)
            }

            // Revoke a device immediately - e.g. phone lost/stolen.
            post("/{id}/revoke") {
                val idStr = call.parameters["id"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "missing_parameter", "Device ID is required")
                val deviceUuid = idStr.toUuidOrNull() ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_uuid", "Invalid Device UUID format")

                transaction {
                    Devices.update({ Devices.id eq deviceUuid }) {
                        it[Devices.revoked] = true
                    }
                }
                registry.disconnect(idStr)
                call.respond(SimpleStatusResponse("revoked"))
            }

            // Queue a command for a device.
            post("/{id}/commands") {
                val idStr = call.parameters["id"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "missing_parameter", "Device ID is required")
                val deviceUuid = idStr.toUuidOrNull() ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_uuid", "Invalid Device UUID format")
                val req = call.receive<SendCommandRequest>()

                if (req.type !in ALLOWED_COMMAND_TYPES) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "invalid_command_type", "Command type '${req.type}' is not allowed")
                }

                // Verify device exists and is active (not revoked)
                val deviceRow = transaction {
                    Devices.selectAll().where { (Devices.id eq deviceUuid) and (Devices.revoked eq false) }.singleOrNull()
                }
                if (deviceRow == null) {
                    return@post call.respondError(HttpStatusCode.NotFound, "device_not_found", "Active device not found or has been revoked")
                }

                val commandId = try {
                    transaction {
                        Commands.insert {
                            it[Commands.deviceId] = deviceUuid
                            it[Commands.type] = req.type
                            it[Commands.payload] = req.payload
                            it[Commands.status] = "pending"
                        } get Commands.id
                    }
                } catch (e: Exception) {
                    call.application.environment.log.error("Failed to insert command for device $idStr", e)
                    return@post call.respondError(HttpStatusCode.InternalServerError, "command_error", "Failed to queue command for device")
                }

                val delivered = registry.sendCommand(idStr, commandId.value.toString(), req.type, req.payload)
                if (delivered) {
                    transaction {
                        Commands.update({ Commands.id eq commandId }) { it[Commands.status] = "sent" }
                    }
                }

                call.respond(SendCommandResponse(commandId = commandId.value.toString(), delivered = delivered))
            }

            // Query status of a specific command.
            get("/{id}/commands/{cmdId}") {
                val idStr = call.parameters["id"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_parameter", "Device ID is required")
                val cmdIdStr = call.parameters["cmdId"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_parameter", "Command ID is required")

                val deviceUuid = idStr.toUuidOrNull() ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_uuid", "Invalid Device UUID format")
                val commandUuid = cmdIdStr.toUuidOrNull() ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_uuid", "Invalid Command UUID format")

                val command = transaction {
                    Commands.selectAll().where { (Commands.id eq commandUuid) and (Commands.deviceId eq deviceUuid) }
                        .singleOrNull()
                } ?: return@get call.respondError(HttpStatusCode.NotFound, "command_not_found", "Command not found")

                call.respond(
                    CommandStatusResponse(
                        commandId = command[Commands.id].value.toString(),
                        status = command[Commands.status],
                        result = command[Commands.result]
                    )
                )
            }
        }
    }
}
