package com.gallery

import com.gallery.auth.JwtConfig
import com.gallery.routes.AdminBootstrap
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class SecurityTest {

    @Test
    fun testRoleBasedJwtIsolation() = testApplication {
        application {
            module()
            AdminBootstrap.createIfMissing("admin", "pass123")
        }

        val client = createClient {}

        val adminToken = JwtConfig.generateAdminToken(adminId = "admin-uuid-1", username = "admin")
        val deviceToken = JwtConfig.generateDeviceToken(deviceId = "device-uuid-1", tokenVersion = 1)

        // 1. Device token attempting to access Admin route must be rejected (401 Unauthorized)
        val deviceOnAdminRouteResponse = client.get("/api/devices") {
            header(HttpHeaders.Authorization, "Bearer $deviceToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, deviceOnAdminRouteResponse.status)

        // 2. Admin token attempting to access Device-only route must be rejected (401 Unauthorized)
        val adminOnDeviceRouteResponse = client.post("/api/transfers/test-transfer-id/upload") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, adminOnDeviceRouteResponse.status)

        // 3. Admin token accessing Admin route succeeds
        val adminOnAdminRouteResponse = client.get("/api/devices") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, adminOnAdminRouteResponse.status)
    }
}
