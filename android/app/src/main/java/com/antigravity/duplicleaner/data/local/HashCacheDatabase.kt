package com.antigravity.duplicleaner.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "cached_file_hashes")
data class CachedFileHashEntity(
    @PrimaryKey
    val uriString: String,
    val sizeBytes: Long,
    val lastModifiedSeconds: Long,
    val partialHash: Long?,
    val fullHash: String?,
    val perceptualHashesCsv: String?,
    val payloadHash: Long? = null,
    val pHashesCsv: String? = null
) {
    fun parsePerceptualHashes(): List<Long> = parseCsvHashes(perceptualHashesCsv)

    fun parsePHashes(): List<Long> = parseCsvHashes(pHashesCsv)

    private fun parseCsvHashes(csv: String?): List<Long> {
        if (csv.isNullOrBlank()) return emptyList()
        return csv.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
    }

    companion object {
        fun encodePerceptualHashes(hashes: List<Long>): String? {
            if (hashes.isEmpty()) return null
            return hashes.joinToString(",")
        }
    }
}

@Dao
interface HashCacheDao {

    @Query(
        """
        SELECT * FROM cached_file_hashes
        WHERE uriString = :uriString
          AND sizeBytes = :sizeBytes
          AND lastModifiedSeconds = :lastModifiedSeconds
        LIMIT 1
        """
    )
    suspend fun findValidCache(
        uriString: String,
        sizeBytes: Long,
        lastModifiedSeconds: Long
    ): CachedFileHashEntity?

    @Query("SELECT * FROM cached_file_hashes")
    suspend fun getAllCachedHashes(): List<CachedFileHashEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCache(entity: CachedFileHashEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CachedFileHashEntity>)

    @Query("DELETE FROM cached_file_hashes WHERE uriString IN (:uriStrings)")
    suspend fun deleteByUris(uriStrings: List<String>)

    @Query("DELETE FROM cached_file_hashes WHERE uriString NOT IN (:activeUris)")
    suspend fun pruneOrphanedCaches(activeUris: List<String>)

    @Query("SELECT COUNT(*) FROM cached_file_hashes")
    suspend fun countCachedFiles(): Int

    @Query("DELETE FROM cached_file_hashes")
    suspend fun clearAll()
}

@Database(
    entities = [CachedFileHashEntity::class],
    version = 2,
    exportSchema = false
)
abstract class HashCacheDatabase : RoomDatabase() {
    abstract fun hashCacheDao(): HashCacheDao

    companion object {
        @Volatile
        private var INSTANCE: HashCacheDatabase? = null

        fun getInstance(context: Context): HashCacheDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HashCacheDatabase::class.java,
                    "duplicate_hash_cache.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
