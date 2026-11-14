package com.antigravity.duplicleaner.data.hash

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 32-밴드 RMS 에너지 엔벨로프 기반 오디오 파형 지각 해시.
 * L2 단위 벡터 정규화가 적용되어 오디오 음량(gain)이나 포맷이 달라도 동일 파형을 식별합니다.
 */
data class AudioEnvelopeFingerprint(
    val bands: FloatArray,
    val durationMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioEnvelopeFingerprint
        if (!bands.contentEquals(other.bands)) return false
        if (durationMs != other.durationMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = bands.contentHashCode()
        result = 31 * result + durationMs.hashCode()
        return result
    }
}

/**
 * 오디오 파일(MP3, WAV, AAC, M4A 등)의 시간축 진폭 엔벨로프를 추출하여
 * 음량 차이 및 포맷 변환을 극복하는 크로스 오디오 지각 해시 엔진.
 */
object AudioWaveformHasher {

    const val BAND_COUNT = 32

    /**
     * 16비트 모노 PCM 샘플 배열로부터 32개 밴드의 정규화된 RMS 에너지 엔벨로프 지문을 계산합니다.
     * 무음(모든 샘플이 0)이거나 샘플 수가 [BAND_COUNT] 미만인 경우 null을 반환합니다.
     */
    fun computePcmEnvelopeFingerprint(pcmSamples: ShortArray, durationMs: Long = 0L): AudioEnvelopeFingerprint? {
        if (pcmSamples.size < BAND_COUNT) return null

        val bands = FloatArray(BAND_COUNT)
        val totalSamples = pcmSamples.size

        for (band in 0 until BAND_COUNT) {
            val start = (band.toLong() * totalSamples / BAND_COUNT).toInt()
            val end = ((band + 1).toLong() * totalSamples / BAND_COUNT).toInt()
            val count = end - start
            if (count <= 0) continue

            var sumSquares = 0.0
            for (i in start until end) {
                val s = pcmSamples[i].toDouble()
                sumSquares += s * s
            }
            bands[band] = sqrt(sumSquares / count).toFloat()
        }

        // L2 노름(Euclidean norm) 계산 및 단위 벡터 정규화
        var l2Sum = 0.0
        for (band in 0 until BAND_COUNT) {
            val v = bands[band].toDouble()
            l2Sum += v * v
        }
        val l2Norm = sqrt(l2Sum)
        if (l2Norm <= 1e-9 || l2Norm.isNaN() || l2Norm.isInfinite()) {
            // 무음 오디오 트랙
            return null
        }

        val normalizedBands = FloatArray(BAND_COUNT) { i ->
            (bands[i] / l2Norm).toFloat()
        }

        return AudioEnvelopeFingerprint(normalizedBands, durationMs)
    }

    /**
     * 두 오디오 지문 간의 코사인 유사도와 평균 절대 차이(L1 penalty)를 결합하여
     * 0% ~ 99% 범위의 정수 유사도를 계산합니다.
     * 재생 시간이 둘 다 제공된 경우 12% 이상의 재생 시간 차이가 나면 0%를 반환합니다.
     */
    fun computeEnvelopeSimilarityPercent(fpA: AudioEnvelopeFingerprint, fpB: AudioEnvelopeFingerprint): Int {
        if (fpA.bands.size != BAND_COUNT || fpB.bands.size != BAND_COUNT) {
            return 0
        }

        if (fpA.durationMs > 0L && fpB.durationMs > 0L) {
            val maxDuration = maxOf(fpA.durationMs, fpB.durationMs)
            val allowedDiff = maxOf(2500L, (maxDuration * 0.12f).toLong())
            if (abs(fpA.durationMs - fpB.durationMs) > allowedDiff) {
                return 0
            }
        }

        // L2 정규화 벡터의 내적 = 코사인 유사도
        var dot = 0f
        var sumAbsDiff = 0f
        for (i in 0 until BAND_COUNT) {
            val a = fpA.bands[i]
            val b = fpB.bands[i]
            dot += a * b
            sumAbsDiff += abs(a - b)
        }

        val cosine = dot.coerceIn(0f, 1f)
        val meanAbsDiff = sumAbsDiff / BAND_COUNT.toFloat()

        // 코사인 점수에 L1 차이 페널티를 부여하여 식별력 극대화 (0..99%)
        val score = (cosine * 100f - meanAbsDiff * 40f).roundToInt()
        return score.coerceIn(0, 99)
    }

