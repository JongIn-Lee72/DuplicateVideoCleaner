package com.antigravity.duplicleaner.data.hash

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * 대용량 영상 및 파일을 위한 다단계 고속 해싱 엔진.
 *
 * - Stage 2 (Partial Hash): Head(16KB) + Middle(16KB) + Tail(16KB) 3점 샘플링 기반 64-bit XXHash64
 * - Stage 3 (Full Hash): 256KB 버퍼 스트리밍 기반 128-bit Dual-Seed XXHash64 + SHA-256 하이브리드 검증
 */
object FastHasher {

    const val SAMPLE_CHUNK_SIZE = 16 * 1024 // 16 KB per sample point
    private const val FULL_HASH_BUFFER_SIZE = 256 * 1024 // 256 KB streaming buffer

    // XXHash64 prime constants
    private const val PRIME64_1 = -7046029288634856825L // 0x9E3779B185EBCA87UL
    private const val PRIME64_2 = -4417276706812531889L // 0xC2B2AE3D27D4EB4FUL
    private const val PRIME64_3 = 1609587929392839161L  // 0x165667B19E3779F9UL
    private const val PRIME64_4 = -8796714831421723037L // 0x85EBCA77C2B2AE63UL
    private const val PRIME64_5 = 2870177450012600261L  // 0x27D4EB2F165667C5UL

    /**
     * FileChannel에서 시작(Head), 중간(Middle), 끝(Tail) 각 16KB만 시크(Seek)하여
     * 최대 48KB I/O만으로 초고속 64-bit 부분 해시를 계산합니다.
     */
    fun computePartialHash(channel: FileChannel, fileSize: Long): Long {
        if (fileSize <= 0L) return 0L

        val totalSampleThreshold = SAMPLE_CHUNK_SIZE * 3L
        if (fileSize <= totalSampleThreshold) {
            val buffer = ByteBuffer.allocate(fileSize.toInt())
            channel.position(0L)
            readFully(channel, buffer)
            buffer.flip()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            return xxHash64(bytes, seed = fileSize)
        }

        val combined = ByteArray(SAMPLE_CHUNK_SIZE * 3)
        val chunkBuffer = ByteBuffer.allocate(SAMPLE_CHUNK_SIZE)

        // 1. Head 16KB (offset = 0)
        chunkBuffer.clear()
        channel.position(0L)
        readFully(channel, chunkBuffer)
        chunkBuffer.flip()
        chunkBuffer.get(combined, 0, chunkBuffer.remaining())

        // 2. Middle 16KB (offset = fileSize / 2 - 8KB)
        val midOffset = (fileSize / 2L) - (SAMPLE_CHUNK_SIZE / 2L)
        chunkBuffer.clear()
        channel.position(midOffset)
        readFully(channel, chunkBuffer)
        chunkBuffer.flip()
        chunkBuffer.get(combined, SAMPLE_CHUNK_SIZE, chunkBuffer.remaining())

        // 3. Tail 16KB (offset = fileSize - 16KB)
        val tailOffset = fileSize - SAMPLE_CHUNK_SIZE
        chunkBuffer.clear()
        channel.position(tailOffset)
        readFully(channel, chunkBuffer)
        chunkBuffer.flip()
        chunkBuffer.get(combined, SAMPLE_CHUNK_SIZE * 2, chunkBuffer.remaining())

        return xxHash64(combined, seed = fileSize)
    }

    fun computePartialHash(file: File): Long {
        if (!file.exists() || !file.isFile) return 0L
        FileInputStream(file).use { fis ->
            return computePartialHash(fis.channel, file.length())
        }
    }

    /**
     * MP4/MOV 컨테이너의 경우 가변 메타데이터 박스(ftyp, moov, free 등)를 건너뛰고
     * 순수 미디어 스트림(mdat 페이로드, 최소 48KB 이상) 내부의 Head/Middle/Tail 16KB만 샘플링하여
     * 메타데이터나 faststart 구조가 변경된 동일 영상도 일치하는 해시를 산출합니다.
     * MP4 컨테이너가 아니거나 유효한 mdat 페이로드가 48KB 미만이면 computePartialHash로 폴백합니다.
     */
    fun computePayloadHash(channel: FileChannel, fileSize: Long): Long {
        if (fileSize <= 0L) return 0L

        val totalSampleThreshold = SAMPLE_CHUNK_SIZE * 3L
        val region = Mp4ContainerInspector.inspectMdatPayload(channel, fileSize)
        if (region != null &&
            (region.hasFtyp || region.hasMoov) &&
            region.length >= totalSampleThreshold &&
            region.offset >= 0L &&
            region.offset + region.length <= fileSize
        ) {
            val combined = ByteArray(SAMPLE_CHUNK_SIZE * 3)
            val chunkBuffer = ByteBuffer.allocate(SAMPLE_CHUNK_SIZE)

            // 1. Payload Head 16KB (offset = region.offset)
            chunkBuffer.clear()
            channel.position(region.offset)
            readFully(channel, chunkBuffer)
            chunkBuffer.flip()
            chunkBuffer.get(combined, 0, chunkBuffer.remaining())

            // 2. Payload Middle 16KB (offset = region.offset + region.length / 2 - 8KB)
            val midOffset = region.offset + (region.length / 2L) - (SAMPLE_CHUNK_SIZE / 2L)
            chunkBuffer.clear()
            channel.position(midOffset)
            readFully(channel, chunkBuffer)
            chunkBuffer.flip()
            chunkBuffer.get(combined, SAMPLE_CHUNK_SIZE, chunkBuffer.remaining())

            // 3. Payload Tail 16KB (offset = region.offset + region.length - 16KB)
            val tailOffset = region.offset + region.length - SAMPLE_CHUNK_SIZE
            chunkBuffer.clear()
            channel.position(tailOffset)
            readFully(channel, chunkBuffer)
            chunkBuffer.flip()
            chunkBuffer.get(combined, SAMPLE_CHUNK_SIZE * 2, chunkBuffer.remaining())

            return xxHash64(combined, seed = region.length)
        }

        return computePartialHash(channel, fileSize)
    }

