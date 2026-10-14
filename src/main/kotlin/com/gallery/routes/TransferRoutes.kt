package com.gallery.routes

import com.gallery.auth.deviceId
import com.gallery.common.respondError
import com.gallery.db.Devices
import com.gallery.db.Transfers
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Serializable
data class PrepareDownloadRequest(val deviceId: String, val mediaId: Long)

@Serializable
data class PrepareDownloadResponse(val transferId: String, val status: String)

@Serializable
data class TransferStatusResponse(val status: String)

private val stagingDir = File(System.getenv("STAGING_DIR") ?: "./staging").apply { mkdirs() }
private const val TTL_MINUTES = 30L
private const val MAX_UPLOAD_BYTES = 500L * 1024L * 1024L // 500 MB limit

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

fun Route.configureTransferRoutes() {
    route("/api/transfers") {

        // Dashboard: "I want to download this media item" -> creates a pending transfer
        authenticate("auth-admin") {
            post("/prepare") {
                val req = call.receive<PrepareDownloadRequest>()
                if (req.mediaId <= 0) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "invalid_media_id", "Media ID must be positive")
                }

                val deviceUuid = req.deviceId.toUuidOrNull()
                    ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_uuid", "Invalid Device UUID format")

                val activeDevice = transaction {
                    Devices.selectAll().where { (Devices.id eq deviceUuid) and (Devices.revoked eq false) }.singleOrNull()
                }
                if (activeDevice == null) {
                    return@post call.respondError(HttpStatusCode.NotFound, "device_not_found", "Active device not found")
                }

                val transferId = transaction {
                    Transfers.insert {
                        it[deviceId] = deviceUuid
                        it[mediaId] = req.mediaId
                        it[status] = "requested"
                        it[expiresAt] = Instant.now().plus(TTL_MINUTES, ChronoUnit.MINUTES)
                    } get Transfers.id
                }
                call.respond(PrepareDownloadResponse(transferId.value.toString(), "requested"))
            }

            // Dashboard polls this until status=ready
            get("/{id}/status") {
                val idStr = call.parameters["id"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_parameter", "Transfer ID is required")
                val transferUuid = idStr.toUuidOrNull() ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_uuid", "Invalid Transfer UUID format")

                val row = transaction {
                    Transfers.selectAll().where { Transfers.id eq transferUuid }.singleOrNull()
                } ?: return@get call.respondError(HttpStatusCode.NotFound, "not_found", "Transfer not found")
                call.respond(TransferStatusResponse(row[Transfers.status]))
            }

            // Dashboard downloads the staged file once ready
            get("/{id}/download") {
                val idStr = call.parameters["id"] ?: return@get call.respondError(HttpStatusCode.BadRequest, "missing_parameter", "Transfer ID is required")
                val transferUuid = idStr.toUuidOrNull() ?: return@get call.respondError(HttpStatusCode.BadRequest, "invalid_uuid", "Invalid Transfer UUID format")

                val row = transaction {
                    Transfers.selectAll().where { Transfers.id eq transferUuid }.singleOrNull()
                } ?: return@get call.respondError(HttpStatusCode.NotFound, "not_found", "Transfer not found")

                if (row[Transfers.status] != "ready" || row[Transfers.storagePath] == null) {
                    return@get call.respondError(HttpStatusCode.Conflict, "not_ready", "File transfer is not ready yet")
                }
                val file = File(row[Transfers.storagePath]!!)
                if (!file.exists()) return@get call.respondError(HttpStatusCode.Gone, "file_gone", "Staged file no longer exists")

                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.name)
                        .toString()
                )
                call.respondFile(file)
            }

            // Dashboard can explicitly clean up the staged file after a successful download
            delete("/{id}") {
                val idStr = call.parameters["id"] ?: return@delete call.respondError(HttpStatusCode.BadRequest, "missing_parameter", "Transfer ID is required")
                val transferUuid = idStr.toUuidOrNull() ?: return@delete call.respondError(HttpStatusCode.BadRequest, "invalid_uuid", "Invalid Transfer UUID format")

                val row = transaction {
                    Transfers.selectAll().where { Transfers.id eq transferUuid }.singleOrNull()
                } ?: return@delete call.respondError(HttpStatusCode.NotFound, "not_found", "Transfer not found")
                
                row[Transfers.storagePath]?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                    }
                }

                transaction {
                    Transfers.update({ Transfers.id eq transferUuid }) {
                        it[status] = "deleted"
                    }
                }
                
                call.respond(HttpStatusCode.NoContent)
            }
        }

        // Phone: uploads the actual bytes once ready
        authenticate("auth-device") {
            post("/{id}/upload") {
                val idStr = call.parameters["id"] ?: return@post call.respondError(HttpStatusCode.BadRequest, "missing_parameter", "Transfer ID is required")
                val transferUuid = idStr.toUuidOrNull() ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_uuid", "Invalid Transfer UUID format")

                val principal = call.principal<JWTPrincipal>()
                val authenticatedDeviceId = principal?.deviceId()
                    ?: return@post call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "Missing device token")

                val transferRow = transaction {
                    Transfers.selectAll().where { Transfers.id eq transferUuid }.singleOrNull()
                } ?: return@post call.respondError(HttpStatusCode.NotFound, "not_found", "Transfer not found")

                // Enforce device ownership
                if (transferRow[Transfers.deviceId].value.toString() != authenticatedDeviceId) {
                    return@post call.respondError(HttpStatusCode.Forbidden, "forbidden", "Transfer belongs to a different device")
                }

                // Check transfer state
                val currentStatus = transferRow[Transfers.status]
                if (currentStatus !in listOf("requested", "uploading")) {
                    return@post call.respondError(HttpStatusCode.Conflict, "invalid_status", "Transfer is not in requested/uploading state")
                }

                // Check expiration
                if (transferRow[Transfers.expiresAt]?.isBefore(Instant.now()) == true) {
                    return@post call.respondError(HttpStatusCode.Gone, "expired", "Transfer has expired")
                }

                val multipart = call.receiveMultipart()
                var savedPath: String? = null

                try {
                    multipart.forEachPart { part ->
                        if (part is PartData.FileItem) {
                            val rawFileName = part.originalFileName ?: "media"
                            val sanitizedFileName = File(rawFileName).name
                            val fileName = "$idStr-$sanitizedFileName"
                            val target = File(stagingDir, fileName)

                            if (target.canonicalPath.startsWith(stagingDir.canonicalPath)) {
                                var bytesWritten = 0L
                                part.streamProvider().use { input ->
                                    target.outputStream().use { output ->
                                        val buffer = ByteArray(8192)
                                        var bytes = input.read(buffer)
                                        while (bytes >= 0) {
                                            bytesWritten += bytes
                                            if (bytesWritten > MAX_UPLOAD_BYTES) {
                                                throw IllegalArgumentException("Upload payload exceeds max size limit (500MB)")
                                            }
                                            output.write(buffer, 0, bytes)
                                            bytes = input.read(buffer)
                                        }
                                    }
                                }
                                savedPath = target.absolutePath
                            }
                        }
                        part.dispose()
                    }
                } catch (e: Exception) {
                    savedPath?.let { File(it).delete() }
                    return@post call.respondError(HttpStatusCode.PayloadTooLarge, "file_too_large", e.message ?: "Upload streaming error")
                }

                if (savedPath == null) {
                    return@post call.respondError(HttpStatusCode.BadRequest, "no_file", "No valid file uploaded")
                }

                transaction {
                    Transfers.update({ Transfers.id eq transferUuid }) {
                        it[status] = "ready"
                        it[storagePath] = savedPath
                    }
                }
                call.respond(TransferStatusResponse("ready"))
            }
        }
    }
}

/**
 * Cleanup job: delete staged files (and mark rows "expired") past their TTL.
 */
object TransferCleanup {
    fun purgeExpired() {
        val now = Instant.now()
        val expired = transaction {
            Transfers.selectAll().where {
                (Transfers.status inList listOf("requested", "uploading", "ready")) and (Transfers.expiresAt less now)
            }.toList()
        }
        expired.forEach { row ->
            row[Transfers.storagePath]?.let { File(it).delete() }
            transaction {
                Transfers.update({ Transfers.id eq row[Transfers.id] }) {
                    it[status] = "expired"
                }
            }
        }
    }
}
