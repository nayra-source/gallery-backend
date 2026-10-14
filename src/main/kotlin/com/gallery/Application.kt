package com.gallery

import com.gallery.auth.JwtConfig
import com.gallery.auth.configureAuth
import com.gallery.common.respondError
import com.gallery.db.DatabaseFactory
import com.gallery.routes.configureAdminAuthRoutes
import com.gallery.routes.configureDeviceRoutes
import com.gallery.routes.configurePairingRoutes
import com.gallery.routes.configureTransferRoutes
import com.gallery.sockets.DeviceSocketRegistry
import com.gallery.sockets.configureDeviceWebSocket
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.hsts.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

fun main() {
    embeddedServer(Netty, port = System.getenv("PORT")?.toIntOrNull() ?: 8080, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

fun Application.module() {
    // --- Database ---
    DatabaseFactory.init()

    // --- JSON ---
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    // --- Logging ---
    // Ensure we do not log sensitive headers like Authorization
    install(CallLogging) { 
        level = Level.INFO 
    }

    // --- HSTS (Strict-Transport-Security) ---
    // Enforces TLS at the browser level if running directly, 
    // or provides defense-in-depth if behind a reverse proxy.
    install(HSTS) {
        includeSubDomains = true
        maxAgeInSeconds = 31536000 // 1 year
    }

    // --- Rate Limiting ---
    install(RateLimit) {
        register(RateLimitName("auth-login")) {
            rateLimiter(limit = 5, refillPeriod = 60.seconds)
        }
        register(RateLimitName("pairing-exchange")) {
            rateLimiter(limit = 10, refillPeriod = 60.seconds)
        }
    }

    // --- CORS Restriction ---
    install(CORS) {
        val dashboardOrigins = System.getenv("DASHBOARD_ORIGIN")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("http://localhost:5173", "http://localhost:3000", "http://127.0.0.1:5173", "http://localhost:4173")

        dashboardOrigins.forEach { origin ->
            val cleanHost = origin.removePrefix("http://").removePrefix("https://")
            allowHost(cleanHost, schemes = listOf("http", "https"))
        }

        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
    }

    // --- Error handling ---
    install(StatusPages) {
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respondError(status, "rate_limit_exceeded", "Rate limit exceeded. Please try again later.")
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respondError(HttpStatusCode.InternalServerError, "internal_error", "An internal error occurred.")
        }
    }

    // --- WebSockets (for device connections) ---
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(30)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    // --- Auth (JWT for admin dashboard AND device tokens) ---
    configureAuth(JwtConfig)

    // --- Seed Admin Account if configured ---
    val initialAdminUser = System.getenv("ADMIN_USER")
    val initialAdminPassword = System.getenv("ADMIN_PASSWORD")
    if (!initialAdminUser.isNullOrBlank() && !initialAdminPassword.isNullOrBlank()) {
        com.gallery.routes.AdminBootstrap.createIfMissing(initialAdminUser, initialAdminPassword)
    }

    // --- Transfer Cleanup Job (lifecycle-managed) ---
    val cleanupJob = launch {
        while (isActive) {
            runCatching { com.gallery.routes.TransferCleanup.purgeExpired() }
            delay(60_000)
        }
    }
    environment.monitor.subscribe(ApplicationStopping) {
        cleanupJob.cancel()
    }

    val deviceSocketRegistry = DeviceSocketRegistry()

    routing {
        configureAdminAuthRoutes()
        configurePairingRoutes()
        configureDeviceRoutes(deviceSocketRegistry)
        configureTransferRoutes()
        configureDeviceWebSocket(deviceSocketRegistry)

@Serializable
data class HealthResponse(val status: String, val db: String)

        get("/health") {
            val dbOk = runCatching {
                org.jetbrains.exposed.sql.transactions.transaction {
                    exec("SELECT 1") { true } ?: true
                }
            }.getOrDefault(false)

            if (dbOk) {
                call.respond(HealthResponse("ok", "connected"))
            } else {
                call.respondError(HttpStatusCode.ServiceUnavailable, "db_unavailable", "Database connectivity check failed")
            }
        }
    }
}
