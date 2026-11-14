package com.antigravity.duplicleaner.domain.model

import android.net.Uri

data class MediaFileItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val absolutePath: String?,
    val sizeBytes: Long,
    val lastModifiedSeconds: Long,
    val mimeType: String,
    val category: FileCategory,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val partialHash: Long? = null,
    val fullHash: String? = null,
    val perceptualHashes: List<Long> = emptyList(),
    val isSelectedForDelete: Boolean = false,
    val keepBadgeReason: String? = null,
    val payloadHash: Long? = null,
    val pHashes: List<Long> = emptyList(),
    val sharpnessScore: Int = 0,
    val authenticityScore: Int = 0,
    val hasAudioTrack: Boolean = true,
    val videoCodec: String? = null,
    val actualBitrateKbps: Int = 0
) {
    val estimatedBitrateKbps: Int
        get() = if (durationMs > 0L) ((sizeBytes * 8L) / durationMs).toInt() else 0

    val effectiveBitrateKbps: Int
        get() = if (actualBitrateKbps > 0) actualBitrateKbps else estimatedBitrateKbps

    val resolutionLabel: String?
        get() = when {
            width <= 0 || height <= 0 -> null
            maxOf(width, height) >= 3840 -> "4K (${width}x${height})"
            maxOf(width, height) >= 2560 -> "QHD (${width}x${height})"
            maxOf(width, height) >= 1920 -> "1080p (${width}x${height})"
            maxOf(width, height) >= 1280 -> "720p (${width}x${height})"
            else -> "${width}x${height}"
        }

    val formattedDuration: String?
        get() {
            if (durationMs <= 0L) return null
            val totalSeconds = durationMs / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }
}

data class DuplicateGroup(
    val groupId: String,
    val signature: String,
    val matchType: MatchType,
    val similarityPercent: Int,
    val category: FileCategory,
    val items: List<MediaFileItem>
) {
    val totalBytes: Long
        get() = items.sumOf { it.sizeBytes }

    val reclaimableBytes: Long
        get() = items.filter { it.isSelectedForDelete }.sumOf { it.sizeBytes }

    val selectedCount: Int
        get() = items.count { it.isSelectedForDelete }

    val isAllSelectedWarning: Boolean
        get() = items.isNotEmpty() && items.all { it.isSelectedForDelete }
}
