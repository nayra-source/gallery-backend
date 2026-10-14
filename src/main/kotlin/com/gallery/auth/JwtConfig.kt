package com.gallery.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Two kinds of tokens are issued from the same secret infra but with different
 * claims/audiences so a device token can never be used where an admin token
 * is expected, and vice versa:
 *
 *  - "admin"  -> issued at dashboard login, short-lived, refreshed by re-login
 *  - "device" -> issued once at pairing time, long-lived, revocable by deleting
 *                the device row (or bumping a per-device token version)
 */
object JwtConfig {
    private val envSecret = System.getenv("JWT_SECRET")
    private val secret = envSecret.takeIf { !it.isNullOrBlank() }
        ?: "development_jwt_secret_fallback_key_3847293847298374".also {
            println("WARNING: JWT_SECRET environment variable is not set. Using default insecure fallback key!")
        }

    const val issuer = "gallery-backend"
    val algorithm: Algorithm = Algorithm.HMAC256(secret)

    fun generateAdminToken(adminId: String, username: String): String =
        JWT.create()
            .withIssuer(issuer)
            .withClaim("type", "admin")
            .withClaim("adminId", adminId)
            .withClaim("username", username)
            .withExpiresAt(Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(12)))
            .sign(algorithm)

    fun generateDeviceToken(deviceId: String, tokenVersion: Int): String =
        JWT.create()
            .withIssuer(issuer)
            .withClaim("type", "device")
            .withClaim("deviceId", deviceId)
            .withClaim("tokenVersion", tokenVersion)
            // Long-lived; actual revocation happens via Devices.revoked / Devices.tokenVersion,
            // checked on every authenticated device request, not via JWT expiry.
            .withExpiresAt(Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(365)))
            .sign(algorithm)
}
