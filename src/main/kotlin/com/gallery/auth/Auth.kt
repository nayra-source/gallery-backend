package com.gallery.auth

import com.auth0.jwt.JWT
import com.gallery.db.Devices
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

fun Application.configureAuth(jwtConfig: JwtConfig) {
    install(Authentication) {
        jwt("auth-admin") {
            verifier(
                JWT.require(jwtConfig.algorithm).withIssuer(jwtConfig.issuer).build()
            )
            validate { credential ->
                if (credential.payload.getClaim("type").asString() == "admin") {
                    JWTPrincipal(credential.payload)
                } else null
            }
        }

        jwt("auth-device") {
            verifier(
                JWT.require(jwtConfig.algorithm).withIssuer(jwtConfig.issuer).build()
            )
            validate { credential ->
                val payload = credential.payload
                if (payload.getClaim("type").asString() != "device") return@validate null

                val deviceId = payload.getClaim("deviceId").asString()
                val tokenVersion = payload.getClaim("tokenVersion").asInt()

                // Check live revocation state on every request - this is what actually
                // makes "revoke this one device" from the dashboard take effect immediately,
                // rather than waiting out a year-long JWT expiry.
                val stillValid = transaction {
                    Devices.selectAll().where { Devices.id eq UUID.fromString(deviceId) }
                        .singleOrNull()
                        ?.let { row -> !row[Devices.revoked] && row[Devices.tokenVersion] == tokenVersion }
                        ?: false
                }

                if (stillValid) JWTPrincipal(payload) else null
            }
        }
    }
}

/** Helper to pull the authenticated device's id out of a validated device JWT. */
fun JWTPrincipal.deviceId(): String = payload.getClaim("deviceId").asString()

/** Helper to pull the authenticated admin's id out of a validated admin JWT. */
fun JWTPrincipal.adminId(): String = payload.getClaim("adminId").asString()
