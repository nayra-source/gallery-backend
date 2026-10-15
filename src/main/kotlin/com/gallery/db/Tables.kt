package com.gallery.db

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

object Devices : UUIDTable("devices") {
    val name = varchar("name", 255).nullable()
    val hardwareId = varchar("hardware_id", 255).nullable()
    val hasMediaPermission = bool("has_media_permission").default(true)
    val permissionState = varchar("permission_state", 50).default("granted")
    // Lets you revoke a single device's access instantly without rotating a shared secret:
    // bump this integer and old JWTs (which don't carry it) simply stop validating.
    val tokenVersion = integer("token_version").default(1)
    val revoked = bool("revoked").default(false)
    val lastSeenAt = timestamp("last_seen_at").nullable()
    val isOnline = bool("is_online").default(false)
    val createdAt = timestamp("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp)
}

object AdminUsers : UUIDTable("admin_users") {
    val username = varchar("username", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val createdAt = timestamp("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp)
}

// One-time pairing codes used to link a new phone to the backend.
object PairingCodes : org.jetbrains.exposed.sql.Table("pairing_codes") {
    val code = varchar("code", 12)
    val createdAt = timestamp("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp)
    val expiresAt = timestamp("expires_at")
    val used = bool("used").default(false)
    val deviceNameHint = varchar("device_name_hint", 255).nullable()
    override val primaryKey = PrimaryKey(code)
}

object MediaMetadata : UUIDTable("media_metadata") {
    val deviceId = reference("device_id", Devices)
    val mediaId = long("media_id")          // MediaStore id on-device
    val displayName = varchar("display_name", 512).nullable()
    val mimeType = varchar("mime_type", 100).nullable()
    val sizeBytes = long("size_bytes").nullable()
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val durationMs = integer("duration_ms").nullable()
    val takenAt = timestamp("taken_at").nullable()
    val album = varchar("album", 255).nullable()
    val syncedAt = timestamp("synced_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp)
}

object TrashItems : UUIDTable("trash_items") {
    val deviceId = reference("device_id", Devices)
    val mediaId = long("media_id")
    val displayName = varchar("display_name", 512).nullable()
    val deletedBy = varchar("deleted_by", 10) // 'local' | 'remote'
    val deletedAt = timestamp("deleted_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp)
    val purgeAt = timestamp("purge_at").nullable()
    val restored = bool("restored").default(false)
}

object Commands : UUIDTable("commands") {
    val deviceId = reference("device_id", Devices)
    val type = varchar("type", 50)
    val payload = text("payload").nullable() // JSON-encoded
    val status = varchar("status", 20).default("pending") // pending|sent|acked|failed
    val result = text("result").nullable()
    val createdAt = timestamp("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp)
    val completedAt = timestamp("completed_at").nullable()
}

object Transfers : UUIDTable("transfers") {
    val deviceId = reference("device_id", Devices)
    val mediaId = long("media_id")
    val status = varchar("status", 20).default("requested") // requested|uploading|ready|expired|failed
    val storagePath = varchar("storage_path", 1024).nullable()
    val expiresAt = timestamp("expires_at").nullable()
    val createdAt = timestamp("created_at").defaultExpression(org.jetbrains.exposed.sql.javatime.CurrentTimestamp)
}
