package com.antigravity.duplicleaner.data.hash

import kotlin.math.roundToInt

/**
 * 시계열 키프레임 부분 수열 정렬(Subsequence Alignment) 결과.
 */
data class SubsequenceMatchResult(
    val similarityPercent: Int,
    val matchedFrames: Int,
    val totalComparedFrames: Int,
    val isSubsequenceTrimmed: Boolean,
    val bestOffset: Int
)

/**
 * 앞뒤 인트로·아웃트로가 잘린 클립(Trimmed Video)이나 재생 길이가 다른 동일 출처 영상을 탐지하기 위해
 * 짧은 키프레임 시퀀스를 긴 시퀀스 위에서 슬라이딩(및 1-프레임 스킵 허용) 정렬하는 시계열 매칭 엔진.
 */
object TemporalSequenceAligner {

    private const val MIN_MATCH_FRAME_RATIO = 0.75f

    /**
     * 두 영상의 dHash (및 선택적 pHash) 시퀀스를 슬라이딩 윈도우와 1-프레임 스킵 정렬로 비교하여
     * 최적 부분 수열 일치도(SubsequenceMatchResult)를 반환합니다.
     */
    fun alignSubsequenceSimilarity(
        dHashesA: List<Long>,
        dHashesB: List<Long>,
        pHashesA: List<Long> = emptyList(),
        pHashesB: List<Long> = emptyList(),
        maxBitDistancePerFrame: Int = 12
    ): SubsequenceMatchResult {
        if (dHashesA.isEmpty() || dHashesB.isEmpty()) {
            return SubsequenceMatchResult(
                similarityPercent = 0,
                matchedFrames = 0,
                totalComparedFrames = 0,
                isSubsequenceTrimmed = false,
                bestOffset = 0
            )
        }

        val aIsShorterOrEqual = dHashesA.size <= dHashesB.size
        val shorterD = if (aIsShorterOrEqual) dHashesA else dHashesB
        val longerD = if (aIsShorterOrEqual) dHashesB else dHashesA

        val hasPHashes = pHashesA.size == dHashesA.size &&
            pHashesB.size == dHashesB.size &&
            pHashesA.isNotEmpty()
        val shorterP = if (!hasPHashes) emptyList() else if (aIsShorterOrEqual) pHashesA else pHashesB
        val longerP = if (!hasPHashes) emptyList() else if (aIsShorterOrEqual) pHashesB else pHashesA

        fun evaluateFramePair(sIdx: Int, lIdx: Int): Pair<Float, Boolean> {
            val dDist = VideoPerceptualHasher.hammingDistance(shorterD[sIdx], longerD[lIdx])
            return if (hasPHashes) {
                val pDist = VideoPerceptualHasher.hammingDistance(shorterP[sIdx], longerP[lIdx])
                val weighted = 0.45f * dDist + 0.55f * pDist
                val matched = weighted <= maxBitDistancePerFrame + 1f ||
                    (dDist <= maxBitDistancePerFrame && pDist <= maxBitDistancePerFrame + 3) ||
                    (pDist <= maxBitDistancePerFrame && dDist <= maxBitDistancePerFrame + 3)
                weighted to matched
            } else {
                val weighted = dDist.toFloat()
                val matched = dDist <= maxBitDistancePerFrame
                weighted to matched
            }
        }

        // 시퀀스 길이가 3 미만인 경우 안전 폴백(Graceful Fallback)
        if (shorterD.size < 3 || longerD.size < 3) {
            val comparedCount = shorterD.size
            var matchedFrames = 0
            var totalWeightedDist = 0f
            for (i in 0 until comparedCount) {
                val (dist, matched) = evaluateFramePair(i, i)
                totalWeightedDist += dist
                if (matched) matchedFrames++
            }
            val ratio = matchedFrames.toFloat() / comparedCount.toFloat()
            val similarity = if (ratio >= MIN_MATCH_FRAME_RATIO) {
                val totalBits = comparedCount * 64f
                (((totalBits - totalWeightedDist) / totalBits) * 100f).roundToInt().coerceIn(0, 99)
            } else {
                0
            }
            return SubsequenceMatchResult(
                similarityPercent = similarity,
                matchedFrames = matchedFrames,
                totalComparedFrames = comparedCount,
                isSubsequenceTrimmed = (dHashesA.size != dHashesB.size) && similarity > 0,
                bestOffset = 0
            )
        }

        val minOverlap = minOf(4, shorterD.size)

        var bestSimilarity = 0
        var bestRawScore = -1f
        var bestMatchedFrames = 0
        var bestComparedFrames = 0
        var bestOffset = 0

        fun updateBestIfBetter(
            comparedCount: Int,
            matchedFrames: Int,
            totalWeightedDist: Float,
            effectiveOffset: Int
        ) {
            if (comparedCount < minOverlap) return
            val matchRatio = matchedFrames.toFloat() / comparedCount.toFloat()
            if (matchRatio < MIN_MATCH_FRAME_RATIO) return

            val totalBits = comparedCount * 64f
            val rawScore = ((totalBits - totalWeightedDist) / totalBits) * 100f
            val simPercent = rawScore.roundToInt().coerceIn(0, 99)
            if (simPercent <= 0) return

            val isBetter = when {
                simPercent > bestSimilarity -> true
                simPercent < bestSimilarity -> false
                matchedFrames > bestMatchedFrames -> true
                matchedFrames < bestMatchedFrames -> false
                rawScore > bestRawScore + 0.01f -> true
                rawScore < bestRawScore - 0.01f -> false
                bestOffset != 0 && effectiveOffset == 0 -> true
                else -> false
            }

            if (isBetter) {
                bestSimilarity = simPercent
                bestRawScore = rawScore
                bestMatchedFrames = matchedFrames
                bestComparedFrames = comparedCount
                bestOffset = effectiveOffset
            }
        }

        // 1. 연속 슬라이딩 윈도우 정렬 (양방향 부분 겹침 오프셋 모두 탐색)
        val minOffset = -(shorterD.size - minOverlap)
        val maxOffset = longerD.size - minOverlap
        for (offset in minOffset..maxOffset) {
            val sStart = maxOf(0, -offset)
            val lStart = maxOf(0, offset)
            val overlapCount = minOf(shorterD.size - sStart, longerD.size - lStart)
            if (overlapCount < minOverlap) continue

            var matched = 0
            var totalDist = 0f
            for (k in 0 until overlapCount) {
                val (dist, isMatch) = evaluateFramePair(sStart + k, lStart + k)
                totalDist += dist
                if (isMatch) matched++
            }
            updateBestIfBetter(
                comparedCount = overlapCount,
                matchedFrames = matched,
                totalWeightedDist = totalDist,
                effectiveOffset = offset
            )
        }

        // 2. 1-프레임 스킵(Step Skip) 허용 정렬 (배속 변경·불균일 키프레임 샘플링 대응)
        for (offset in 0..(longerD.size - minOverlap)) {
            for (skipAt in 1 until shorterD.size) {
                var compared = 0
                var matched = 0
                var totalDist = 0f
                for (sIdx in shorterD.indices) {
                    val lIdx = offset + sIdx + if (sIdx >= skipAt) 1 else 0
                    if (lIdx >= longerD.size) break
                    val (dist, isMatch) = evaluateFramePair(sIdx, lIdx)
                    compared++
                    totalDist += dist
                    if (isMatch) matched++
                }
                val effectiveOffset = if (offset == 0) skipAt else offset
                updateBestIfBetter(
                    comparedCount = compared,
                    matchedFrames = matched,
                    totalWeightedDist = totalDist,
                    effectiveOffset = effectiveOffset
                )
            }
        }

        val isTrimmed = (dHashesA.size != dHashesB.size || bestOffset != 0) && bestSimilarity > 0

        return SubsequenceMatchResult(
            similarityPercent = bestSimilarity,
            matchedFrames = bestMatchedFrames,
            totalComparedFrames = bestComparedFrames,
            isSubsequenceTrimmed = isTrimmed,
            bestOffset = bestOffset
        )
    }
}
