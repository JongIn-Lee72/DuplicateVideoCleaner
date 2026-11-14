package com.antigravity.duplicleaner.data.hash

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * MP4/MOV, MKV/WebM, MP3 등 다양한 미디어 컨테이너의 스트림 페이로드를 고속 검사하여
 * 가변 메타데이터(ID3 태그, EBML 헤더, moov/ftyp 등)를 건너뛰고 순수 오디오/비디오 스트림 영역을 식별합니다.
 */
object MediaContainerInspector {

    private const val MKV_SCAN_WINDOW_BYTES = 64 * 1024L
    private const val ID3V1_TRAILER_SIZE = 128L

    /**
     * MP3 오디오 파일에서 ID3v2 태그(헤더) 및 ID3v1 트레일러를 건너뛰고
     * 순수 MPEG 오디오 프레임 스트림 영역(PayloadRegion)을 반환합니다.
     * ID3v2 헤더나 MPEG 동기화 단어(0xFF, 0xEx)를 찾지 못하면 null을 반환합니다.
     */
    fun inspectMp3AudioPayload(channel: FileChannel, fileSize: Long): Mp4ContainerInspector.PayloadRegion? {
        if (fileSize < 2L) return null

        val headerSize = minOf(fileSize, 10L).toInt()
        val headerBuf = ByteBuffer.allocate(headerSize)
        channel.position(0L)
        if (!readFully(channel, headerBuf)) return null
        headerBuf.flip()

        var audioStartOffset = -1L

        if (headerSize >= 10 &&
            headerBuf.get(0) == 'I'.code.toByte() &&
            headerBuf.get(1) == 'D'.code.toByte() &&
            headerBuf.get(2) == '3'.code.toByte()
        ) {
            // ID3v2 헤더: 바이트 6..9는 7비트 Synchsafe 정수 (태그 페이로드 크기)
            val b6 = headerBuf.get(6).toInt() and 0x7F
            val b7 = headerBuf.get(7).toInt() and 0x7F
            val b8 = headerBuf.get(8).toInt() and 0x7F
            val b9 = headerBuf.get(9).toInt() and 0x7F
            val synchsafeSize = (b6 shl 21) or (b7 shl 14) or (b8 shl 7) or b9
            audioStartOffset = 10L + synchsafeSize
        } else if ((headerBuf.get(0).toInt() and 0xFF) == 0xFF && (headerBuf.get(1).toInt() and 0xE0) == 0xE0) {
            // ID3 태그가 없는 순수 MPEG 오디오 프레임 동기화 단어 (11개 연속 1비트)
            audioStartOffset = 0L
        } else {
            return null
        }

        if (audioStartOffset < 0L || audioStartOffset >= fileSize) return null

        // ID3v1 트레일러(파일 끝 128바이트의 "TAG") 존재 여부 확인
        var audioEnd = fileSize
        if (fileSize >= ID3V1_TRAILER_SIZE) {
            val tagBuf = ByteBuffer.allocate(3)
            channel.position(fileSize - ID3V1_TRAILER_SIZE)
            if (readFully(channel, tagBuf)) {
                tagBuf.flip()
                if (tagBuf.get(0) == 'T'.code.toByte() &&
                    tagBuf.get(1) == 'A'.code.toByte() &&
                    tagBuf.get(2) == 'G'.code.toByte()
                ) {
                    audioEnd = fileSize - ID3V1_TRAILER_SIZE
                }
            }
        }

        val audioLength = audioEnd - audioStartOffset
        if (audioLength <= 0L) return null

        return Mp4ContainerInspector.PayloadRegion(
            offset = audioStartOffset,
            length = audioLength,
            hasMoov = true,
            hasFtyp = true
        )
    }

    fun inspectMp3AudioPayload(file: File): Mp4ContainerInspector.PayloadRegion? {
        if (!file.exists() || !file.isFile) return null
        FileInputStream(file).use { fis ->
            return inspectMp3AudioPayload(fis.channel, file.length())
        }
    }

