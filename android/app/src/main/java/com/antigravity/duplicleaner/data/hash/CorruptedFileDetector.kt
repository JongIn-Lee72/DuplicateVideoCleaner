package com.antigravity.duplicleaner.data.hash

import com.antigravity.duplicleaner.domain.model.MediaFileItem
import java.io.File
import java.io.FileInputStream

data class CorruptedFileReport(
    val item: MediaFileItem,
    val reason: String
)

object CorruptedFileDetector {

    private val TEMP_EXTENSIONS = setOf("tmp", "part", "crdownload", "download", "partial")

    fun isAllZeroBytes(bytes: ByteArray): Boolean {
        return bytes.isNotEmpty() && bytes.all { it == 0.toByte() }
    }

    fun detectCorruptedReason(file: File): String? {
        if (!file.exists() || !file.isFile) {
            return null
        }

        if (file.length() == 0L) {
            return "0바이트 빈 파일"
        }

        val ext = file.extension.lowercase()
        if (ext in TEMP_EXTENSIONS) {
            return "미완료 임시 다운로드 파일 (.${ext})"
        }

        if (ext in setOf("mp4", "mov", "m4v") && file.length() >= 64L) {
            try {
                FileInputStream(file).use { fis ->
                    val head64 = ByteArray(64)
                    var totalRead = 0
                    while (totalRead < 64) {
                        val bytesRead = fis.read(head64, totalRead, 64 - totalRead)
                        if (bytesRead == -1) break
                        totalRead += bytesRead
                    }
                    if (totalRead == 64 && isAllZeroBytes(head64)) {
                        return "헤더 손상 영상 (0x00 빈 헤더 · 재생 불가)"
                    }
                }
            } catch (_: Exception) {
                // Ignore file read errors and treat as not detected corrupted reason here
            }
        }

        return null
    }
}
