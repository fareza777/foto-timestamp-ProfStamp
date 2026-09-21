package com.proofstamp.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "photos",
    indices = [
        Index("capturedAt"),
        Index("sessionId"),
        Index("project"),
        Index(value = ["verificationCode"], unique = true),
        Index("contentHash"),
    ],
)
data class PhotoEntity(
    /** Unique Photo ID, e.g. PS-20250920-7KQ2M9XA. */
    @PrimaryKey val id: String,
    /** Short human-friendly code shown on the photo, e.g. 7KQ2-M9XA. */
    val verificationCode: String,
    /** Absolute path to the sealed JPEG inside app-private storage. */
    val filePath: String,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val capturedAt: Long,
    val timeZoneId: String,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Float?,
    val altitudeM: Double?,
    val placeName: String?,
    val project: String,
    val operator: String,
    val note: String,
    val sessionId: String?,
    val sequence: Int?,
    val template: String,
    /** SHA-256 of the final JPEG bytes on disk. */
    val contentHash: String,
    /** SHA-256 over contentHash + canonical metadata; this is what gets signed. */
    val proofHash: String,
    /** Base64 ECDSA signature of proofHash made with the on-device Keystore key. */
    val signature: String,
    /** Base64 X.509 public key used, so records stay verifiable if the key rotates. */
    val publicKey: String,
    val deviceModel: String,
    val appVersion: String,
    /** True when a signed C2PA manifest (Content Credentials) is embedded in the JPEG. */
    @ColumnInfo(defaultValue = "0")
    val c2pa: Boolean = false,
)

@Entity(tableName = "sessions", indices = [Index("startedAt")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val project: String,
    val operator: String,
    val startedAt: Long,
    val endedAt: Long?,
    val photoCount: Int,
)

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val project: String,
    val operator: String,
    val locationLabel: String,
    val defaultNote: String,
    val sortOrder: Int,
)
