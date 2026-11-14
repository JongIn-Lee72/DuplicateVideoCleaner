package com.antigravity.duplicleaner.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.PI
import kotlin.math.sin

/**
 * 실제 기기나 에뮬레이터에서 "파일명이 완전히 다르지만 내용이 100% 동일한 영상/파일" 및
 * "메타데이터(moov) 크기는 다르지만 실제 영상 스트림(mdat)이 100% 동일한 MP4 컨테이너" 시나리오를
 * 즉시 테스트해 볼 수 있도록 샘플 중복 파일 세트를 생성하는 헬퍼.
 */
object DemoSampleGenerator {

    suspend fun createRealisticDuplicateSamples(context: Context): Int = withContext(Dispatchers.IO) {
        val baseDir = File(context.getExternalFilesDir(null), "DemoDuplicates")
        if (baseDir.exists()) {
            baseDir.deleteRecursively()
        }

        val cameraDir = File(baseDir, "DCIM/Camera").apply { mkdirs() }
        val moviesDir = File(baseDir, "Movies").apply { mkdirs() }
        val downloadDir = File(baseDir, "Download").apply { mkdirs() }
        val kakaoDir = File(baseDir, "Download/KakaoTalkDownload").apply { mkdirs() }
        val musicDir = File(baseDir, "Music").apply { mkdirs() }
        val picturesDir = File(baseDir, "Pictures").apply { mkdirs() }

        // 그룹 1: 여행 원본 영상 (1.2MB) vs 카톡 재전송 파일명 변경본 vs 브라우저 (1) 중복 다운로드본
        val payloadVideoA = generateDeterministicPayload(seed = 20261111L, sizeBytes = 1_200_000)
        writeFile(File(cameraDir, "VID_20261101_ JejuTrip_4K.mp4"), payloadVideoA, ageDays = 10)
        writeFile(File(kakaoDir, "KakaoTalk_Video_2026-11-05-14-22.mp4"), payloadVideoA, ageDays = 5)
        writeFile(File(downloadDir, "VID_20261101_ JejuTrip_4K (1).mp4"), payloadVideoA, ageDays = 1)

        // 그룹 2: 강의 녹화 영상 (900KB) vs 다른 이름으로 저장된 복사본
        val payloadVideoB = generateDeterministicPayload(seed = 99887766L, sizeBytes = 900_000)
        writeFile(File(downloadDir, "Android_Architecture_Lecture_01.mp4"), payloadVideoB, ageDays = 14)
        writeFile(File(downloadDir, "lecture_backup - 복사본.mp4"), payloadVideoB, ageDays = 2)

        // 대조군: 파일 크기(900KB)와 앞/뒤 바이트는 같지만 중간 바이트가 다른 별개 영상 (중복으로 잡히면 안 됨!)
        val payloadDifferentMiddle = payloadVideoB.clone().also { bytes ->
            val mid = bytes.size / 2
            bytes[mid] = (bytes[mid].toInt() xor 0xFF).toByte()
        }
        writeFile(File(downloadDir, "Android_Architecture_Lecture_02_Edited.mp4"), payloadDifferentMiddle, ageDays = 1)

        // 그룹 3 (Stage 2.5 MP4 컨테이너 스트림 매칭 데모):
        // moov 메타데이터 박스 크기가 4KB vs 12KB로 달라서 전체 파일 크기가 8KB 차이 나지만,
        // 내부 600KB mdat 미디어 스트림 페이로드가 100% 동일한 두 MP4 파일
        val sharedMdatPayload = ByteArray(600 * 1024).also { Random(77772026L).nextBytes(it) }
        val fastStartMp4 = buildMp4ContainerBytes(
            moovPayloadSize = 4 * 1024,
            moovSeed = 101L,
            mdatPayload = sharedMdatPayload
        )
        val remuxedMetaMp4 = buildMp4ContainerBytes(
            moovPayloadSize = 12 * 1024,
            moovSeed = 202L,
            mdatPayload = sharedMdatPayload
        )
        writeFile(File(moviesDir, "Clip_2026_Original_FastStart.mp4"), fastStartMp4, ageDays = 7)
        writeFile(File(downloadDir, "Clip_2026_ReMuxed_Meta.mp4"), remuxedMetaMp4, ageDays = 3)

        // 그룹 4 (Stage 2.5 MP3 컨테이너 스트림 매칭 데모):
        // ID3v2 태그 메타데이터 크기가 2KB vs 10KB로 달라서 전체 파일 크기가 8KB 차이 나지만,
        // 내부 550KB MPEG 오디오 프레임 스트림 페이로드가 100% 동일한 두 MP3 파일
        val sharedAudioPayload = generateMpegAudioPayload(seed = 88991122L, sizeBytes = 550 * 1024)
        val originalMp3 = buildMp3ContainerBytes(
            id3v2TagSize = 2 * 1024,
            tagSeed = 301L,
            audioPayload = sharedAudioPayload
        )
        val retaggedMp3 = buildMp3ContainerBytes(
            id3v2TagSize = 10 * 1024,
            tagSeed = 402L,
            audioPayload = sharedAudioPayload
        )
        writeFile(File(musicDir, "Podcast_Episode_12_Original.mp3"), originalMp3, ageDays = 8)
        writeFile(File(downloadDir, "Podcast_Episode_12_Retagged.mp3"), retaggedMp3, ageDays = 2)

        // 그룹 5 (Stage 4 비주얼 퍼셉추얼 해시 데모 - 실제 PNG 풍경 및 좌우 반전 복사본):
        // 128x128 비대칭 컬러 체커보드/그라디언트 PNG 원본 vs 가로 반전본 (x -> 127 - x)
        val landscapePng = encodeMinimalRgbPng(128, 128) { x, y ->
            val r = (x * 2) and 0xFF
            val g = (y * 2) and 0xFF
            val b = ((x xor y) * 3) and 0xFF
            Triple(r, g, b)
        }
        val mirroredPng = encodeMinimalRgbPng(128, 128) { x, y ->
            val origX = 127 - x
            val r = (origX * 2) and 0xFF
            val g = (y * 2) and 0xFF
            val b = ((origX xor y) * 3) and 0xFF
            Triple(r, g, b)
        }
        writeFile(File(picturesDir, "RealSample_Landscape.png"), landscapePng, ageDays = 6)
        writeFile(File(downloadDir, "RealSample_Landscape_Mirrored.png"), mirroredPng, ageDays = 1)

        // 그룹 6 (Stage 4 오디오 파형 핑거프린트 데모 - 실제 WAV 오디오 크로스 볼륨 복사본):
        // 44바이트 RIFF/WAVE 표준 16-bit PCM (8000Hz mono, 2초) 원본 vs 0.65x 음량 감소 복사본
        val voiceMemoOriginal = encodeMinimalPcmWav(durationSeconds = 2, sampleRate = 8000, amplitudeScale = 1.0f)
        val voiceMemoQuiet = encodeMinimalPcmWav(durationSeconds = 2, sampleRate = 8000, amplitudeScale = 0.65f)
        writeFile(File(musicDir, "VoiceMemo_Original.wav"), voiceMemoOriginal, ageDays = 4)
        writeFile(File(downloadDir, "VoiceMemo_QuietCopy.wav"), voiceMemoQuiet, ageDays = 1)

        14
    }

