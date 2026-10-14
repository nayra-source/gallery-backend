package com.gallery

import com.gallery.routes.AdminBootstrap
import com.gallery.routes.LoginRequest
import com.gallery.routes.LoginResponse
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.mindrot.jbcrypt.BCrypt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdminAuthTest {

    @Test
    fun testPasswordHashingVerification() {
        val rawPassword = "securePassword123"
        val hash = BCrypt.hashpw(rawPassword, BCrypt.gensalt(10))
        assertTrue(BCrypt.checkpw(rawPassword, hash))
    }

    @Test
    fun testAdminLoginFlow() = testApplication {
        application {
            module()
            AdminBootstrap.createIfMissing("testadmin", "password123")
        }

        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        // Correct login
        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("testadmin", "password123"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val loginResponse = response.body<LoginResponse>()
        assertEquals("testadmin", loginResponse.username)
        assertNotNull(loginResponse.token)

        // Invalid password
        val badResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("testadmin", "wrongpass"))
        }
        assertEquals(HttpStatusCode.Unauthorized, badResponse.status)
    }
}
