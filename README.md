# Gallery Backend (Ktor API & WebSocket Engine)

`gallery-backend` is a high-performance, asynchronous Kotlin server built on Ktor and PostgreSQL. It serves as the secure central command authority for the Gallery remote sync ecosystem, bridging the React web dashboard (`gallery-dashboard`) and paired Android devices (`gallery-android`).

---

## Table of Contents
1. [Architecture & System Overview](#architecture--system-overview)
2. [Technology Stack](#technology-stack)
3. [Project Directory Structure](#project-directory-structure)
4. [Database Schema & Migrations](#database-schema--migrations)
5. [Authentication & Role Isolation](#authentication--role-isolation)
6. [Complete API Endpoint Reference](#complete-api-endpoint-reference)
7. [WebSocket Command Protocol](#websocket-command-protocol)
8. [Configuration & Environment Variables](#configuration--environment-variables)
9. [Local Development & Setup](#local-development--setup)
10. [CLI Admin Seeding](#cli-admin-seeding)
11. [Testing & Quality Assurance](#testing--quality-assurance)
12. [Security & Production Hardening](#security--production-hardening)

---

## Architecture & System Overview

The backend facilitates low-latency remote device command dispatching and temporary media file staging. Heavy binary media data is never permanently stored in PostgreSQL — only metadata and transient staged files exist on disk during active transfer requests.

```
┌────────────────────────┐              ┌────────────────────────┐
│   gallery-dashboard    │              │    gallery-android     │
│  (React / TypeScript)  │              │    (Kotlin / Hilt)     │
└───────────┬────────────┘              └───────────┬────────────┘
            │                                       │
            │ REST API (Admin JWT)                  │ WebSocket & Upload
            │ (Port 8080)                           │ (Device JWT)
            ▼                                       ▼
┌────────────────────────────────────────────────────────────────┐
│                        gallery-backend                         │
│                    (Ktor / Netty / Coroutines)                 │
├────────────────────────────────────────────────────────────────┤
│  • Auth Module (BCrypt / JWT)   • Rate Limiting (Ktor)        │
│  • DeviceSocketRegistry         • Lifecycle Transfer Cleanup   │
│  • Flyway Database Migrations   • HSTS & CORS Origin Guards    │
└───────────┬───────────────────────────────────────┬────────────┘
            │                                       │
            ▼                                       ▼
┌────────────────────────┐              ┌────────────────────────┐
│       PostgreSQL       │              │ Local Staging Storage  │
│  (7 Versioned Tables)  │              │    (./staging/ TTL)    │
└────────────────────────┘              └────────────────────────┘
```

---

## Technology Stack

* **Language**: Kotlin `2.1.20`
* **Server Framework**: Ktor Server `2.3.13` (Netty Engine)
* **Database & ORM**: PostgreSQL 17/18, Exposed ORM `0.53.0`, HikariCP `5.1.0`
* **Migrations**: Flyway `10.20.0`
* **Authentication & Hashing**: Auth0 Java-JWT `4.4.0`, BCrypt (`jbcrypt:0.4`)
* **Serialization**: Kotlinx Serialization `1.7.x` (JSON)
* **Testing**: Ktor Test Host, Testcontainers PostgreSQL `1.20.2`, JUnit 4
* **Build Tooling**: Gradle `9.6.0`, Shadow Plugin (`com.gradleup.shadow:9.0.0-beta10`), OWASP Dependency Check `12.1.0`

---

## Project Directory Structure

```
gallery-backend/
├── src/
│   ├── main/
│   │   ├── kotlin/com/gallery/
│   │   │   ├── Application.kt              # App entrypoint, engine config, plugins, coroutines
│   │   │   ├── SeedAdmin.kt                # CLI entrypoint for ./gradlew seedAdmin
│   │   │   ├── auth/
│   │   │   │   ├── Auth.kt                 # Ktor JWT verifier configurations ("auth-admin", "auth-device")
│   │   │   │   └── JwtConfig.kt            # Token generation & secret management
│   │   │   ├── common/
│   │   │   │   └── ApiError.kt             # Standardized JSON error response model & extensions
│   │   │   ├── db/
│   │   │   │   ├── DatabaseFactory.kt      # HikariCP pool setup & Flyway migration runner
│   │   │   │   └── Tables.kt               # Exposed table schemas
│   │   │   ├── routes/
│   │   │   │   ├── AdminAuthRoutes.kt      # /api/auth/login and AdminBootstrap
│   │   │   │   ├── DeviceRoutes.kt         # /api/devices, /revoke, /commands
│   │   │   │   ├── PairingRoutes.kt        # /api/pairing/generate, /exchange
│   │   │   │   └── TransferRoutes.kt       # /api/transfers/prepare, /status, /download, /upload, DELETE
│   │   │   └── sockets/
│   │   │       ├── DeviceSocketRegistry.kt # ConcurrentHashMap tracking active WebSocket sessions
│   │   │       └── DeviceWebSocket.kt      # /ws/device endpoint & pending command flusher
│   │   └── resources/
│   │       ├── db/migration/
│   │       │   └── V1__init_schema.sql     # Versioned SQL migration for Flyway
│   │       └── logback.xml                 # SLF4J logging configuration
│   └── test/
│       └── kotlin/com/gallery/
│           ├── AdminAuthTest.kt            # Unit tests for BCrypt hashing and admin login
│           ├── PairingTest.kt              # Integration tests for pairing generation & single-use exchange
│           └── SecurityTest.kt             # JWT role isolation tests (admin vs device claim guards)
├── .env.example                            # Configuration environment template
├── build.gradle.kts                        # Gradle build configuration
├── DEPLOYMENT.md                           # Production deployment guide (VPS, systemd, Caddy, HTTPS)
└── SECURITY.md                             # Security architecture & JWT rotation documentation
```

---

## Database Schema & Migrations

Database tables are managed via versioned Flyway migrations executed automatically during `DatabaseFactory.init()` prior to database pool initialization.

### Schema Overview (`V1__init_schema.sql`)

1. **`admin_users`**: Administrative accounts for dashboard access.
   * `id` (UUID, PK), `username` (VARCHAR 255, Unique), `password_hash` (VARCHAR 255), `created_at` (TIMESTAMP).
2. **`devices`**: Paired Android devices.
   * `id` (UUID, PK), `name` (VARCHAR 255), `token_version` (INT, Default 1), `revoked` (BOOLEAN, Default false), `last_seen_at` (TIMESTAMP), `is_online` (BOOLEAN), `created_at` (TIMESTAMP).
3. **`pairing_codes`**: Transient 6-digit one-time pairing credentials.
   * `code` (VARCHAR 12, PK), `created_at` (TIMESTAMP), `expires_at` (TIMESTAMP), `used` (BOOLEAN, Default false), `device_name_hint` (VARCHAR 255).
4. **`commands`**: Command queue dispatched to phones via WebSocket.
   * `id` (UUID, PK), `device_id` (UUID, FK -> devices), `type` (VARCHAR 50), `payload` (TEXT), `status` (VARCHAR 20: `pending`\|`sent`\|`acked`\|`failed`), `result` (TEXT), `created_at` (TIMESTAMP), `completed_at` (TIMESTAMP).
5. **`transfers`**: Staged file download requests.
   * `id` (UUID, PK), `device_id` (UUID, FK -> devices), `media_id` (BIGINT), `status` (VARCHAR 20: `requested`\|`uploading`\|`ready`\|`expired`\|`deleted`\|`failed`), `storage_path` (VARCHAR 1024), `expires_at` (TIMESTAMP), `created_at` (TIMESTAMP).
6. **`media_metadata`**: Synced MediaStore metadata index.
7. **`trash_items`**: Tracked remote/local deletion records.

---

## Authentication & Role Isolation

The backend issues two distinct types of JWT tokens from `JwtConfig`:

| Property | Admin Token | Device Token |
|---|---|---|
| **Audience / Scope** | Dashboard Administration | Phone Client Operations |
| **Claim Type** | `type: "admin"` | `type: "device"` |
| **Payload Claims** | `adminId`, `username` | `deviceId`, `tokenVersion` |
| **Expiry** | 12 Hours | 365 Days |
| **Validation Strategy** | Validated via JWT signature & issuer | Checked against DB (`Devices.revoked` & `tokenVersion`) on **every request** |

### Immediate Revocation Mechanism
Because device tokens are long-lived, calling `POST /api/devices/{id}/revoke` increments `tokenVersion` or sets `revoked = true` in PostgreSQL and disconnects the device's WebSocket. Subsequent API calls using the old JWT instantly fail database validation with `401 Unauthorized`.

---

## Complete API Endpoint Reference

All error responses adhere to the standardized `ApiError` model:
```json
{
  "code": "invalid_credentials",
  "message": "Invalid username or password",
  "details": null
}
```

### 1. Health Check
* **`GET /health`**
  * **Auth**: None
  * **Response (200 OK)**: `{"status": "ok"}`

### 2. Admin Authentication
* **`POST /api/auth/login`**
  * **Auth**: None (Rate limited: 5 req/min)
  * **Request Body**:
    ```json
    { "username": "admin", "password": "admin_password123" }
    ```
  * **Response (200 OK)**:
    ```json
    { "token": "<JWT_ADMIN_TOKEN>", "username": "admin" }
    ```
  * **Error (401 Unauthorized)**: `ApiError("invalid_credentials", "Invalid username or password")`

### 3. Device Pairing
* **`POST /api/pairing/generate`**
  * **Auth**: Admin JWT (`auth-admin`)
  * **Request Body**: `{ "deviceNameHint": "Pixel 8 Pro" }`
  * **Response (200 OK)**:
    ```json
    { "code": "920135", "expiresAt": "2026-10-11T09:21:57.113Z" }
    ```
* **`POST /api/pairing/exchange`**
  * **Auth**: None (Rate limited: 10 req/min)
  * **Request Body**: `{ "code": "920135", "deviceName": "Pixel 8 Pro" }`
  * **Response (200 OK)**:
    ```json
    { "deviceToken": "<JWT_DEVICE_TOKEN>", "deviceId": "66d8dfc8-e1ae-4366-9528-7aa4ded23867" }
    ```
  * **Error (401 Unauthorized)**: `ApiError("invalid_or_expired_code", "Pairing code is invalid or expired")`

### 4. Device Management & Commands
* **`GET /api/devices`**
  * **Auth**: Admin JWT (`auth-admin`)
  * **Response (200 OK)**: List of `DeviceSummary` (`id`, `name`, `isOnline`, `lastSeenAt`).
* **`POST /api/devices/{id}/revoke`**
  * **Auth**: Admin JWT (`auth-admin`)
  * **Response (200 OK)**: `{"status": "revoked"}`
* **`POST /api/devices/{id}/commands`**
  * **Auth**: Admin JWT (`auth-admin`)
  * **Request Body**: `{ "type": "LIST_MEDIA", "payload": "{\"limit\":50}" }`
  * **Response (200 OK)**: `{"commandId": "uuid-str", "delivered": true}`

### 5. Media Transfers
* **`POST /api/transfers/prepare`**
  * **Auth**: Admin JWT (`auth-admin`)
  * **Request Body**: `{ "deviceId": "uuid-str", "mediaId": 10024 }`
  * **Response (200 OK)**: `{ "transferId": "uuid-str", "status": "requested" }`
* **`GET /api/transfers/{id}/status`**
  * **Auth**: Admin JWT (`auth-admin`)
  * **Response (200 OK)**: `{ "status": "ready" }`
* **`GET /api/transfers/{id}/download`**
  * **Auth**: Admin JWT (`auth-admin`)
  * **Response (200 OK)**: File binary with `Content-Disposition: attachment; filename="..."` header.
* **`DELETE /api/transfers/{id}`**
  * **Auth**: Admin JWT (`auth-admin`)
  * **Response (204 No Content)**: Immediately deletes staged file from local storage.
* **`POST /api/transfers/{id}/upload`**
  * **Auth**: Device JWT (`auth-device`)
  * **Request Body**: Multipart form data with file stream.
  * **Response (200 OK)**: `{ "status": "ready" }`

---

## WebSocket Command Protocol

Phones maintain a persistent WebSocket channel at:
```
wss://<backend-domain>/ws/device?token=<DEVICE_JWT>
```

When an admin queues a command via `POST /api/devices/{id}/commands`:
1. If online, `DeviceSocketRegistry` transmits an `OutgoingCommand` frame immediately:
   ```json
   { "cmdId": "cmd-uuid", "type": "GET_THUMBNAIL", "payload": "{\"mediaId\":10024}" }
   ```
2. If offline, the command remains in status `pending` in PostgreSQL and is flushed upon device reconnection.
3. The phone processes the command and returns an `IncomingResult` frame:
   ```json
   { "cmdId": "cmd-uuid", "status": "ok", "result": "{\"thumbnailBase64\":\"...\"}" }
   ```

---

## Configuration & Environment Variables

Environment variables are loaded from `.env` (or host environment variables in production):

```env
# Database Settings
DB_URL=jdbc:postgresql://localhost:5432/gallery
DB_USER=gallery_app
DB_PASSWORD=gallery890

# Seed Initial Admin Account on First Boot
ADMIN_USER=admin
ADMIN_PASSWORD=admin_password123

# Security & Secrets
JWT_SECRET=xN/3yh6Z2+9jgPPvcL7TS3IKCa79Ng5SQn3XlYOK7p0dTM2Y9MFD4uGrxuRh1HeA
DASHBOARD_ORIGIN=http://localhost:5173,http://localhost:3000
NVD_API_KEY=531D57D4-4C13-465A-8F1A-E8739235FC4F

# Server Runtime
PORT=8080
STAGING_DIR=./staging
```

---

## Local Development & Setup

### Prerequisites
* JDK 21 or Java 25 Runtime
* PostgreSQL 17/18 installed and running locally
* Gradle 9.6.0

### Database Initialization
```bash
psql -U postgres -c "CREATE DATABASE gallery;"
psql -U postgres -c "CREATE USER gallery_app WITH PASSWORD 'gallery890';"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE gallery TO gallery_app;"
psql -U postgres -d gallery -c "GRANT ALL ON SCHEMA public TO gallery_app; ALTER SCHEMA public OWNER TO gallery_app;"
```

### Running the Server
```bash
./gradlew run
```
_Note: The `run`, `seedAdmin`, and `test` Gradle tasks automatically parse and load variables from `.env`._

---

## CLI Admin Seeding

Seed an administrative account explicitly without exposing public registration endpoints:

```bash
./gradlew seedAdmin --args="admin admin_password123"
```

---

## Testing & Quality Assurance

### Running Unit & Integration Tests
The test suite validates password hashing, admin authentication, pairing single-use enforcement, and JWT role isolation:

```bash
./gradlew test
```

### Building Production Jar
Build a self-contained Fat JAR:
```bash
./gradlew shadowJar
```
Output artifact: `build/libs/gallery-backend-0.1.0-all.jar`.

### Dependency Vulnerability Audit
Execute OWASP Dependency Check using the configured NVD API key:
```bash
./gradlew dependencyCheckAnalyze
```

---

## Security & Production Hardening

1. **HSTS Header**: Forces HTTPS/WSS in browser clients (`maxAge = 1 year`).
2. **CORS Restrictions**: Rejects unauthorized cross-origin requests unless listed in `DASHBOARD_ORIGIN`.
3. **Log Sanitization**: `CallLogging` explicitly masks sensitive headers (`Authorization`).
4. **Lifecycle Staging Cleanup**: Coroutine runs every 60s purging expired staging files past their 30-minute TTL.
5. **Deployment Guide**: See [DEPLOYMENT.md](file:///Users/aryan/AndroidStudioProjects/gallery-backend/DEPLOYMENT.md) for systemd service files, Caddy HTTPS reverse proxy setup, and VPS deployment.
6. **Security Policy**: See [SECURITY.md](file:///Users/aryan/AndroidStudioProjects/gallery-backend/SECURITY.md) for secret rotation and multi-tenant isolation plans.
