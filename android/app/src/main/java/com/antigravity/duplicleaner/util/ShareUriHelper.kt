package com.antigravity.duplicleaner.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object ShareUriHelper {

    fun isFileScheme(uriString: String): Boolean {
        val trimmed = uriString.trim()
        val lower = trimmed.lowercase()
        return lower.startsWith("file://") || (trimmed.startsWith("/") && !lower.startsWith("content://"))
    }

    fun toViewableUri(context: Context, uri: Uri, absolutePath: String? = null): Uri {
        if (uri.scheme.equals("content", ignoreCase = true)) {
            return uri
        }

        val file = when {
            !absolutePath.isNullOrBlank() -> File(absolutePath)
            !uri.path.isNullOrBlank() -> File(uri.path!!)
            else -> null
        }

        if (file != null && file.exists()) {
            return try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } catch (e: Exception) {
                uri
            }
        }

        return uri
    }
}
