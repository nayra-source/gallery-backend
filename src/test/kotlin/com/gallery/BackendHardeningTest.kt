package com.gallery

import com.gallery.routes.AdminBootstrap
import com.gallery.routes.LoginRequest
import com.gallery.routes.LoginResponse
import com.gallery.routes.SendCommandRequest
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class BackendHardeningTest {

    @Test
    fun testInvalidUuidRouteParametersReturnBadRequest() = testApplication {
        val testUser = "hardeningadmin"
        val testPass = "hardeningpass123"

        application {
            module()
            AdminBootstrap.createIfMissing(testUser, testPass)
        }

        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val loginResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(testUser, testPass))
        }.body<LoginResponse>()

        val token = loginResponse.token

        // Invalid device UUID format
        val response = client.post("/api/devices/invalid-uuid-string/commands") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(SendCommandRequest("LIST_MEDIA"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun testDisallowedCommandTypeRejected() = testApplication {
        val testUser = "cmdadmin"
        val testPass = "cmdpass123"

        application {
            module()
            AdminBootstrap.createIfMissing(testUser, testPass)
        }

        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val loginResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(testUser, testPass))
        }.body<LoginResponse>()

        val token = loginResponse.token
        val validUuid = "00000000-0000-0000-0000-000000000001"

        val response = client.post("/api/devices/$validUuid/commands") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(SendCommandRequest("UNAUTHORIZED_SHELL_COMMAND"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun testAccountLockoutAfterRepeatedFailures() = testApplication {
        val username = "lockoutuser"
        application {
            module()
            AdminBootstrap.createIfMissing(username, "realPassword123")
        }

        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        // 5 bad login attempts
        repeat(5) {
            val res = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(username, "wrongPassword"))
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)
        }

        // 6th attempt should trigger 429 Too Many Requests lockout
        val lockedRes = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, "realPassword123"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, lockedRes.status)
    }
}
