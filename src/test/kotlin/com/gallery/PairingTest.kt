package com.gallery

import com.gallery.routes.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PairingTest {

    @Test
    fun testPairingFlowAndSingleUseCode() = testApplication {
        val testUser = "pairingadmin"
        val testPass = "pairingpass123"

        application {
            module()
            AdminBootstrap.createIfMissing(testUser, testPass)
        }

        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        // 1. Login as admin
        val loginResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(testUser, testPass))
        }.body<LoginResponse>()

        val token = loginResponse.token

        // 2. Generate a pairing code
        val pairResponse = client.post("/api/pairing/generate") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(GenerateCodeRequest("Test Phone"))
        }.body<GenerateCodeResponse>()

        val code = pairResponse.code
        assertNotNull(code)

        // 3. Exchange pairing code for device token
        val exchangeResponse = client.post("/api/pairing/exchange") {
            contentType(ContentType.Application.Json)
            setBody(ExchangeCodeRequest(code, "Test Device"))
        }

        assertEquals(HttpStatusCode.OK, exchangeResponse.status)
        val exchangeData = exchangeResponse.body<ExchangeCodeResponse>()
        assertNotNull(exchangeData.deviceToken)
        assertNotNull(exchangeData.deviceId)

        // 4. Attempting to reuse the single-use code must fail
        val reuseResponse = client.post("/api/pairing/exchange") {
            contentType(ContentType.Application.Json)
            setBody(ExchangeCodeRequest(code, "Test Device"))
        }

        assertEquals(HttpStatusCode.Unauthorized, reuseResponse.status)
    }
}
