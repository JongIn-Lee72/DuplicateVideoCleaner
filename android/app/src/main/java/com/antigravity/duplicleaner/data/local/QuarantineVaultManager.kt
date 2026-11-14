package com.antigravity.duplicleaner.data.local

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

data class QuarantinedItemRecord(
    val id: String,
    val originalPath: String,
    val vaultFilePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val quarantinedAtMs: Long
)

class QuarantineVaultManager(private val vaultDir: File) {

    constructor(context: Context) : this(File(context.filesDir, "quarantine_vault"))

    private val manifestFile = File(vaultDir, "manifest.json")
    private val lock = Any()

    init {
        if (!vaultDir.exists()) {
            vaultDir.mkdirs()
        }
    }

    fun quarantineFile(sourceFile: File): QuarantinedItemRecord? = synchronized(lock) {
        if (!sourceFile.exists() || !sourceFile.isFile) {
            return null
        }

        if (!vaultDir.exists()) {
            vaultDir.mkdirs()
        }

        val id = UUID.randomUUID().toString()
        val originalPath = sourceFile.absolutePath
        val displayName = sourceFile.name
        val sizeBytes = sourceFile.length()
        val quarantinedAtMs = System.currentTimeMillis()

        // Give the quarantined file a unique name inside the vault to avoid collisions
        val safeVaultFileName = "${id}_${displayName}"
        val targetVaultFile = File(vaultDir, safeVaultFileName)

        val moved = moveOrCopyDelete(sourceFile, targetVaultFile)
        if (!moved) {
            return null
        }

        val record = QuarantinedItemRecord(
            id = id,
            originalPath = originalPath,
            vaultFilePath = targetVaultFile.absolutePath,
            displayName = displayName,
            sizeBytes = sizeBytes,
            quarantinedAtMs = quarantinedAtMs
        )

        val items = loadManifestInternal().toMutableList()
        items.add(record)
        saveManifestInternal(items)

        return record
    }

    fun getQuarantinedItems(): List<QuarantinedItemRecord> = synchronized(lock) {
        return loadManifestInternal()
    }

    fun getTotalQuarantinedBytes(): Long = synchronized(lock) {
        return loadManifestInternal().sumOf { it.sizeBytes }
    }

    fun restoreSingleItem(itemId: String): Boolean = synchronized(lock) {
        val items = loadManifestInternal().toMutableList()
        val index = items.indexOfFirst { it.id == itemId }
        if (index == -1) return false

        val item = items[index]
        val vaultFile = File(item.vaultFilePath)
        val originalFile = File(item.originalPath)

        val restored = if (vaultFile.exists()) {
            val parent = originalFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            moveOrCopyDelete(vaultFile, originalFile)
        } else {
            false
        }

        if (restored) {
            items.removeAt(index)
            saveManifestInternal(items)
            return true
        } else if (!vaultFile.exists()) {
            // Vault file is already missing, clean up orphaned record
            items.removeAt(index)
            saveManifestInternal(items)
            return false
        }
        return false
    }

    fun deleteSingleVaultItem(itemId: String): Long = synchronized(lock) {
        val items = loadManifestInternal().toMutableList()
        val index = items.indexOfFirst { it.id == itemId }
        if (index == -1) return 0L

        val item = items[index]
        val vaultFile = File(item.vaultFilePath)
        val freedBytes = if (vaultFile.exists()) {
            val size = vaultFile.length()
            vaultFile.delete()
            size
        } else {
            item.sizeBytes
        }

        items.removeAt(index)
        saveManifestInternal(items)
        return freedBytes
    }

    fun restoreAllQuarantinedFiles(): Int = synchronized(lock) {
        val items = loadManifestInternal()
        if (items.isEmpty()) return 0

        var restoredCount = 0
        val remainingItems = mutableListOf<QuarantinedItemRecord>()

        for (item in items) {
            val vaultFile = File(item.vaultFilePath)
            val originalFile = File(item.originalPath)

            if (vaultFile.exists()) {
                val parent = originalFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }

                val restored = moveOrCopyDelete(vaultFile, originalFile)
                if (restored) {
                    restoredCount++
                } else {
                    remainingItems.add(item)
                }
            } else {
                // Vault file missing, remove from manifest
            }
        }

