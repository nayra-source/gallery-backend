# API Contracts — gallery-backend

Source of truth for every HTTP/WebSocket contract between `gallery-android`,
`gallery-dashboard`, and `gallery-backend`. **Update this file in the same commit/PR
that changes a route** — it should never drift from the actual backend code.

Base URL placeholders: `{BASE_URL}` = `https://your-domain` (prod) or
`http://10.0.2.2:8080` (Android emulator → host machine) / `http://localhost:8080`
(dashboard dev). See `CONFIG_STRATEGY.md` for how each client resolves this.

---

## Auth

### `POST /api/auth/login`
**Caller:** Dashboard (admin) · **Auth:** None

Request:
```json
{ "username": "string", "password": "string" }
```
Response `200`:
```json
{ "token": "jwt-string", "username": "string" }
```
Response `401`: `{ "error": "invalid_credentials" }`

---

## Pairing

### `POST /api/pairing/generate`
**Caller:** Dashboard · **Auth:** `Authorization: Bearer <admin-jwt>`

Request:
```json
{ "deviceNameHint": "string | null" }
```
Response `200`:
```json
{ "code": "123456", "expiresAt": "2026-10-10T10:20:00Z" }
```

### `POST /api/pairing/exchange`
**Caller:** Android app · **Auth:** None (the code itself is the credential)

Request:
```json
{ "code": "123456", "deviceName": "string | null" }
```
Response `200`:
```json
{ "deviceToken": "jwt-string", "deviceId": "uuid" }
```
Response `401`: `{ "error": "invalid_or_expired_code" }`

---

## Devices

### `GET /api/devices`
**Caller:** Dashboard · **Auth:** admin-jwt

Response `200`:
```json
[
  { "id": "uuid", "name": "string | null", "isOnline": true, "lastSeenAt": "iso8601 | null" }
]
```

### `POST /api/devices/{id}/revoke`
**Caller:** Dashboard · **Auth:** admin-jwt

Response `200`: `{ "status": "revoked" }`

### `POST /api/devices/{id}/commands`
**Caller:** Dashboard · **Auth:** admin-jwt

Request:
```json
{ "type": "LIST_MEDIA | GET_THUMBNAIL | GET_FULL_MEDIA | DELETE_MEDIA | RENAME_MEDIA", "payload": "string | null (JSON-encoded)" }
```
Response `200`:
```json
{ "commandId": "uuid", "delivered": true }
```
`delivered: false` means the device is offline — command is queued (`status = pending`)
and will be flushed on reconnect.

---

## Device WebSocket

### `WS /ws/device?token={deviceJwt}`
**Caller:** Android app (held open by the foreground service) · **Auth:** device JWT as query param (over WSS only)

**Backend → Device** (command):
```json
{ "cmdId": "uuid", "type": "string", "payload": "string | null" }
```

**Device → Backend** (result):
```json
{ "cmdId": "uuid", "status": "ok | error", "result": "string | null", "message": "string | null" }
```

> Command `payload`/`result` bodies are JSON-encoded strings, not nested objects —
> keeps the envelope stable while per-command shapes evolve. Document each command's
> inner payload/result shape below as they're implemented.

#### `LIST_MEDIA` *(not yet implemented — Phase 8)*
- Payload: `{ "album": "string | null", "cursor": "string | null" }`
- Result: `{ "items": [ { "mediaId": 0, "displayName": "", "mimeType": "", "takenAt": "" } ], "nextCursor": "string | null" }`

#### `GET_THUMBNAIL` *(not yet implemented — Phase 8)*
- Payload: `{ "mediaId": 0 }`
- Result: TBD — likely a reference/URL after upload, not inline bytes

#### `GET_FULL_MEDIA` / prepare-download *(see Transfers below — Phase 8)*

#### `DELETE_MEDIA` *(not yet implemented — Phase 8)*
- Payload: `{ "mediaIds": [0] }`
- Result: `{ "trashed": [0], "failed": [0] }`

#### `RENAME_MEDIA` *(not yet implemented — Phase 8)*
- Payload: `{ "mediaId": 0, "newName": "string" }`
- Result: `{ "success": true }`

---

## Transfers (prepare-download staging)

### `POST /api/transfers/prepare`
**Caller:** Dashboard · **Auth:** admin-jwt

Request:
```json
{ "deviceId": "uuid", "mediaId": 0 }
```
Response `200`:
```json
{ "transferId": "uuid", "status": "requested" }
```
> Dashboard should also send a `GET_FULL_MEDIA` (or similarly-named) command via
> `/api/devices/{id}/commands` carrying this `transferId`, so the device knows
> which transfer to fulfil. Exact command payload TBD in Phase 8.

### `GET /api/transfers/{id}/status`
**Caller:** Dashboard · **Auth:** admin-jwt

Response `200`: `{ "status": "requested | uploading | ready | expired | failed" }`

### `GET /api/transfers/{id}/download`
**Caller:** Dashboard · **Auth:** admin-jwt

Response `200`: binary file stream, `Content-Disposition: attachment`
Response `409`: `{ "error": "not_ready" }`
Response `410`: Gone (file expired/purged)

### `POST /api/transfers/{id}/upload`
**Caller:** Android app · **Auth:** device-jwt

Request: `multipart/form-data`, one file part
Response `200`: `{ "status": "ready" }`

---

## Error shape (target — not fully consistent yet, see roadmap Phase 1)

```json
{ "error": "machine_readable_code", "message": "optional human-readable detail" }
```

## Change log

| Date | Change |
|---|---|
| 2026-10-10 | Initial contracts doc, matching Phase 1 backend skeleton |