    /**
     * Android [android.graphics.Bitmap] 프레임워크 의존성 없이 순수 코틀린 / JVM 표준 라이브러리만으로
     * 유효한 24-bit RGB PNG 바이너리를 인코딩하는 헬퍼 (JUnit 로컬 테스트 100% 호환).
     */
    fun encodeMinimalRgbPng(
        width: Int,
        height: Int,
        rgbPixelProvider: (x: Int, y: Int) -> Triple<Int, Int, Int>
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // 1. PNG Signature (8 bytes)
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        // 2. IHDR Chunk (13 bytes payload)
        // Width (4), Height (4), Bit depth (1 = 8), Color type (1 = 2 RGB),
        // Compression method (1 = 0 deflate), Filter method (1 = 0 adaptive), Interlace method (1 = 0 none)
        val ihdrPayload = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            .putInt(width)
            .putInt(height)
            .put(8.toByte()) // bit depth
            .put(2.toByte()) // color type: 2 (RGB)
            .put(0.toByte()) // compression method (0)
            .put(0.toByte()) // filter method (0)
            .put(0.toByte()) // interlace method (0)
            .array()
        writePngChunk(out, "IHDR", ihdrPayload)

        // 3. IDAT Chunk: Raw scanlines with filter byte 0x00 per line, deflated
        val rawScanlines = ByteArray(height * (1 + width * 3))
        var offset = 0
        for (y in 0 until height) {
            rawScanlines[offset++] = 0x00.toByte() // Filter type 0 (None)
            for (x in 0 until width) {
                val (r, g, b) = rgbPixelProvider(x, y)
                rawScanlines[offset++] = (r and 0xFF).toByte()
                rawScanlines[offset++] = (g and 0xFF).toByte()
                rawScanlines[offset++] = (b and 0xFF).toByte()
            }
        }

        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(rawScanlines)
        deflater.finish()

        val idatBuffer = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            idatBuffer.write(buffer, 0, count)
        }
        deflater.end()