        saveManifestInternal(remainingItems)
        return restoredCount
    }

    fun emptyQuarantineVault(): Long = synchronized(lock) {
        val items = loadManifestInternal()
        var freedBytes = 0L

        for (item in items) {
            val vaultFile = File(item.vaultFilePath)
            if (vaultFile.exists()) {
                val size = vaultFile.length()
                if (vaultFile.delete()) {
                    freedBytes += size
                }
            }
        }

        // Clean any leftover orphan files in vaultDir except manifest
        vaultDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name != "manifest.json") {
                freedBytes += file.length()
                file.delete()
            }
        }

        saveManifestInternal(emptyList())
        return freedBytes
    }

    private fun moveOrCopyDelete(src: File, dst: File): Boolean {
        if (src.renameTo(dst)) {
            return true
        }

        // Fallback to copy & delete for cross-filesystem moves
        return try {
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { output ->
                    input.copyTo(output)
                }
            }
            src.delete()
            true
        } catch (e: Exception) {
            if (dst.exists()) {
                dst.delete()
            }
            false
        }
    }

    private fun loadManifestInternal(): List<QuarantinedItemRecord> {
        if (!manifestFile.exists()) {
            return emptyList()
        }

        return try {
            val jsonText = manifestFile.readText().trim()
            parseJsonArrayOfObjects(jsonText)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveManifestInternal(items: List<QuarantinedItemRecord>) {
        try {
            val sb = java.lang.StringBuilder()
            sb.append("[\n")
            for (i in items.indices) {
                val item = items[i]
                sb.append("  {\n")
                sb.append("    \"id\": \"").append(escapeJson(item.id)).append("\",\n")
                sb.append("    \"originalPath\": \"").append(escapeJson(item.originalPath)).append("\",\n")
                sb.append("    \"vaultFilePath\": \"").append(escapeJson(item.vaultFilePath)).append("\",\n")
                sb.append("    \"displayName\": \"").append(escapeJson(item.displayName)).append("\",\n")
                sb.append("    \"sizeBytes\": ").append(item.sizeBytes).append(",\n")
                sb.append("    \"quarantinedAtMs\": ").append(item.quarantinedAtMs).append("\n")
                sb.append("  }")
                if (i < items.size - 1) {
                    sb.append(",")
                }
                sb.append("\n")
            }
            sb.append("]\n")
            manifestFile.writeText(sb.toString())
        } catch (e: Exception) {
            // Log or ignore
        }
    }

    private fun escapeJson(str: String): String {
        val sb = java.lang.StringBuilder()
        for (c in str) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun parseJsonArrayOfObjects(json: String): List<QuarantinedItemRecord> {
        val records = mutableListOf<QuarantinedItemRecord>()
        var i = 0
        val len = json.length

        // Find '['
        while (i < len && json[i] != '[') i++
        if (i >= len) return emptyList()
        i++ // skip '['

        while (i < len) {
            // Find '{' or ']'
            while (i < len && json[i] != '{' && json[i] != ']') i++
            if (i >= len || json[i] == ']') break

            // Parse object
            i++ // skip '{'
            val map = mutableMapOf<String, String>()

            while (i < len && json[i] != '}') {
                // Find key start '"'
                while (i < len && json[i] != '"' && json[i] != '}') i++
                if (i >= len || json[i] == '}') break

                val key = parseString(json, i)
                i += key.encodedLength

                // Find ':'
                while (i < len && json[i] != ':') i++
                if (i < len) i++ // skip ':'

                // Skip whitespace
                while (i < len && json[i].isWhitespace()) i++
                if (i >= len) break

                if (json[i] == '"') {
                    val value = parseString(json, i)
                    i += value.encodedLength
                    map[key.value] = value.value
                } else {
                    // Number/primitive
                    val numStart = i
                    while (i < len && json[i] != ',' && json[i] != '}' && !json[i].isWhitespace()) {
                        i++
                    }
                    val numVal = json.substring(numStart, i).trim()
                    map[key.value] = numVal
                }

                // Skip until ',' or '}'
                while (i < len && json[i] != ',' && json[i] != '}') i++
                if (i < len && json[i] == ',') i++
            }

            if (i < len && json[i] == '}') i++ // skip '}'

            val id = map["id"]
            val originalPath = map["originalPath"]
            val vaultFilePath = map["vaultFilePath"]
            val displayName = map["displayName"]
            val sizeBytes = map["sizeBytes"]?.toLongOrNull()
            val quarantinedAtMs = map["quarantinedAtMs"]?.toLongOrNull()

            if (id != null && originalPath != null && vaultFilePath != null &&
                displayName != null && sizeBytes != null && quarantinedAtMs != null
            ) {
                records.add(
                    QuarantinedItemRecord(
                        id = id,
                        originalPath = originalPath,
                        vaultFilePath = vaultFilePath,
                        displayName = displayName,
                        sizeBytes = sizeBytes,
                        quarantinedAtMs = quarantinedAtMs
                    )
                )
            }
        }

        return records
    }

    private data class ParsedString(val value: String, val encodedLength: Int)

    private fun parseString(json: String, startIndex: Int): ParsedString {
        // startIndex points at opening '"'
        val sb = java.lang.StringBuilder()
        var i = startIndex + 1
        var escaped = false

        while (i < json.length) {
            val c = json[i]
            if (escaped) {
                when (c) {
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        if (i + 4 < json.length) {
                            val hex = json.substring(i + 1, i + 5)
                            hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                            i += 4
                        }
                    }
                    else -> sb.append(c)
                }
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                i++ // include closing quote
                break
            } else {
                sb.append(c)
            }
            i++
        }
        return ParsedString(sb.toString(), i - startIndex)
    }
}