    /**
     * 순수 Kotlin으로 16비트 PCM RIFF/WAVE 파일을 파싱하여
     * 최대 [maxSamples]개의 모노 PCM 샘플 배열과 durationMs를 추출합니다.
     * 스테레오 채널은 평균화하여 모노로 변환합니다. 유효하지 않은 포맷 시 null을 반환합니다.
     */
    fun parseWavPcmSamples(file: File, maxSamples: Int = 44100 * 15): Pair<ShortArray, Long>? {
        if (!file.exists() || !file.isFile || file.length() < 44L) return null

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val riffHeader = ByteArray(4)
                raf.readFully(riffHeader)
                if (riffHeader[0] != 'R'.code.toByte() ||
                    riffHeader[1] != 'I'.code.toByte() ||
                    riffHeader[2] != 'F'.code.toByte() ||
                    riffHeader[3] != 'F'.code.toByte()
                ) {
                    return null
                }

                // ChunkSize (fileSize - 8)
                raf.readInt()

                val waveHeader = ByteArray(4)
                raf.readFully(waveHeader)
                if (waveHeader[0] != 'W'.code.toByte() ||
                    waveHeader[1] != 'A'.code.toByte() ||
                    waveHeader[2] != 'V'.code.toByte() ||
                    waveHeader[3] != 'E'.code.toByte()
                ) {
                    return null
                }

                var numChannels = 0
                var sampleRate = 0
                var bitsPerSample = 0
                var audioFormat = 0
                var dataOffset = -1L
                var dataSize = 0L

                val fileLength = raf.length()
                val chunkIdBytes = ByteArray(4)

                while (raf.filePointer <= fileLength - 8L) {
                    raf.readFully(chunkIdBytes)
                    val chunkSize = Integer.reverseBytes(raf.readInt()).toLong() and 0xFFFFFFFFL
                    val chunkId = String(chunkIdBytes, Charsets.US_ASCII)

                    when (chunkId) {
                        "fmt " -> {
                            val fmtStart = raf.filePointer
                            if (chunkSize < 16) return null
                            audioFormat = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF
                            numChannels = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF
                            sampleRate = Integer.reverseBytes(raf.readInt())
                            val byteRate = Integer.reverseBytes(raf.readInt())
                            val blockAlign = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF
                            bitsPerSample = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF

                            val nextPos = fmtStart + chunkSize + (chunkSize % 2L)
                            if (nextPos in (fmtStart + 16)..fileLength) {
                                raf.seek(nextPos)
                            } else {
                                break
                            }
                        }
                        "data" -> {
                            dataOffset = raf.filePointer
                            dataSize = chunkSize
                            if (numChannels > 0 && sampleRate > 0) {
                                break
                            }
                            val nextPos = dataOffset + chunkSize + (chunkSize % 2L)
                            if (nextPos in (dataOffset + 1)..fileLength) {
                                raf.seek(nextPos)
                            } else {
                                break
                            }
                        }
                        else -> {
                            val nextPos = raf.filePointer + chunkSize + (chunkSize % 2L)
                            if (nextPos in (raf.filePointer + 1)..fileLength) {
                                raf.seek(nextPos)
                            } else {
                                break
                            }
                        }
                    }
                }

                if (audioFormat != 1 || bitsPerSample != 16 || numChannels <= 0 || sampleRate <= 0 || dataOffset < 0L) {
                    return null
                }

                val availableDataBytes = (fileLength - dataOffset).coerceAtLeast(0L)
                val actualDataBytes = if (dataSize in 1L..availableDataBytes) dataSize else availableDataBytes
                val bytesPerFrame = numChannels * 2
                val totalFrames = actualDataBytes / bytesPerFrame
                if (totalFrames <= 0) return null

                val durationMs = (totalFrames * 1000L) / sampleRate
                val framesToRead = minOf(maxSamples.toLong(), totalFrames).toInt()
                if (framesToRead <= 0) return null

                raf.seek(dataOffset)
                val bytesToRead = framesToRead * bytesPerFrame
                val pcmBytes = ByteArray(bytesToRead)
                raf.readFully(pcmBytes)

                val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val pcmSamples = ShortArray(framesToRead)

                if (numChannels == 1) {
                    shortBuffer.get(pcmSamples)
                } else {
                    for (i in 0 until framesToRead) {
                        var sum = 0
                        for (c in 0 until numChannels) {
                            sum += shortBuffer.get().toInt()
                        }
                        pcmSamples[i] = (sum / numChannels).toShort()
                    }
                }

                Pair(pcmSamples, durationMs)
            }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 오디오 파일에서 최대 15초 분량의 파형 엔벨로프 지문을 추출합니다.
     * .wav 파일인 경우 순수 Kotlin 파서를 우선 사용하고, 그 외에는 MediaExtractor + MediaCodec을 사용합니다.
     */
    fun extractAudioFingerprint(
        context: Context,
        uri: Uri,
        absolutePath: String? = null,
        knownDurationMs: Long = 0L
    ): AudioEnvelopeFingerprint? {
        // 1. WAV 파일인 경우 파서 우선 시도 (JVM 단위 테스트 호환성 및 초고속 I/O)
        val candidatePath = absolutePath ?: if (uri.scheme == "file") uri.path else null
        if (!candidatePath.isNullOrBlank()) {
            val file = File(candidatePath)
            if (file.exists() && file.isFile) {
                if (file.name.endsWith(".wav", ignoreCase = true) || isWavFileHeader(file)) {
                    val parsed = parseWavPcmSamples(file)
                    if (parsed != null) {
                        val duration = if (knownDurationMs > 0L) knownDurationMs else parsed.second
                        val fp = computePcmEnvelopeFingerprint(parsed.first, duration)
                        if (fp != null) return fp
                    }
                }
            }
        }

        // 2. MediaExtractor + MediaCodec 디코딩 시도
        return decodeViaMediaCodec(context, uri, absolutePath, knownDurationMs)
    }

