package com.antigravity.duplicleaner.data.hash

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 2D 이산 라플라시안(Discrete Laplacian) 필터 기반 영상/사진 선명도(Sharpness/Visual Quality) 채점기.
 *
 * 저품질/블러된 사진·영상 프레임(평탄하거나 흐릿한 에지)과 고화질 원본(선명한 에지)을 감별하여
 * 스마트 중복 선별(`KEEP_HIGHEST_QUALITY`) 시 더 선명한 원본을 보존할 수 있도록 돕습니다.
 */
object VisualQualityScorer {

    /**
     * 그레이스케일 픽셀 그리드에 2D 이산 라플라시안(Discrete Laplacian) 필터를 적용하여
     * 에지의 급격한 밝기 변화량의 평균(Mean) 및 제곱평균제곱근(RMS)/분산(Variance)을 산출하고,
     * 0(완전 평탄/흐림)부터 100(고대비 선명한 에지) 사이의 정수 점수로 변환합니다.
     *
     * @param grid 그레이스케일(0..255) 픽셀 배열 (크기 >= width * height)
     * @param width 그리드 너비 (>= 3)
     * @param height 그리드 높이 (>= 3)
     */
    fun computeSharpnessScoreFromGrayscaleGrid(grid: IntArray, width: Int, height: Int): Int {
        if (width < 3 || height < 3 || grid.size < width * height) return 0

        val count = (width - 2) * (height - 2)
        if (count <= 0) return 0

        var sum = 0.0
        var sumSq = 0.0

        for (y in 1 until height - 1) {
            val rowOffset = y * width
            for (x in 1 until width - 1) {
                val center = grid[rowOffset + x]
                val left = grid[rowOffset + x - 1]
                val right = grid[rowOffset + x + 1]
                val up = grid[rowOffset - width + x]
                val down = grid[rowOffset + width + x]
                val laplacian = abs(4 * center - left - right - up - down).toDouble()
                sum += laplacian
                sumSq += laplacian * laplacian
            }
        }

        val mean = sum / count
        val variance = maxOf(0.0, (sumSq / count) - (mean * mean))
        val rms = sqrt(sumSq / count)

        // RMS(제곱평균제곱근)는 mean^2 + variance를 통합 반영합니다.
        // 평탄하거나 부드러운 블러 영상(rms <= 20)은 25점 미만으로,
        // 고대비 선명한 에지 영상(rms >= 60)은 70점 이상(최대 100)으로 스케일링합니다.
        val rawScore = (rms * 1.2).coerceIn(0.0, 100.0)
        return rawScore.roundToInt()
    }

    /**
     * 입력 비트맵을 32x32 크기로 다운샘플링하고 Rec.601 표준 루마(Luma = (299*R + 587*G + 114*B) / 1000)를
     * 추출하여 0..100 선명도 점수를 계산합니다.
     */
    fun computeBitmapSharpnessScore(bitmap: Bitmap): Int {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return 0

        val scaled = if (bitmap.width == 32 && bitmap.height == 32) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        }

        val pixels = IntArray(32 * 32)
        scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)

        val grid = IntArray(32 * 32)
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            grid[i] = (r * 299 + g * 587 + b * 114) / 1000
        }

        if (scaled != bitmap) {
            scaled.recycle()
        }

        return computeSharpnessScoreFromGrayscaleGrid(grid, 32, 32)
    }
}
