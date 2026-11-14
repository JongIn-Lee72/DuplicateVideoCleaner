package com.antigravity.duplicleaner.data.hash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 메신저 재인코딩·해상도 변경·압축으로 바이트 해시가 달라진 동일 영상을 탐지하는
 * 키프레임 기반 지각 해시(Perceptual Difference Hash, dHash 및 2D-DCT pHash) 엔진.
 */
object VideoPerceptualHasher {

    // 인트로/아웃트로 검은 화면을 피해 영상의 15%, 32%, 50%, 68%, 85% 지점 5개 프레임 샘플링 (12초 이하 단편 영상)
    private val SAMPLE_POSITIONS = floatArrayOf(0.15f, 0.32f, 0.50f, 0.68f, 0.85f)
    // 12초 초과 영상의 시계열 부분 수열(TemporalSequenceAligner) 정밀 정렬을 위한 7개 키프레임 샘플링 지점
    private val EXTENDED_SAMPLE_POSITIONS = floatArrayOf(0.10f, 0.23f, 0.36f, 0.50f, 0.64f, 0.77f, 0.90f)
    private const val EXTENDED_DURATION_THRESHOLD_MS = 12_000L
    private const val MAX_BIT_DISTANCE_PER_FRAME = 11 // 64비트 중 11비트 이하 차이 (~83% 이상 일치)

    private const val PHASH_GRID_SIZE = 16
    private const val PHASH_DCT_SIZE = 8

    // 16x16 -> 8x8 저주파 2D DCT-II 계산을 위한 사전 계산 코사인 및 정규화 계수 테이블
    private val DCT_COS_TABLE: Array<DoubleArray> = Array(PHASH_DCT_SIZE) { u ->
        DoubleArray(PHASH_GRID_SIZE) { x ->
            cos(((2.0 * x + 1.0) * u * PI) / (2.0 * PHASH_GRID_SIZE))
        }
    }

    private val DCT_ALPHA: DoubleArray = DoubleArray(PHASH_DCT_SIZE) { u ->
        if (u == 0) sqrt(1.0 / PHASH_GRID_SIZE) else sqrt(2.0 / PHASH_GRID_SIZE)
    }

    data class DualPerceptualSignature(
        val dHashes: List<Long>,
        val pHashes: List<Long>
    )

