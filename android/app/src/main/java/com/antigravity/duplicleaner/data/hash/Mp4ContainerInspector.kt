package com.antigravity.duplicleaner.data.hash

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * ISO Base Media File Format (MP4, MOV, M4V, 3GP) 컨테이너의 최상위(Top-level) Box 헤더를
 * 고속 스캔하여 가변 메타데이터(ftyp, moov, udta, free 등)와 실제 미디어 스트림(mdat) 영역을 분리합니다.
 */
object Mp4ContainerInspector {

    private const val MAX_TOP_LEVEL_BOXES = 64
    private const val STANDARD_HEADER_SIZE = 8L
    private const val EXTENDED_HEADER_SIZE = 16L

    data class PayloadRegion(
        val offset: Long,
        val length: Long,
        val hasMoov: Boolean,
        val hasFtyp: Boolean
    )

    /**
     * 최대 64개의 최상위 Box 헤더(8바이트 기본 헤더 및 size == 1일 때 64-bit largesize)를 스캔하여
     * 가장 큰 `mdat` 미디어 스트림 페이로드의 시작 오프셋과 길이를 반환합니다.
     * 유효한 MP4/MOV 구조가 아니거나 `mdat` 박스를 찾지 못하면 null을 반환합니다.
     */
    fun inspectMdatPayload(channel: FileChannel, fileSize: Long): PayloadRegion? {
        if (fileSize < STANDARD_HEADER_SIZE) return null

        val headerBuf = ByteBuffer.allocate(EXTENDED_HEADER_SIZE.toInt()).order(ByteOrder.BIG_ENDIAN)
        var currentOffset = 0L
        var boxCount = 0

        var hasFtyp = false
        var hasMoov = false
        var bestMdatOffset = -1L
        var bestMdatLength = -1L

        while (currentOffset + STANDARD_HEADER_SIZE <= fileSize && boxCount < MAX_TOP_LEVEL_BOXES) {
            headerBuf.clear()
            headerBuf.limit(STANDARD_HEADER_SIZE.toInt())
            channel.position(currentOffset)
            if (!readExactly(channel, headerBuf)) break
            headerBuf.flip()

            val size32 = headerBuf.int.toLong() and 0xFFFFFFFFL
            val b0 = headerBuf.get()
            val b1 = headerBuf.get()
            val b2 = headerBuf.get()
            val b3 = headerBuf.get()

            if (!isPrintableFourCc(b0, b1, b2, b3)) {
                if (boxCount == 0) return null
                break
            }

            val boxType = String(byteArrayOf(b0, b1, b2, b3), Charsets.US_ASCII)

            val headerSize: Long
            val boxTotalSize: Long

            when (size32) {
                1L -> {
                    // 64-bit largesize box
                    if (currentOffset + EXTENDED_HEADER_SIZE > fileSize) break
                    headerBuf.clear()
                    headerBuf.limit(8)
                    channel.position(currentOffset + STANDARD_HEADER_SIZE)
                    if (!readExactly(channel, headerBuf)) break
                    headerBuf.flip()
                    val largeSize = headerBuf.long
                    if (largeSize < EXTENDED_HEADER_SIZE) break
                    headerSize = EXTENDED_HEADER_SIZE
                    boxTotalSize = largeSize
                }
                0L -> {
                    // Box extends to end of file
                    headerSize = STANDARD_HEADER_SIZE
                    boxTotalSize = fileSize - currentOffset
                }
                else -> {
                    if (size32 < STANDARD_HEADER_SIZE) {
                        if (boxCount == 0) return null
                        break
                    }
                    headerSize = STANDARD_HEADER_SIZE
                    boxTotalSize = size32
                }
            }

            val boundedEnd = (currentOffset + boxTotalSize).coerceAtMost(fileSize)
            val payloadOffset = currentOffset + headerSize
            val payloadLength = boundedEnd - payloadOffset
            if (payloadLength < 0L) break

            when (boxType) {
                "ftyp", "styp", "qt  " -> hasFtyp = true
                "moov", "moof" -> hasMoov = true
                "mdat" -> {
                    if (payloadLength > bestMdatLength) {
                        bestMdatOffset = payloadOffset
                        bestMdatLength = payloadLength
                    }
                }
            }

            currentOffset += boxTotalSize
            boxCount++
        }

        if (bestMdatOffset < 0L || bestMdatLength <= 0L || (!hasFtyp && !hasMoov)) {
            return null
        }

        return PayloadRegion(
            offset = bestMdatOffset,
            length = bestMdatLength,
            hasMoov = hasMoov,
            hasFtyp = hasFtyp
        )
    }

    fun inspectMdatPayload(file: File): PayloadRegion? {
        if (!file.exists() || !file.isFile) return null
        FileInputStream(file).use { fis ->
            return inspectMdatPayload(fis.channel, file.length())
        }
    }

    private fun isPrintableFourCc(b0: Byte, b1: Byte, b2: Byte, b3: Byte): Boolean {
        return isValidFourCcByte(b0) &&
            isValidFourCcByte(b1) &&
            isValidFourCcByte(b2) &&
            isValidFourCcByte(b3)
    }

    private fun isValidFourCcByte(b: Byte): Boolean {
        val code = b.toInt() and 0xFF
        return code in 0x20..0x7E
    }

    private fun readExactly(channel: FileChannel, buffer: ByteBuffer): Boolean {
        while (buffer.hasRemaining()) {
            val bytesRead = channel.read(buffer)
            if (bytesRead <= 0) return false
        }
        return true
    }
}

typealias PayloadRegion = Mp4ContainerInspector.PayloadRegion
