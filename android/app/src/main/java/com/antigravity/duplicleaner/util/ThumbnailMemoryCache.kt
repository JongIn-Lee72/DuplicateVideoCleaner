package com.antigravity.duplicleaner.util

import android.graphics.Bitmap
import android.util.LruCache

object ThumbnailMemoryCache {

    private val cache: LruCache<String, Bitmap>

    init {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        val cacheSizeKb = minOf(16 * 1024, (maxMemoryKb / 16).coerceAtLeast(4 * 1024))

        cache = object : LruCache<String, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return (bitmap.allocationByteCount / 1024).coerceAtLeast(1)
            }
        }
    }

    fun get(key: String): Bitmap? {
        val bitmap = cache.get(key)
        return if (bitmap != null && !bitmap.isRecycled) {
            bitmap
        } else {
            if (bitmap != null && bitmap.isRecycled) {
                cache.remove(key)
            }
            null
        }
    }

    fun put(key: String, bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            cache.put(key, bitmap)
        }
    }

    fun clear() {
        cache.evictAll()
    }

    fun computeSafeInSampleSize(rawWidth: Int, rawHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (reqWidth > 0 && reqHeight > 0 && (rawHeight > reqHeight || rawWidth > reqWidth)) {
            val halfHeight = rawHeight / 2
            val halfWidth = rawWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
