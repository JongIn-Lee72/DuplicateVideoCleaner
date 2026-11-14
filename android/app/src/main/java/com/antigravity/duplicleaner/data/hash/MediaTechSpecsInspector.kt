package com.antigravity.duplicleaner.data.hash

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.util.Locale

data class VideoTechSpecs(
    val videoCodec: String,
    val audioCodec: String?,
    val hasAudioTrack: Boolean,
    val bitrateKbps: Int,
    val rotationDegrees: Int,
    val frameRate: Float
)

object MediaTechSpecsInspector {

    /**
     * MIME 타입 문자열을 사용자 친화적 코덱 명칭으로 정규화합니다.
     */
    fun normalizeCodecLabel(mime: String?): String {
        if (mime.isNullOrBlank()) return "UNKNOWN"
        return when (mime.trim().lowercase(Locale.ROOT)) {
            "video/hevc", "hevc", "h.265", "h265", "hevc/h.265" -> "HEVC/H.265"
            "video/avc", "avc", "h.264", "h264", "avc/h.264" -> "AVC/H.264"
            "video/av01", "av01", "av1" -> "AV1"
            "video/x-vnd.on2.vp9", "vp9" -> "VP9"
            "audio/mp4a-latm", "mp4a-latm", "aac" -> "AAC"
            "audio/mpeg", "mp3" -> "MP3"
            else -> mime.trim().uppercase(Locale.ROOT)
        }
    }

    /**
     * 압축 효율 및 코덱 품질 순위를 반환합니다.
     * AV1(4) > HEVC/H.265(3) > VP9(2) > AVC/H.264(1) > 기타(0)
     */
    fun computeCodecQualityRank(videoCodec: String?): Int {
        if (videoCodec.isNullOrBlank()) return 0
        return when (normalizeCodecLabel(videoCodec)) {
            "AV1" -> 4
            "HEVC/H.265" -> 3
            "VP9" -> 2
            "AVC/H.264" -> 1
            else -> 0
        }
    }

    /**
     * MediaExtractor 및 MediaMetadataRetriever를 활용하여 영상의 기술 사양(코덱, 오디오 여부, 비트레이트, 회전각, FPS)을 추출합니다.
     */
    fun inspectVideoSpecs(
        context: Context,
        uri: Uri,
        fallbackSizeBytes: Long = 0L,
        fallbackDurationMs: Long = 0L
    ): VideoTechSpecs {
        var rawVideoCodec: String? = null
        var rawAudioCodec: String? = null
        var hasAudio = false
        var extractedBitrate = 0
        var rotation = 0
        var frameRate = 0f

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackCount = extractor.trackCount
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = if (format.containsKey(MediaFormat.KEY_MIME)) {
                    format.getString(MediaFormat.KEY_MIME)
                } else {
                    null
                }
                if (mime != null) {
                    if (mime.startsWith("video/") && rawVideoCodec == null) {
                        rawVideoCodec = mime
                        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            frameRate = try {
                                format.getFloat(MediaFormat.KEY_FRAME_RATE)
                            } catch (_: Exception) {
                                try {
                                    format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                                } catch (_: Exception) {
                                    0f
                                }
                            }
                        }
                        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                            rotation = try {
                                format.getInteger(MediaFormat.KEY_ROTATION)
                            } catch (_: Exception) {
                                0
                            }
                        }
                        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            extractedBitrate = try {
                                format.getInteger(MediaFormat.KEY_BIT_RATE)
                            } catch (_: Exception) {
                                0
                            }
                        }
                    } else if (mime.startsWith("audio/")) {
                        hasAudio = true
                        if (rawAudioCodec == null) {
                            rawAudioCodec = mime
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // MediaExtractor 실패 시 retriever fallback 활용
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            if (rotation == 0) {
                val rotStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                rotation = rotStr?.toIntOrNull() ?: 0
            }
            if (extractedBitrate == 0) {
                val brStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                extractedBitrate = brStr?.toIntOrNull() ?: 0
            }
            if (!hasAudio) {
                val audioStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                if (audioStr != null && (audioStr.equals("yes", ignoreCase = true) || audioStr == "1")) {
                    hasAudio = true
                }
            }
            if (frameRate <= 0f) {
                val captureFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                if (captureFps != null && captureFps > 0f) {
                    frameRate = captureFps
                }
            }
            if (rawVideoCodec == null) {
                val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                if (mime != null && mime.startsWith("video/")) {
                    rawVideoCodec = mime
                }
            }
        } catch (_: Exception) {
            // Retriever 실패 시 fallback
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        val bitrateKbps = if (extractedBitrate >= 1000) {
            extractedBitrate / 1000
        } else if (fallbackDurationMs > 0L) {
            ((fallbackSizeBytes * 8L) / fallbackDurationMs).toInt()
        } else {
            0
        }

        val finalVideoCodec = normalizeCodecLabel(rawVideoCodec ?: "UNKNOWN")
        val finalAudioCodec = if (hasAudio) {
            if (rawAudioCodec != null) normalizeCodecLabel(rawAudioCodec) else "AUDIO"
        } else {
            null
        }

        return VideoTechSpecs(
            videoCodec = finalVideoCodec,
            audioCodec = finalAudioCodec,
            hasAudioTrack = hasAudio,
            bitrateKbps = bitrateKbps,
            rotationDegrees = rotation,
            frameRate = frameRate
        )
    }
}
