package com.antigravity.duplicleaner.data.hash

import java.io.File

object FileIntegrityGuard {

    fun normalizeMountAlias(path: String): String {
        var standardized = path.replace('\\', '/')
        // On Windows JVM, absolute POSIX paths like /sdcard/ can get drive prefixes attached e.g. C:/sdcard/
        if (standardized.length >= 2 && standardized[1] == ':' && standardized.getOrNull(2) == '/') {
            standardized = standardized.substring(2)
        }
        return when {
            standardized.startsWith("/sdcard/") -> "/storage/emulated/0/" + standardized.removePrefix("/sdcard/")
            standardized == "/sdcard" -> "/storage/emulated/0"
            standardized.startsWith("/mnt/sdcard/") -> "/storage/emulated/0/" + standardized.removePrefix("/mnt/sdcard/")
            standardized == "/mnt/sdcard" -> "/storage/emulated/0"
            else -> standardized
        }
    }

    fun resolveCanonicalKey(file: File): String {
        val originalNormalized = normalizeMountAlias(file.path)
        val candidateFile = if (originalNormalized != file.path.replace('\\', '/')) {
            File(originalNormalized)
        } else {
            file
        }

        val rawPath = try {
            candidateFile.canonicalPath
        } catch (_: Exception) {
            candidateFile.absolutePath
        }
        return normalizeMountAlias(rawPath)
    }

    fun deduplicateByCanonicalPath(paths: List<String>): List<String> {
        val seenKeys = HashSet<String>()
        val result = mutableListOf<String>()

        for (path in paths) {
            val key = resolveCanonicalKey(File(path))
            if (seenKeys.add(key)) {
                result.add(path)
            }
        }

        return result
    }
}
