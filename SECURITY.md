# Gallery Backend — Security Architecture & Hardening Guide

This document defines the security model, token rotation procedures, and multi-tenant isolation strategy for `gallery-backend`.

---

## 1. Authentication & Token Role Isolation

Two types of JWT tokens are issued from the backend using distinct claim types:

* **Admin Tokens**: Issued at `/api/auth/login` containing `type: "admin"`. Valid for 12 hours. Used exclusively by the web dashboard to manage devices, trigger pairing, and queue commands.
* **Device Tokens**: Issued at `/api/pairing/exchange` containing `type: "device"` and `tokenVersion`. Long-lived (1 year) but backed by real-time database validation (`token_version` and `revoked` flag checked on every request).

### Token Isolation Enforcement
Role isolation is validated on every endpoint via `auth-admin` and `auth-device` JWT verifiers:
- Admin routes (`/api/devices`, `/api/pairing/generate`, `/api/transfers/prepare`) reject device JWTs with `401 Unauthorized`.
- Device routes (`/api/transfers/{id}/upload`, `/ws/device`) reject admin JWTs with `401 Unauthorized`.
- Verified by automated unit tests in `SecurityTest.kt`.

---

## 2. JWT Secret Rotation Procedure

If `JWT_SECRET` is compromised or rotated as part of routine security maintenance:

### Impact of Secret Rotation
1. **Existing Tokens Invalidated**: Every active Admin JWT and Device JWT instantly fails verification.
2. **Dashboard Users**: Logged-out automatically upon their next API request. Admins must re-authenticate at `/api/auth/login`.
3. **Paired Phones**: Disconnected from WebSocket channels and unable to upload media transfers. 

### Recommended Recovery / Re-Pairing Workflow
1. Update `JWT_SECRET` in `.env` (or environment secrets manager).
2. Restart the backend service (`sudo systemctl restart gallery-backend`).
3. Dashboard users log in again to receive new Admin tokens.
4. Admins generate new 6-digit pairing codes via `/api/pairing/generate`.
5. Mobile apps exchange the new pairing code via `/api/pairing/exchange` to receive fresh Device JWTs.

---

## 3. Network & Transport Security

* **HSTS (Strict-Transport-Security)**: Enabled via Ktor `HSTS` plugin (`maxAgeInSeconds = 31536000`, `includeSubDomains = true`).
* **TLS Termination**: Production environments run behind Caddy or NGINX reverse proxies providing TLS 1.3 / WSS termination.
* **Header Sanitization**: `CallLogging` is configured to exclude sensitive headers (`Authorization`, `Cookie`) from application logs.

---

## 4. Rate Limiting & Brute-Force Prevention

To mitigate brute-force attacks against credentials and short-lived 6-digit pairing codes:
- `/api/auth/login`: Rate limited to **5 requests per minute**.
- `/api/pairing/exchange`: Rate limited to **10 requests per minute**.
- Excess requests return `HTTP 429 Too Many Requests` with a structured `ApiError` JSON response.

---

## 5. Multi-Tenant & Multi-Device Isolation Architecture (Future Roadmap)

Currently, all paired devices belong to the single administrative scope. Before expanding to multi-user or multi-tenant operations, the following database schema modifications must be applied:

### Schema Additions
```sql
ALTER TABLE devices ADD COLUMN owner_id UUID REFERENCES admin_users(id);
ALTER TABLE pairing_codes ADD COLUMN created_by UUID REFERENCES admin_users(id);
```

### Route Query Scoping
1. **Device Listing (`GET /api/devices`)**:
   Filter device queries by the authenticated admin's ID (`Devices.ownerId eq adminId`).
2. **Command Dispatch (`POST /api/devices/{id}/commands`)**:
   Verify ownership (`(Devices.id eq id) and (Devices.ownerId eq adminId)`) before queuing commands.
3. **Transfer Staging (`POST /api/transfers/prepare`)**:
   Ensure `deviceId` belongs to `adminId` prior to staging media files.
```