        writePngChunk(out, "IDAT", idatBuffer.toByteArray())

        // 4. IEND Chunk
        writePngChunk(out, "IEND", ByteArray(0))

        return out.toByteArray()
    }

    private fun writePngChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val length = data.size

        // Length (4 bytes, big endian)
        val lenBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(length).array()
        out.write(lenBytes)

        // Type (4 bytes)
        out.write(typeBytes)

        // Data
        if (data.isNotEmpty()) {
            out.write(data)
        }

        // CRC32 calculated over Type + Data
        val crc = CRC32()
        crc.update(typeBytes)
        if (data.isNotEmpty()) {
            crc.update(data)
        }
        val crcValue = crc.value.toInt()
        val crcBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crcValue).array()
        out.write(crcBytes)
    }

    /**
     * Android 오디오 프레임워크 의존성 없이 순수 코틀린으로 유효한 44바이트 헤더 표준 RIFF/WAVE
     * 16-bit mono PCM 오디오 바이너리를 생성하는 헬퍼 (JUnit 로컬 테스트 100% 호환).
     * alternating 440Hz / 660Hz / 880Hz 다중 주파수 톤 및 [amplitudeScale] 배율을 지원합니다.
     */
    fun encodeMinimalPcmWav(
        durationSeconds: Int = 2,
        sampleRate: Int = 8000,
        amplitudeScale: Float = 1.0f
    ): ByteArray {
        val numSamples = durationSeconds * sampleRate
        val pcmDataSize = numSamples * 2 // 16-bit = 2 bytes per sample
        val totalFileSize = 44 + pcmDataSize

        val out = ByteBuffer.allocate(totalFileSize).order(ByteOrder.LITTLE_ENDIAN)

        // 1. RIFF Chunk Descriptor
        out.put("RIFF".toByteArray(Charsets.US_ASCII))
        out.putInt(totalFileSize - 8) // ChunkSize = total - 8
        out.put("WAVE".toByteArray(Charsets.US_ASCII))

        // 2. "fmt " Sub-chunk
        out.put("fmt ".toByteArray(Charsets.US_ASCII))
        out.putInt(16) // Subchunk1Size = 16 for PCM
        out.putShort(1.toShort()) // AudioFormat = 1 (PCM linear)
        out.putShort(1.toShort()) // NumChannels = 1 (mono)
        out.putInt(sampleRate) // SampleRate
        out.putInt(sampleRate * 2) // ByteRate = SampleRate * NumChannels * BitsPerSample/8
        out.putShort(2.toShort()) // BlockAlign = NumChannels * BitsPerSample/8
        out.putShort(16.toShort()) // BitsPerSample = 16

        // 3. "data" Sub-chunk
        out.put("data".toByteArray(Charsets.US_ASCII))
        out.putInt(pcmDataSize)

        // Generate deterministic multi-tone melody
        // Tones: 440Hz (A4), 660Hz (E5), 880Hz (A5) cycling each 0.5s with cosine window envelope
        val segmentSamples = sampleRate / 2
        for (i in 0 until numSamples) {
            val segmentIdx = (i / segmentSamples) % 3
            val freq = when (segmentIdx) {
                0 -> 440.0
                1 -> 660.0
                else -> 880.0
            }

            val t = i.toDouble() / sampleRate
            val rawSine = sin(2.0 * PI * freq * t)

            // Soft envelope per segment to avoid clicks
            val segmentPos = (i % segmentSamples).toDouble() / segmentSamples
            val envelope = sin(PI * segmentPos)

            val sampleVal = (rawSine * envelope * 32000.0 * amplitudeScale.toDouble())
                .coerceIn(-32768.0, 32767.0)
                .toInt()
                .toShort()

            out.putShort(sampleVal)
        }

        return out.array()
    }

    private fun generateMpegAudioPayload(seed: Long, sizeBytes: Int): ByteArray {
        val payload = ByteArray(sizeBytes).also { Random(seed).nextBytes(it) }
        // Ensure standard MPEG-1 Audio Layer III sync word (0xFF, 0xFB) at the start of frames
        var offset = 0
        val frameStep = 1024
        while (offset < payload.size - 1) {
            payload[offset] = 0xFF.toByte()
            payload[offset + 1] = 0xFB.toByte() // MPEG 1 Layer 3, no CRC
            offset += frameStep
        }
        return payload
    }

    private fun buildMp3ContainerBytes(
        id3v2TagSize: Int,
        tagSeed: Long,
        audioPayload: ByteArray
    ): ByteArray {
        // ID3v2 header is 10 bytes: "ID3" (3) + version (2) + flags (1) + size (4 synchsafe)
        // Ensure id3v2TagSize is at least 10
        val tagPayloadSize = (id3v2TagSize - 10).coerceAtLeast(0)
        val totalSize = 10 + tagPayloadSize + audioPayload.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        // 1. ID3 identifier
        buffer.put("ID3".toByteArray(Charsets.US_ASCII))
        buffer.put(0x03.toByte()) // v2.3
        buffer.put(0x00.toByte()) // revision
        buffer.put(0x00.toByte()) // flags

        // 4 bytes synchsafe integer for tagPayloadSize (7 bits per byte)
        val s0 = ((tagPayloadSize shr 21) and 0x7F).toByte()
        val s1 = ((tagPayloadSize shr 14) and 0x7F).toByte()
        val s2 = ((tagPayloadSize shr 7) and 0x7F).toByte()
        val s3 = (tagPayloadSize and 0x7F).toByte()
        buffer.put(byteArrayOf(s0, s1, s2, s3))

        // Tag payload
        if (tagPayloadSize > 0) {
            val tagBytes = ByteArray(tagPayloadSize).also { Random(tagSeed).nextBytes(it) }
            buffer.put(tagBytes)
        }

        // 2. Audio Payload
        buffer.put(audioPayload)

        return buffer.array()
    }

    private fun buildMp4ContainerBytes(
        moovPayloadSize: Int,
        moovSeed: Long,
        mdatPayload: ByteArray
    ): ByteArray {
        val ftypBoxSize = 24
        val moovBoxSize = 8 + moovPayloadSize
        val mdatBoxSize = 8 + mdatPayload.size
        val totalSize = ftypBoxSize + moovBoxSize + mdatBoxSize

        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        // 1. ftyp box (24 bytes)
        buffer.putInt(ftypBoxSize)
        buffer.put("ftyp".toByteArray(Charsets.US_ASCII))
        buffer.put("mp42".toByteArray(Charsets.US_ASCII))
        buffer.putInt(0)
        buffer.put("mp42isom".toByteArray(Charsets.US_ASCII))

        // 2. moov box (8 + moovPayloadSize bytes)
        val moovPayload = ByteArray(moovPayloadSize).also { Random(moovSeed).nextBytes(it) }
        buffer.putInt(moovBoxSize)
        buffer.put("moov".toByteArray(Charsets.US_ASCII))
        buffer.put(moovPayload)

        // 3. mdat box (8 + mdatPayload.size bytes)
        buffer.putInt(mdatBoxSize)
        buffer.put("mdat".toByteArray(Charsets.US_ASCII))
        buffer.put(mdatPayload)

        return buffer.array()
    }

    private fun generateDeterministicPayload(seed: Long, sizeBytes: Int): ByteArray {
        val random = Random(seed)
        val bytes = ByteArray(sizeBytes)
        random.nextBytes(bytes)
        // MP4 ftyp header signature simulation
        val header = byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, 0x6D, 0x70, 0x34, 0x32)
        System.arraycopy(header, 0, bytes, 0, header.size.coerceAtMost(bytes.size))
        return bytes
    }

    private fun writeFile(target: File, content: ByteArray, ageDays: Int) {
        FileOutputStream(target).use { fos ->
            fos.write(content)
        }
        val timestamp = System.currentTimeMillis() - (ageDays * 24L * 3600L * 1000L)
        target.setLastModified(timestamp)
    }
}
