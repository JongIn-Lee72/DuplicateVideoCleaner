package com.antigravity.duplicleaner.data.local

import java.io.File
import java.util.Locale

data class EmptyFolderCandidate(
    val path: String,
    val name: String,
    val parentPath: String
)

object EmptyFolderCleaner {
    private val ALWAYS_PROTECTED_DIR_NAMES = setOf(
        "dcim",
        "camera",
        "android",
        "pictures",
        "movies",
        "music",
        "download",
        "documents",
        "quarantine_vault"
    )

    /**
     * Finds empty folders within [rootDirs].
     *
     * Protected root folders and folders matching [excludedKeywords] or [protectedFolders]
     * will never be marked as empty candidates. Bottom-up post-order traversal ensures
     * that deepest subfolders are returned first.
     */
    fun findEmptyFolders(
        rootDirs: List<File>,
        excludedKeywords: Set<String> = emptySet(),
        protectedFolders: Set<String> = emptySet()
    ): List<EmptyFolderCandidate> {
        val candidates = mutableListOf<EmptyFolderCandidate>()
        val excludedLower = excludedKeywords.map { it.lowercase(Locale.ROOT) }.filter { it.isNotBlank() }
        val protectedLower = protectedFolders.map { it.lowercase(Locale.ROOT) }.filter { it.isNotBlank() }

        for (rootDir in rootDirs) {
            if (!rootDir.exists() || !rootDir.isDirectory) continue
            scanDir(
                dir = rootDir,
                isRoot = true,
                excludedKeywords = excludedLower,
                protectedFolders = protectedLower,
                outCandidates = candidates
            )
        }
        return candidates
    }

    /**
     * Recursively scans [dir].
     * Returns true if [dir] is effectively empty (contains 0 non-empty subdirs and 0 files)
     * AND is eligible to be deleted.
     */
    private fun scanDir(
        dir: File,
        isRoot: Boolean,
        excludedKeywords: List<String>,
        protectedFolders: List<String>,
        outCandidates: MutableList<EmptyFolderCandidate>
    ): Boolean {
        val dirName = dir.name
        val dirNameLower = dirName.lowercase(Locale.ROOT)
        val absPath = dir.absolutePath
        val absLower = absPath.lowercase(Locale.ROOT)

        // Ignore hidden directories (starting with '.')
        if (dirName.startsWith(".")) {
            return false
        }

        // Check exclusions: if dir matches excluded keywords or protected folder paths, skip subtree
        if (excludedKeywords.any { absLower.contains(it) } || protectedFolders.any { absLower.contains(it) }) {
            return false
        }

        val children = dir.listFiles() ?: return false

        var hasFiles = false
        var allSubDirsEmpty = true
        var subDirCount = 0

        for (child in children) {
            if (child.isDirectory) {
                subDirCount++
                val childIsEmpty = scanDir(
                    dir = child,
                    isRoot = false,
                    excludedKeywords = excludedKeywords,
                    protectedFolders = protectedFolders,
                    outCandidates = outCandidates
                )
                if (!childIsEmpty) {
                    allSubDirsEmpty = false
                }
            } else {
                hasFiles = true
            }
        }

        val isEffectivelyEmpty = !hasFiles && (subDirCount == 0 || allSubDirsEmpty)

        // Check if this dir itself can be marked as deletable candidate:
        if (isRoot) {
            return false
        }

        // Protected names: e.g. dcim, camera, download, etc.
        if (dirNameLower in ALWAYS_PROTECTED_DIR_NAMES) {
            return false
        }

        // Special protection: DCIM/Camera path check
        val normalizedPath = absLower.replace('\\', '/')
        if (normalizedPath.endsWith("/dcim/camera") || normalizedPath.contains("/dcim/camera/")) {
            return false
        }

        if (isEffectivelyEmpty) {
            outCandidates.add(
                EmptyFolderCandidate(
                    path = absPath,
                    name = dirName,
                    parentPath = dir.parent ?: ""
                )
            )
            return true
        }

        return false
    }

    /**
     * Deletes the given folder paths safely (deepest first).
     * Only deletes if the directory exists and contains no files/folders.
     * Returns the total count of successfully deleted folders.
     */
    fun deleteEmptyFolders(paths: List<String>): Int {
        var deletedCount = 0
        // Sort descending by path length to ensure deepest directories are deleted first
        val sortedPaths = paths.sortedByDescending { it.length }

        for (path in sortedPaths) {
            val file = File(path)
            if (file.exists() && file.isDirectory) {
                val contents = file.listFiles()
                if (contents != null && contents.isEmpty()) {
                    if (file.delete()) {
                        deletedCount++
                    }
                }
            }
        }
        return deletedCount
    }
}