    /**
     * 비디오 메타데이터에 기록된 회전각(90, 180, 270도 등)이 존재하는 경우 Bitmap을 정방향으로 회전합니다.
     */
    fun rotateBitmapIfNeeded(bitmap: Bitmap, degrees: Int): Bitmap {
        val normalizedDegrees = ((degrees % 360) + 360) % 360
        if (normalizedDegrees == 0) return bitmap
        return try {
            val matrix = Matrix().apply {
                postRotate(normalizedDegrees.toFloat())
            }
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )
        } catch (_: Exception) {
            bitmap
        }
    }

    /**
     * 지정된 영상 URI에서 재생 길이에 따라 최대 7개(12초 초과 시 7개, 이하 시 5개)의
     * 대표 키프레임 64-bit dHash 및 64-bit DCT pHash 시퀀스를 단일 패스로 추출합니다.
     */
    fun extractDualVideoSignature(
        context: Context,
        uri: Uri,
        knownDurationMs: Long = 0L
    ): DualPerceptualSignature {
        return extractExtendedDualVideoSignature(
            context = context,
            uri = uri,
            knownDurationMs = knownDurationMs
        )
    }

    /**
     * TemporalSequenceAligner의 풍부한 시간 해상도를 위해 12초 초과 영상에서는 7개 키프레임
     * (0.10f, 0.23f, 0.36f, 0.50f, 0.64f, 0.77f, 0.90f), 12초 이하 영상에서는 5개 키프레임을 추출합니다.
     */
    fun extractExtendedDualVideoSignature(
        context: Context,
        uri: Uri,
        knownDurationMs: Long = 0L
    ): DualPerceptualSignature {
        val retriever = MediaMetadataRetriever()
        val dHashes = mutableListOf<Long>()
        val pHashes = mutableListOf<Long>()
        try {
            retriever.setDataSource(context, uri)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val durationMs = if (knownDurationMs > 0L) {
                knownDurationMs
            } else {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            }

            if (durationMs < 500L) return DualPerceptualSignature(emptyList(), emptyList())

            val samplePositions = if (durationMs > EXTENDED_DURATION_THRESHOLD_MS) {
                EXTENDED_SAMPLE_POSITIONS
            } else {
                SAMPLE_POSITIONS
            }

            for (ratio in samplePositions) {
                val timeUs = (durationMs * ratio * 1000L).toLong()
                val frame = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                if (frame != null) {
                    val targetFrame = rotateBitmapIfNeeded(frame, rotation)
                    try {
                        dHashes.add(computeBitmapDHash(targetFrame))
                        pHashes.add(computeBitmapPHash(targetFrame))
                    } finally {
                        if (targetFrame !== frame) {
                            targetFrame.recycle()
                        }
                        frame.recycle()
                    }
                }
            }
        } catch (_: Exception) {
            return DualPerceptualSignature(emptyList(), emptyList())
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
        return DualPerceptualSignature(dHashes = dHashes, pHashes = pHashes)
    }

    /**
     * 지정된 이미지 URI에서 다운샘플링된 Bitmap을 디코딩하여 단일 프레임 dHash 및 pHash를 추출합니다.
     */
    fun extractImageDualSignature(
        context: Context,
        uri: Uri
    ): DualPerceptualSignature {
        return try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
            var sampleSize = 1
            val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
            while (maxDim / sampleSize > 128) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return DualPerceptualSignature(emptyList(), emptyList())

            try {
                val dHash = computeBitmapDHash(bitmap)
                val pHash = computeBitmapPHash(bitmap)
                DualPerceptualSignature(
                    dHashes = listOf(dHash),
                    pHashes = listOf(pHash)
                )
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) {
            DualPerceptualSignature(emptyList(), emptyList())
        }
    }

    /**
     * 지정된 영상 URI에서 대표 키프레임 5개의 64-bit dHash 시퀀스를 추출합니다.
     */
    fun extractVideoSignature(
        context: Context,
        uri: Uri,
        knownDurationMs: Long = 0L
    ): List<Long> {
        val retriever = MediaMetadataRetriever()
        val hashes = mutableListOf<Long>()
        try {
            retriever.setDataSource(context, uri)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val durationMs = if (knownDurationMs > 0L) {
                knownDurationMs
            } else {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            }

            if (durationMs < 500L) return emptyList()

            for (ratio in SAMPLE_POSITIONS) {
                val timeUs = (durationMs * ratio * 1000L).toLong()
                val frame = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                if (frame != null) {
                    val targetFrame = rotateBitmapIfNeeded(frame, rotation)
                    try {
                        hashes.add(computeBitmapDHash(targetFrame))
                    } finally {
                        if (targetFrame !== frame) {
                            targetFrame.recycle()
                        }
                        frame.recycle()
                    }
                }
            }
        } catch (_: Exception) {
            return emptyList()
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
        return hashes
    }

    /**
     * Bitmap을 9x8 그레이스케일로 축소한 뒤 인접 픽셀 밝기 구배를 비교하여 64-bit dHash를 생성합니다.
     */
    fun computeBitmapDHash(source: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(source, 9, 8, true)
        var hash = 0L
        try {
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val leftPixel = scaled.getPixel(x, y)
                    val rightPixel = scaled.getPixel(x + 1, y)

                    val leftLuma = computeLuma(leftPixel)
                    val rightLuma = computeLuma(rightPixel)

                    if (leftLuma > rightLuma) {
                        val bitIndex = y * 8 + x
                        hash = hash or (1L shl bitIndex)
                    }
                }
            }
        } finally {
            if (scaled !== source) {
                scaled.recycle()
            }
        }
        return hash
    }

    /**
     * 픽셀 밝기 배열(9x8 = 72개)로부터 64-bit dHash를 계산하는 순수 함수 (단위 테스트에서도 직접 검증 가능).
     */
    fun computeDHashFromGrayscaleGrid(grid9x8: IntArray): Long {
        require(grid9x8.size == 72) { "Grid must be 9x8 (72 elements)" }
        var hash = 0L
        for (y in 0 until 8) {
            val rowOffset = y * 9
            for (x in 0 until 8) {
                if (grid9x8[rowOffset + x] > grid9x8[rowOffset + x + 1]) {
                    hash = hash or (1L shl (y * 8 + x))
                }
            }
        }
        return hash
    }

    /**
     * 9x8 그레이스케일 그리드(72개)를 좌우 수평 반전(Horizontal Flip/Mirror)합니다.
     */
    fun flipGrayscaleGrid9x8Horizontally(grid9x8: IntArray): IntArray {
        require(grid9x8.size == 72) { "Grid must be 9x8 (72 elements)" }
        val flipped = IntArray(72)
        for (y in 0 until 8) {
            val rowOffset = y * 9
            for (x in 0 until 9) {
                flipped[rowOffset + x] = grid9x8[rowOffset + (8 - x)]
            }
        }
        return flipped
    }

    /**
     * 64-bit dHash 값을 비트 연산으로 좌우 수평 반전(Mirror)한 dHash로 변환합니다.
     */
    fun computeMirroredDHash(dHash: Long): Long {
        var mirroredHash = 0L
        for (y in 0 until 8) {
            val rowByte = ((dHash ushr (y * 8)) and 0xFFL).toInt()
            var rev = 0
            for (b in 0..7) {
                if (((rowByte ushr b) and 1) != 0) {
                    rev = rev or (1 shl (7 - b))
                }
            }
            val mirroredByte = (rev xor 0xFF) and 0xFF
            mirroredHash = mirroredHash or ((mirroredByte.toLong() and 0xFFL) shl (y * 8))
        }
        return mirroredHash
    }

    /**
     * Bitmap을 16x16 그레이스케일로 축소한 뒤 저주파 8x8 2D DCT 기반 64-bit pHash를 생성합니다.
     */
    fun computeBitmapPHash(source: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(source, PHASH_GRID_SIZE, PHASH_GRID_SIZE, true)
        val grid16x16 = IntArray(PHASH_GRID_SIZE * PHASH_GRID_SIZE)
        try {
            for (y in 0 until PHASH_GRID_SIZE) {
                val rowOffset = y * PHASH_GRID_SIZE
                for (x in 0 until PHASH_GRID_SIZE) {
                    grid16x16[rowOffset + x] = computeLuma(scaled.getPixel(x, y))
                }
            }
        } finally {
            if (scaled !== source) {
                scaled.recycle()
            }
        }
        return computePHashFromGrayscaleGrid(grid16x16)
    }

    /**
     * 16x16 픽셀 밝기 배열(256개)로부터 저주파 8x8 2D DCT를 계산하고,
     * AC 계수들의 중앙값(Median)과 비교하여 64-bit DCT Perceptual Hash(pHash)를 생성합니다.
     */
    fun computePHashFromGrayscaleGrid(grid16x16: IntArray): Long {
        require(grid16x16.size == PHASH_GRID_SIZE * PHASH_GRID_SIZE) {
            "Grid must be 16x16 (256 elements)"
        }

        // 1단계: 행(Row) 방향 1D DCT (16x16 -> 16x8)
        val rowDct = DoubleArray(PHASH_GRID_SIZE * PHASH_DCT_SIZE)
        for (y in 0 until PHASH_GRID_SIZE) {
            val rowBase = y * PHASH_GRID_SIZE
            val outBase = y * PHASH_DCT_SIZE
            for (u in 0 until PHASH_DCT_SIZE) {
                val cosRow = DCT_COS_TABLE[u]
                var sum = 0.0
                for (x in 0 until PHASH_GRID_SIZE) {
                    sum += grid16x16[rowBase + x] * cosRow[x]
                }
                rowDct[outBase + u] = sum * DCT_ALPHA[u]
            }
        }

        // 2단계: 열(Column) 방향 1D DCT (16x8 -> 8x8 저주파 계수)
        val dct8x8 = DoubleArray(PHASH_DCT_SIZE * PHASH_DCT_SIZE)
        for (v in 0 until PHASH_DCT_SIZE) {
            val cosCol = DCT_COS_TABLE[v]
            val alphaV = DCT_ALPHA[v]
            val outRow = v * PHASH_DCT_SIZE
            for (u in 0 until PHASH_DCT_SIZE) {
                var sum = 0.0
                for (y in 0 until PHASH_GRID_SIZE) {
                    sum += rowDct[y * PHASH_DCT_SIZE + u] * cosCol[y]
                }
                dct8x8[outRow + u] = sum * alphaV
            }
        }

        // 3단계: DC 성분(index 0)을 제외한 63개 AC 계수의 중앙값(Median) 산출
        val acCoeffs = DoubleArray(63)
        System.arraycopy(dct8x8, 1, acCoeffs, 0, 63)
        acCoeffs.sort()
        val median = acCoeffs[31]

        // 4단계: 각 계수를 중앙값과 비교하여 64-bit 해시 구성
        var hash = 0L
        if (dct8x8[1] > median) {
            hash = hash or 1L
        }
        for (i in 1 until 64) {
            if (dct8x8[i] > median) {
                hash = hash or (1L shl i)
            }
        }
        return hash
    }

    fun hammingDistance(hash1: Long, hash2: Long): Int {
        return java.lang.Long.bitCount(hash1 xor hash2)
    }

    /**
     * 공간 구배 기반 dHash 시퀀스와 주파수 기반 pHash 시퀀스를 결합하여
     * 두 영상의 종합 시각적 유사도(0~99%)를 계산합니다.
     */
    fun combineDHashAndPHashSimilarity(
        dHashesA: List<Long>,
        dHashesB: List<Long>,
        pHashesA: List<Long>,
        pHashesB: List<Long>
    ): Int {
        if (dHashesA.isEmpty() || dHashesB.isEmpty() || dHashesA.size != dHashesB.size) return 0
        if (pHashesA.isEmpty() || pHashesB.isEmpty()) {
            return calculateSimilarityPercent(dHashesA, dHashesB, 0L, 0L)
        }
        if (pHashesA.size != pHashesB.size || pHashesA.size != dHashesA.size) return 0

        val frameCount = dHashesA.size
        var matchedFrames = 0
        var totalWeightedBitDistance = 0f

        for (i in 0 until frameCount) {
            val dDist = hammingDistance(dHashesA[i], dHashesB[i])
            val pDist = hammingDistance(pHashesA[i], pHashesB[i])
            val weightedDist = dDist * 0.45f + pDist * 0.55f
            totalWeightedBitDistance += weightedDist

            if (weightedDist <= MAX_BIT_DISTANCE_PER_FRAME + 1f ||
                (dDist <= MAX_BIT_DISTANCE_PER_FRAME && pDist <= MAX_BIT_DISTANCE_PER_FRAME + 3) ||
                (pDist <= MAX_BIT_DISTANCE_PER_FRAME && dDist <= MAX_BIT_DISTANCE_PER_FRAME + 3)
            ) {
                matchedFrames++
            }
        }

        val frameMatchRatio = matchedFrames.toFloat() / frameCount.toFloat()
        if (frameMatchRatio < 0.8f) return 0

        val totalBits = frameCount * 64f
        val similarity = ((totalBits - totalWeightedBitDistance) / totalBits) * 100f
        return similarity.roundToInt().coerceIn(0, 99)
    }

    /**
     * 두 영상의 키프레임 dHash 시퀀스 간 시각적 유사도(0~100%)를 반환합니다.
     * 유사하지 않으면 0을 반환합니다.
     */
    fun calculateSimilarityPercent(
        hashesA: List<Long>,
        hashesB: List<Long>,
        durationMsA: Long,
        durationMsB: Long
    ): Int {
        if (hashesA.isEmpty() || hashesB.isEmpty()) return 0
        if (hashesA.size != hashesB.size) return 0

        // 재생 시간 차이가 2.5초 이상이면서 10%를 초과하면 다른 영상으로 간주
        if (durationMsA > 0 && durationMsB > 0) {
            val diffMs = abs(durationMsA - durationMsB)
            val maxAllowedDiff = maxOf(2500L, (maxOf(durationMsA, durationMsB) * 0.08f).toLong())
            if (diffMs > maxAllowedDiff) return 0
        }

        var matchedFrames = 0
        var totalBitDistance = 0

        for (i in hashesA.indices) {
            val dist = hammingDistance(hashesA[i], hashesB[i])
            totalBitDistance += dist
            if (dist <= MAX_BIT_DISTANCE_PER_FRAME) {
                matchedFrames++
            }
        }

        // 최소 80% 이상의 샘플 프레임(5개 중 4개 이상)이 임계값 이내여야 유사 영상으로 판정
        val frameMatchRatio = matchedFrames.toFloat() / hashesA.size.toFloat()
        if (frameMatchRatio < 0.8f) return 0

        val totalBits = hashesA.size * 64
        val similarity = ((totalBits - totalBitDistance).toFloat() / totalBits.toFloat()) * 100f
        return similarity.roundToInt().coerceIn(0, 99)
    }

    /**
     * 원본 방향 및 좌우 수평 반전(Mirror) 상태를 모두 고려하여
     * 최대 시각적 유사도(0~99%)와 좌우 반전 여부(Boolean)를 반환합니다.
     */
    fun calculateMirrorAwareSimilarityPercent(
        dHashesA: List<Long>,
        dHashesB: List<Long>,
        pHashesA: List<Long> = emptyList(),
        pHashesB: List<Long> = emptyList()
    ): Pair<Int, Boolean> {
        val directSim = combineDHashAndPHashSimilarity(dHashesA, dHashesB, pHashesA, pHashesB)
        val mirroredDHashesB = dHashesB.map { computeMirroredDHash(it) }
        val mirrorSim = calculateSimilarityPercent(dHashesA, mirroredDHashesB, 0L, 0L)
        return if (directSim >= mirrorSim) {
            directSim to false
        } else {
            mirrorSim to true
        }
    }

    private fun computeLuma(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}

typealias DualPerceptualSignature = VideoPerceptualHasher.DualPerceptualSignature
