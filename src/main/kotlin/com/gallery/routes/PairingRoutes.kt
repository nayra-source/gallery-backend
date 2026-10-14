package com.gallery.routes

import com.gallery.auth.JwtConfig
import com.gallery.common.respondError
import com.gallery.db.Devices
import com.gallery.db.PairingCodes
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit

@Serializable
data class GenerateCodeRequest(val deviceNameHint: String? = null)

@Serializable
data class GenerateCodeResponse(val code: String, val expiresAt: String)

@Serializable
data class ExchangeCodeRequest(val code: String, val deviceName: String? = null)

@Serializable
data class ExchangeCodeResponse(val deviceToken: String, val deviceId: String)

private val secureRandom = SecureRandom()

/** 6-digit numeric code — easy to type on a phone, short enough to be usable, random enough for a 10-min TTL. */
private fun generatePairingCode(): String =
    (100000 + secureRandom.nextInt(900000)).toString()

fun Route.configurePairingRoutes() {
    route("/api/pairing") {

        // Called from the DASHBOARD (admin-authenticated) to start pairing a new phone.
        authenticate("auth-admin") {
            post("/generate") {
                val req = call.receive<GenerateCodeRequest>()
                val code = generatePairingCode()
                val expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES)

                transaction {
                    // Opportunistic cleanup of expired pairing codes
                    PairingCodes.deleteWhere { PairingCodes.expiresAt less Instant.now() }

                    PairingCodes.insert {
                        it[PairingCodes.code] = code
                        it[PairingCodes.expiresAt] = expiresAt
                        it[PairingCodes.used] = false
                        it[PairingCodes.deviceNameHint] = req.deviceNameHint
                    }
                }

                call.respond(GenerateCodeResponse(code = code, expiresAt = expiresAt.toString()))
            }
        }

        // Called from the PHONE (unauthenticated — this IS the authentication step)
        // Rate limited to 10 req/min
        rateLimit(RateLimitName("pairing-exchange")) {
            post("/exchange") {
                val req = call.receive<ExchangeCodeRequest>()
                val now = Instant.now()
                var deviceNameHint: String? = null

                // Atomic conditional update to prevent race conditions on code usage
                val updatedCount = transaction {
                    val pairing = PairingCodes.selectAll().where {
                        (PairingCodes.code eq req.code) and (PairingCodes.used eq false) and (PairingCodes.expiresAt greater now)
                    }.singleOrNull()

                    if (pairing != null) {
                        deviceNameHint = pairing[PairingCodes.deviceNameHint]
                        PairingCodes.update({
                            (PairingCodes.code eq req.code) and (PairingCodes.used eq false)
                        }) {
                            it[used] = true
                        }
                    } else 0
                }

                if (updatedCount == 0) {
                    call.respondError(HttpStatusCode.Unauthorized, "invalid_or_expired_code", "Pairing code is invalid or expired")
                    return@post
                }

                val deviceId = transaction {
                    Devices.insert {
                        it[Devices.name] = req.deviceName ?: deviceNameHint
                        it[Devices.isOnline] = false
                    } get Devices.id
                }

                val token = JwtConfig.generateDeviceToken(deviceId.value.toString(), tokenVersion = 1)
                call.respond(ExchangeCodeResponse(deviceToken = token, deviceId = deviceId.value.toString()))
            }
        }
    }
}
