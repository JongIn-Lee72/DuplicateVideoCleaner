package com.antigravity.duplicleaner.data.local

import android.content.Context
import android.content.SharedPreferences
import com.antigravity.duplicleaner.ui.theme.AppThemeMode

/**
 * 스캔 제외 키워드, 원본 보호 폴더(화이트리스트), 최소 파일 크기 등
 * 사용자 스캔 설정을 영구 저장하는 SharedPreferences 래퍼.
 */
class WhitelistPreferences(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun getExcludedKeywords(): Set<String> {
        val stored = prefs.getStringSet(KEY_EXCLUDED_KEYWORDS, null)
        return stored?.toSet() ?: DEFAULT_EXCLUDED_KEYWORDS
    }

    fun addExcludedKeyword(keyword: String): Set<String> {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return getExcludedKeywords()
        val updated = LinkedHashSet(getExcludedKeywords()).apply { add(trimmed) }
        prefs.edit().putStringSet(KEY_EXCLUDED_KEYWORDS, HashSet(updated)).apply()
        return updated
    }

    fun removeExcludedKeyword(keyword: String): Set<String> {
        val trimmed = keyword.trim()
        val updated = LinkedHashSet(getExcludedKeywords()).apply { remove(trimmed) }
        prefs.edit().putStringSet(KEY_EXCLUDED_KEYWORDS, HashSet(updated)).apply()
        return updated
    }

    fun getProtectedKeepFolders(): Set<String> {
        val stored = prefs.getStringSet(KEY_PROTECTED_KEEP_FOLDERS, null)
        return stored?.toSet() ?: DEFAULT_PROTECTED_KEEP_FOLDERS
    }

    fun addProtectedKeepFolder(folder: String): Set<String> {
        val trimmed = folder.trim()
        if (trimmed.isEmpty()) return getProtectedKeepFolders()
        val updated = LinkedHashSet(getProtectedKeepFolders()).apply { add(trimmed) }
        prefs.edit().putStringSet(KEY_PROTECTED_KEEP_FOLDERS, HashSet(updated)).apply()
        return updated
    }

    fun removeProtectedKeepFolder(folder: String): Set<String> {
        val trimmed = folder.trim()
        val updated = LinkedHashSet(getProtectedKeepFolders()).apply { remove(trimmed) }
        prefs.edit().putStringSet(KEY_PROTECTED_KEEP_FOLDERS, HashSet(updated)).apply()
        return updated
    }

    fun getMinFileSizeBytes(): Long {
        return prefs.getLong(KEY_MIN_FILE_SIZE_BYTES, DEFAULT_MIN_FILE_SIZE_BYTES)
    }

    fun setMinFileSizeBytes(bytes: Long) {
        val safeBytes = bytes.coerceAtLeast(0L)
        prefs.edit().putLong(KEY_MIN_FILE_SIZE_BYTES, safeBytes).apply()
    }

    fun getSimilarityThresholdPercent(): Int {
        return prefs.getInt(
            KEY_SIMILARITY_THRESHOLD_PERCENT,
            DEFAULT_SIMILARITY_THRESHOLD_PERCENT
        ).coerceIn(MIN_SIMILARITY_THRESHOLD_PERCENT, MAX_SIMILARITY_THRESHOLD_PERCENT)
    }

    fun setSimilarityThresholdPercent(percent: Int) {
        val safePercent = percent.coerceIn(
            MIN_SIMILARITY_THRESHOLD_PERCENT,
            MAX_SIMILARITY_THRESHOLD_PERCENT
        )
        prefs.edit().putInt(KEY_SIMILARITY_THRESHOLD_PERCENT, safePercent).apply()
    }

    fun getIgnoredGroupSignatures(): Set<String> {
        val stored = prefs.getStringSet(KEY_IGNORED_GROUP_SIGNATURES, null)
        return stored?.toSet() ?: emptySet()
    }

    fun addIgnoredGroupSignature(signature: String): Set<String> {
        val trimmed = signature.trim()
        if (trimmed.isEmpty()) return getIgnoredGroupSignatures()
        val updated = LinkedHashSet(getIgnoredGroupSignatures()).apply { add(trimmed) }
        prefs.edit().putStringSet(KEY_IGNORED_GROUP_SIGNATURES, HashSet(updated)).apply()
        return updated
    }

    fun clearIgnoredGroupSignatures(): Set<String> {
        prefs.edit().remove(KEY_IGNORED_GROUP_SIGNATURES).apply()
        return emptySet()
    }

    fun getTargetFolderKeywords(): Set<String> {
        val stored = prefs.getStringSet(KEY_TARGET_FOLDER_KEYWORDS, null)
        return stored?.toSet() ?: emptySet()
    }

    fun toggleTargetFolderKeyword(folder: String): Set<String> {
        val trimmed = folder.trim()
        if (trimmed.isEmpty()) return getTargetFolderKeywords()
        val current = LinkedHashSet(getTargetFolderKeywords())
        if (current.contains(trimmed)) {
            current.remove(trimmed)
        } else {
            current.add(trimmed)
        }
        prefs.edit().putStringSet(KEY_TARGET_FOLDER_KEYWORDS, HashSet(current)).apply()
        return current
    }

    fun addTargetFolderKeyword(folder: String): Set<String> {
        val trimmed = folder.trim()
        if (trimmed.isEmpty()) return getTargetFolderKeywords()
        val updated = LinkedHashSet(getTargetFolderKeywords()).apply { add(trimmed) }
        prefs.edit().putStringSet(KEY_TARGET_FOLDER_KEYWORDS, HashSet(updated)).apply()
        return updated
    }

    fun clearTargetFolderKeywords(): Set<String> {
        prefs.edit().remove(KEY_TARGET_FOLDER_KEYWORDS).apply()
        return emptySet()
    }

    fun getAutoScanInterval(): com.antigravity.duplicleaner.domain.model.AutoScanInterval {
        val name = prefs.getString(KEY_AUTO_SCAN_INTERVAL, null) ?: return com.antigravity.duplicleaner.domain.model.AutoScanInterval.OFF
        return try {
            com.antigravity.duplicleaner.domain.model.AutoScanInterval.valueOf(name)
        } catch (_: IllegalArgumentException) {
            com.antigravity.duplicleaner.domain.model.AutoScanInterval.OFF
        }
    }

    fun setAutoScanInterval(interval: com.antigravity.duplicleaner.domain.model.AutoScanInterval) {
        prefs.edit().putString(KEY_AUTO_SCAN_INTERVAL, interval.name).apply()
    }

    fun getAppThemeMode(): AppThemeMode {
        val name = prefs.getString(KEY_APP_THEME_MODE, null)
            ?: return AppThemeMode.SYSTEM
        return try {
            AppThemeMode.valueOf(name)
        } catch (_: IllegalArgumentException) {
            AppThemeMode.SYSTEM
        }
    }

    fun setAppThemeMode(mode: AppThemeMode) {
        prefs.edit().putString(KEY_APP_THEME_MODE, mode.name).apply()
    }

    companion object {
        const val PREFS_NAME = "duplicleaner_prefs"
        private const val KEY_EXCLUDED_KEYWORDS = "excluded_keywords"
        private const val KEY_PROTECTED_KEEP_FOLDERS = "protected_keep_folders"
        private const val KEY_MIN_FILE_SIZE_BYTES = "min_file_size_bytes"
        private const val KEY_SIMILARITY_THRESHOLD_PERCENT = "similarity_threshold_percent"
        private const val KEY_IGNORED_GROUP_SIGNATURES = "ignored_group_signatures"
        private const val KEY_TARGET_FOLDER_KEYWORDS = "target_folder_keywords"
        private const val KEY_AUTO_SCAN_INTERVAL = "auto_scan_interval"
        private const val KEY_APP_THEME_MODE = "app_theme_mode"

        val DEFAULT_EXCLUDED_KEYWORDS: Set<String> = setOf(
            ".thumbnails",
            "Android/data",
            "Trash",
            ".trash"
        )

        val DEFAULT_PROTECTED_KEEP_FOLDERS: Set<String> = setOf(
            "DCIM/Camera",
            "Important"
        )

        const val DEFAULT_MIN_FILE_SIZE_BYTES: Long = 512 * 1024L
        const val DEFAULT_SIMILARITY_THRESHOLD_PERCENT: Int = 85
        const val MIN_SIMILARITY_THRESHOLD_PERCENT: Int = 75
        const val MAX_SIMILARITY_THRESHOLD_PERCENT: Int = 96
    }
}
