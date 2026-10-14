package com.gallery.routes

import com.gallery.auth.JwtConfig
import com.gallery.common.respondError
import com.gallery.db.AdminUsers
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt

import java.util.concurrent.ConcurrentHashMap

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val token: String, val username: String)

private data class LockoutState(var attempts: Int, var lastAttemptMs: Long, var lockedUntilMs: Long = 0L)
private val loginAttempts = ConcurrentHashMap<String, LockoutState>()
private const val MAX_FAILED_ATTEMPTS = 5
private const val LOCKOUT_DURATION_MS = 15L * 60L * 1000L // 15 minutes
private const val DUMMY_HASH = "\$2a\$12\$e823n9X0dJzN29S38e239e239e239e239e239e239e239e239e239"

fun Route.configureAdminAuthRoutes() {
    route("/api/auth") {
        rateLimit(RateLimitName("auth-login")) {
            post("/login") {
                val req = call.receive<LoginRequest>()
                val username = req.username.trim()
                val now = System.currentTimeMillis()

                val state = loginAttempts.computeIfAbsent(username) { LockoutState(0, now) }
                if (state.lockedUntilMs > now) {
                    val remainingMins = ((state.lockedUntilMs - now) / 60000) + 1
                    return@post call.respondError(
                        HttpStatusCode.TooManyRequests,
                        "account_locked",
                        "Account locked due to multiple failed login attempts. Please try again in $remainingMins minute(s)."
                    )
                }

                val admin = transaction {
                    AdminUsers.selectAll().where { AdminUsers.username eq username }.singleOrNull()
                }

                val passwordValid = if (admin != null) {
                    BCrypt.checkpw(req.password, admin[AdminUsers.passwordHash])
                } else {
                    BCrypt.checkpw(req.password, DUMMY_HASH)
                    false
                }

                if (admin == null || !passwordValid) {
                    state.attempts++
                    state.lastAttemptMs = now
                    if (state.attempts >= MAX_FAILED_ATTEMPTS) {
                        state.lockedUntilMs = now + LOCKOUT_DURATION_MS
                    }
                    call.respondError(HttpStatusCode.Unauthorized, "invalid_credentials", "Invalid username or password")
                    return@post
                }

                // Successful login resets lockout tracking
                loginAttempts.remove(username)

                val token = JwtConfig.generateAdminToken(
                    adminId = admin[AdminUsers.id].value.toString(),
                    username = admin[AdminUsers.username]
                )
                call.respond(LoginResponse(token = token, username = admin[AdminUsers.username]))
            }
        }
    }
}

/**
 * One-time bootstrap helper: creates the single admin account if the table is empty.
 */
object AdminBootstrap {
    fun createIfMissing(username: String, plaintextPassword: String) = transaction {
        val exists = AdminUsers.selectAll().where { AdminUsers.username eq username }.any()
        if (!exists) {
            AdminUsers.insert {
                it[AdminUsers.username] = username
                it[AdminUsers.passwordHash] = BCrypt.hashpw(plaintextPassword, BCrypt.gensalt(12))
            }
        }
    }
}