    /**
     * Matroska / WebM (MKV) 파일의 EBML 헤더를 검사하고,
     * 첫 번째 미디어 프레임이 시작되는 `Cluster` 요소(0x1F, 0x43, 0xB6, 0x75)의 위치를 스캔하여
     * 가변 헤더 메타데이터를 제외한 실제 비디오/오디오 스트림 영역(PayloadRegion)을 반환합니다.
     */
    fun inspectMkvClusterPayload(channel: FileChannel, fileSize: Long): Mp4ContainerInspector.PayloadRegion? {
        if (fileSize < 4L) return null

        val scanLimit = minOf(fileSize, MKV_SCAN_WINDOW_BYTES).toInt()
        val scanBuf = ByteBuffer.allocate(scanLimit)
        channel.position(0L)
        if (!readFully(channel, scanBuf)) return null
        scanBuf.flip()

        // 1. EBML Magic 확인: 0x1A, 0x45, 0xDF, 0xA3
        val b0 = scanBuf.get(0).toInt() and 0xFF
        val b1 = scanBuf.get(1).toInt() and 0xFF
        val b2 = scanBuf.get(2).toInt() and 0xFF
        val b3 = scanBuf.get(3).toInt() and 0xFF

        if (b0 != 0x1A || b1 != 0x45 || b2 != 0xDF || b3 != 0xA3) {
            return null
        }

        // 2. 첫 번째 Cluster ID 스캔: 0x1F, 0x43, 0xB6, 0x75
        var clusterOffset = -1L
        val limit = scanLimit - 3
        for (i in 0 until limit) {
            if ((scanBuf.get(i).toInt() and 0xFF) == 0x1F &&
                (scanBuf.get(i + 1).toInt() and 0xFF) == 0x43 &&
                (scanBuf.get(i + 2).toInt() and 0xFF) == 0xB6 &&
                (scanBuf.get(i + 3).toInt() and 0xFF) == 0x75
            ) {
                clusterOffset = i.toLong()
                break
            }
        }

        if (clusterOffset < 0L) return null
        val length = fileSize - clusterOffset
        if (length <= 0L) return null

        return Mp4ContainerInspector.PayloadRegion(
            offset = clusterOffset,
            length = length,
            hasMoov = true,
            hasFtyp = true
        )
    }

    fun inspectMkvClusterPayload(file: File): Mp4ContainerInspector.PayloadRegion? {
        if (!file.exists() || !file.isFile) return null
        FileInputStream(file).use { fis ->
            return inspectMkvClusterPayload(fis.channel, file.length())
        }
    }

    /**
     * MP4/MOV(mdat), MKV/WebM(Cluster), MP3(ID3 제거 스트림) 순으로 검사하여
     * 지원되는 모든 컨테이너의 페이로드 스트림 영역을 식별합니다.
     */
    fun inspectAnyMediaPayload(channel: FileChannel, fileSize: Long): Mp4ContainerInspector.PayloadRegion? {
        val mp4 = Mp4ContainerInspector.inspectMdatPayload(channel, fileSize)
        if (mp4 != null) return mp4

        val mkv = inspectMkvClusterPayload(channel, fileSize)
        if (mkv != null) return mkv

        return inspectMp3AudioPayload(channel, fileSize)
    }

    fun inspectAnyMediaPayload(file: File): Mp4ContainerInspector.PayloadRegion? {
        if (!file.exists() || !file.isFile) return null
        FileInputStream(file).use { fis ->
            return inspectAnyMediaPayload(fis.channel, file.length())
        }
    }

    /**
     * 미디어 컨테이너 내부의 실제 스트림 페이로드(최소 48KB 이상)에 대해
     * Head(16KB), Middle(16KB), Tail(16KB) 3점 샘플링 기반 64-bit XXHash64를 산출합니다.
     * 성공 시 Pair(페이로드 길이, XXHash64 해시)를 반환하고, 컨테이너 형식이 아니거나 크기가 부족하면 null을 반환합니다.
     */
    fun computeContainerPayloadHash(channel: FileChannel, fileSize: Long): Pair<Long, Long>? {
        val region = inspectAnyMediaPayload(channel, fileSize) ?: return null
        val threshold = FastHasher.SAMPLE_CHUNK_SIZE * 3L
        if (region.length < threshold || region.offset < 0L || region.offset + region.length > fileSize) {
            return null
        }

        val sampleSize = FastHasher.SAMPLE_CHUNK_SIZE
        val combined = ByteArray(sampleSize * 3)
        val chunkBuffer = ByteBuffer.allocate(sampleSize)

        // 1. Payload Head 16KB
        chunkBuffer.clear()
        channel.position(region.offset)
        if (!readFully(channel, chunkBuffer)) return null
        chunkBuffer.flip()
        chunkBuffer.get(combined, 0, chunkBuffer.remaining())

        // 2. Payload Middle 16KB
        val midOffset = region.offset + (region.length / 2L) - (sampleSize / 2L)
        chunkBuffer.clear()
        channel.position(midOffset)
        if (!readFully(channel, chunkBuffer)) return null
        chunkBuffer.flip()
        chunkBuffer.get(combined, sampleSize, chunkBuffer.remaining())

        // 3. Payload Tail 16KB
        val tailOffset = region.offset + region.length - sampleSize
        chunkBuffer.clear()
        channel.position(tailOffset)
        if (!readFully(channel, chunkBuffer)) return null
        chunkBuffer.flip()
        chunkBuffer.get(combined, sampleSize * 2, chunkBuffer.remaining())

        val hash = FastHasher.xxHash64(combined, seed = region.length)
        return region.length to hash
    }

    fun computeContainerPayloadHash(file: File): Pair<Long, Long>? {
        if (!file.exists() || !file.isFile) return null
        FileInputStream(file).use { fis ->
            return computeContainerPayloadHash(fis.channel, file.length())
        }
    }

    private fun readFully(channel: FileChannel, buffer: ByteBuffer): Boolean {
        while (buffer.hasRemaining()) {
            val bytesRead = channel.read(buffer)
            if (bytesRead <= 0) return false
        }
        return true
    }
}