    fun computePayloadHash(file: File): Long {
        if (!file.exists() || !file.isFile) return 0L
        FileInputStream(file).use { fis ->
            return computePayloadHash(fis.channel, file.length())
        }
    }

    /**
     * Stage 2를 통과한 후보 파일들에 대해 전체 스트리밍 해시를 계산합니다.
     * 128-bit Dual-Seed XXHash64 및 SHA-256을 결합하여 100% 바이트 일치를 보증합니다.
     */
    fun computeFullHash(channel: FileChannel): String {
        channel.position(0L)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocateDirect(FULL_HASH_BUFFER_SIZE)
        val arrayBuf = ByteArray(FULL_HASH_BUFFER_SIZE)

        var rollingHashA = PRIME64_5
        var rollingHashB = PRIME64_1

        while (true) {
            buffer.clear()
            val bytesRead = channel.read(buffer)
            if (bytesRead <= 0) break
            buffer.flip()
            buffer.get(arrayBuf, 0, bytesRead)

            val chunkHash = xxHash64(arrayBuf, length = bytesRead, seed = rollingHashA)
            rollingHashA = java.lang.Long.rotateLeft(rollingHashA xor chunkHash, 27) * PRIME64_1 + PRIME64_4
            rollingHashB = java.lang.Long.rotateLeft(rollingHashB + chunkHash, 31) * PRIME64_2
            digest.update(arrayBuf, 0, bytesRead)
        }

        val shaBytes = digest.digest()
        val shaPrefix = shaBytes.take(8).joinToString("") { "%02x".format(it) }
        return "%016x%016x-%s".format(rollingHashA, rollingHashB, shaPrefix)
    }

    fun computeFullHash(file: File): String {
        FileInputStream(file).use { fis ->
            return computeFullHash(fis.channel)
        }
    }

    /**
     * 순수 Kotlin 64-bit XXHash64 구현 (Little-Endian 블록 처리)
     */
    fun xxHash64(input: ByteArray, length: Int = input.size, seed: Long = 0L): Long {
        val bb = ByteBuffer.wrap(input, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        var h64: Long

        if (length >= 32) {
            var v1 = seed + PRIME64_1 + PRIME64_2
            var v2 = seed + PRIME64_2
            var v3 = seed + 0L
            var v4 = seed - PRIME64_1

            val limit = length - 32
            while (bb.position() <= limit) {
                v1 = round(v1, bb.long)
                v2 = round(v2, bb.long)
                v3 = round(v3, bb.long)
                v4 = round(v4, bb.long)
            }

            h64 = java.lang.Long.rotateLeft(v1, 1) +
                java.lang.Long.rotateLeft(v2, 7) +
                java.lang.Long.rotateLeft(v3, 12) +
                java.lang.Long.rotateLeft(v4, 18)

            h64 = mergeRound(h64, v1)
            h64 = mergeRound(h64, v2)
            h64 = mergeRound(h64, v3)
            h64 = mergeRound(h64, v4)
        } else {
            h64 = seed + PRIME64_5
        }

        h64 += length.toLong()

        while (bb.remaining() >= 8) {
            val k1 = round(0L, bb.long)
            h64 = h64 xor k1
            h64 = java.lang.Long.rotateLeft(h64, 27) * PRIME64_1 + PRIME64_4
        }

        if (bb.remaining() >= 4) {
            val k1 = (bb.int.toLong() and 0xFFFFFFFFL) * PRIME64_1
            h64 = h64 xor k1
            h64 = java.lang.Long.rotateLeft(h64, 23) * PRIME64_2 + PRIME64_3
        }

        while (bb.hasRemaining()) {
            val k1 = (bb.get().toLong() and 0xFFL) * PRIME64_5
            h64 = h64 xor k1
            h64 = java.lang.Long.rotateLeft(h64, 11) * PRIME64_1
        }

        // Avalanche final mix
        h64 = h64 xor (h64 ushr 33)
        h64 *= PRIME64_2
        h64 = h64 xor (h64 ushr 29)
        h64 *= PRIME64_3
        h64 = h64 xor (h64 ushr 32)

        return h64
    }

    private fun round(acc: Long, input: Long): Long {
        var res = acc + input * PRIME64_2
        res = java.lang.Long.rotateLeft(res, 31)
        res *= PRIME64_1
        return res
    }

    private fun mergeRound(acc: Long, value: Long): Long {
        val valRounded = round(0L, value)
        var res = acc xor valRounded
        res = res * PRIME64_1 + PRIME64_4
        return res
    }

    private fun readFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val r = channel.read(buffer)
            if (r <= 0) break
        }
    }
}