    private fun isWavFileHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 12L) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(12)
                raf.readFully(header)
                header[0] == 'R'.code.toByte() &&
                    header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() &&
                    header[3] == 'F'.code.toByte() &&
                    header[8] == 'W'.code.toByte() &&
                    header[9] == 'A'.code.toByte() &&
                    header[10] == 'V'.code.toByte() &&
                    header[11] == 'E'.code.toByte()
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun decodeViaMediaCodec(
        context: Context,
        uri: Uri,
        absolutePath: String?,
        knownDurationMs: Long,
        maxSamples: Int = 44100 * 15
    ): AudioEnvelopeFingerprint? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        return try {
            extractor = MediaExtractor()
            if (!absolutePath.isNullOrBlank() && File(absolutePath).exists()) {
                extractor.setDataSource(absolutePath)
            } else {
                extractor.setDataSource(context, uri, null)
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrackIndex == -1 || audioFormat == null) return null

            extractor.selectTrack(audioTrackIndex)

            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            val calculatedDurationMs = durationUs / 1000L
            val durationMs = if (knownDurationMs > 0L) knownDurationMs else calculatedDurationMs

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val sampleList = ArrayList<Short>(minOf(maxSamples, 65536))
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            val timeoutUs = 5000L
            var emptyCount = 0

            while (!outputEos && sampleList.size < maxSamples && emptyCount < 50) {
                if (!inputEos) {
                    val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputIndex >= 0) {
                    emptyCount = 0
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEos = true
                    }
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val outputFormat = codec.outputFormat
                        val channelCount = if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } else 1

                        val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        if (channelCount <= 1) {
                            while (shortBuffer.hasRemaining() && sampleList.size < maxSamples) {
                                sampleList.add(shortBuffer.get())
                            }
                        } else {
                            while (shortBuffer.remaining() >= channelCount && sampleList.size < maxSamples) {
                                var sum = 0
                                for (c in 0 until channelCount) {
                                    sum += shortBuffer.get().toInt()
                                }
                                sampleList.add((sum / channelCount).toShort())
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    emptyCount++
                }
            }

            if (sampleList.size < BAND_COUNT) return null

            val pcmArray = ShortArray(sampleList.size)
            for (i in sampleList.indices) {
                pcmArray[i] = sampleList[i]
            }

            computePcmEnvelopeFingerprint(pcmArray, durationMs)
        } catch (e: Throwable) {
            null
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {}
            try {
                codec?.release()
            } catch (_: Throwable) {}
            try {
                extractor?.release()
            } catch (_: Throwable) {}
        }
    }
}
