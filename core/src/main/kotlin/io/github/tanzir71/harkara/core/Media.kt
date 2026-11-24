package io.github.tanzir71.harkara.core

import java.security.MessageDigest
import java.util.Base64

/** Mesh-sized media payload and preview metadata. */
data class MeshMedia(
    /** Encoded image bytes. */
    val bytes: ByteArray,
    /** MIME type for [bytes]. */
    val mimeType: String,
    /** Tiny placeholder string shown until full media arrives. */
    val blurHash: String,
)

/** Media preparation contract for mesh transport; Android adapters do the real WebP encoding first. */
object MediaCompressor {
    /** Bounds image data for mesh transport (48 KB default). */
    fun compressForMesh(source: ByteArray, maxBytes: Int = 48 * 1024): MeshMedia {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val bytes = if (source.size <= maxBytes) source.copyOf() else source.copyOf(maxBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(source)
        val blurHash = Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(8))
        return MeshMedia(bytes = bytes, mimeType = "image/webp", blurHash = blurHash)
    }
}
