package com.antigravity.duplicleaner.domain.usecase

import com.antigravity.duplicleaner.domain.model.DuplicateGroup
import com.antigravity.duplicleaner.domain.model.FileCategory
import com.antigravity.duplicleaner.domain.model.MatchType
import com.antigravity.duplicleaner.domain.model.MediaFileItem
import com.antigravity.duplicleaner.domain.model.SmartSelectionRule

/**
 * 중복 그룹 내에서 반드시 최소 1개의 원본(Keep)을 보존하고
 * 나머지 중복본(Delete)을 자동으로 선택하는 스마트 판별 엔진.
 */
object SmartSelectUseCase {

    const val PROTECTED_FOLDER_BADGE_REASON = "보호 폴더 원본 보존"
    const val FOLDER_AUTO_PROTECT_BADGE_REASON = "폴더 일괄선택 중 원본 자동 보호"
    const val EXIF_AUTHENTIC_BADGE_REASON = "EXIF 촬영 원본 보존"
    const val AUDIO_INTACT_BADGE_REASON = "오디오 포함 원본 보존"

    // (1), (2), _1, - 복사본, copy, duplicate 등 흔한 중복 다운로드 접미사 패턴
    private val DUPLICATE_NAME_REGEX = Regex(
        pattern = """(\(\d+\)|\[\d+\]|_\d+$|\s-\s*복사본|\scopy\b|\sduplicate\b|^KakaoTalk_|^received_)""",
        option = RegexOption.IGNORE_CASE
    )

    @JvmOverloads
    fun applyRuleToGroups(
        groups: List<DuplicateGroup>,
        rule: SmartSelectionRule,
        protectedFolderKeywords: Set<String> = emptySet()
    ): List<DuplicateGroup> {
        return groups.map { group ->
            applyRuleToGroup(group, rule, protectedFolderKeywords)
        }
    }

    @JvmOverloads
    fun applyRuleToGroup(
        group: DuplicateGroup,
        rule: SmartSelectionRule,
        protectedFolderKeywords: Set<String> = emptySet()
    ): DuplicateGroup {
        if (group.items.size <= 1) return group

        val normalizedKeywords = normalizeKeywords(protectedFolderKeywords)

        val hasCopyOutsideProtected = normalizedKeywords.isNotEmpty() &&
            group.items.any { !matchesNormalizedProtectedFolders(it, normalizedKeywords) }

        val keeperWithReason = selectBestKeeper(
            items = group.items,
            matchType = group.matchType,
            rule = rule,
            normalizedProtectedKeywords = normalizedKeywords
        )
        val keeperId = keeperWithReason.first.id
        val keeperUri = keeperWithReason.first.uri
        val reason = keeperWithReason.second

        val updatedItems = group.items.map { item ->
            val isInProtectedFolder = matchesNormalizedProtectedFolders(item, normalizedKeywords)
            val isPrimaryKeeper = item.id == keeperId && item.uri == keeperUri
            val isProtectedKeep = isInProtectedFolder && hasCopyOutsideProtected
            val isKeeper = isPrimaryKeeper || isProtectedKeep

            val badgeReason = when {
                isKeeper && isInProtectedFolder -> PROTECTED_FOLDER_BADGE_REASON
                isKeeper -> reason
                else -> detectDuplicateHint(item)
            }

            item.copy(
                isSelectedForDelete = !isKeeper,
                keepBadgeReason = badgeReason
            )
        }.sortedBy { it.isSelectedForDelete } // 보존할 원본이 항상 맨 위에 표시되도록 정렬

        return group.copy(items = updatedItems)
    }

    @JvmOverloads
    fun selectFolderDuplicatesSafely(
        groups: List<DuplicateGroup>,
        targetFolderKeyword: String,
        protectedFolderKeywords: Set<String> = emptySet()
    ): List<DuplicateGroup> {
        val normalizedTarget = normalizeKeyword(targetFolderKeyword)
        if (normalizedTarget.isEmpty()) return groups

        val normalizedProtected = normalizeKeywords(protectedFolderKeywords)
        return groups.map { group ->
            selectFolderDuplicatesInGroupSafely(
                group = group,
                normalizedTarget = normalizedTarget,
                normalizedProtected = normalizedProtected
            )
        }
    }

    private fun selectFolderDuplicatesInGroupSafely(
        group: DuplicateGroup,
        normalizedTarget: String,
        normalizedProtected: Set<String>
    ): DuplicateGroup {
        if (group.items.size <= 1) return group

        val hasUnprotectedItem = normalizedProtected.isEmpty() ||
            group.items.any { !matchesNormalizedProtectedFolders(it, normalizedProtected) }
        val previousKeepers = group.items.filter { !it.isSelectedForDelete }

        val tentativelyUpdated = group.items.map { item ->
            val matchesTarget = matchesNormalizedKeyword(item, normalizedTarget)
            val isProtectedFromDelete = hasUnprotectedItem &&
                matchesNormalizedProtectedFolders(item, normalizedProtected)

            if (matchesTarget && !isProtectedFromDelete) {
                item.copy(
                    isSelectedForDelete = true,
                    keepBadgeReason = if (item.isSelectedForDelete && item.keepBadgeReason != null) {
                        item.keepBadgeReason
                    } else {
                        detectDuplicateHint(item)
                    }
                )
            } else {
                item
            }
        }

        if (tentativelyUpdated.any { !it.isSelectedForDelete }) {
            return group.copy(items = tentativelyUpdated)
        }

        val keeperPool = if (previousKeepers.isNotEmpty()) previousKeepers else group.items
        val bestKeeper = selectBestKeeper(
            items = keeperPool,
            matchType = group.matchType,
            rule = SmartSelectionRule.SMART_ORIGINAL,
            normalizedProtectedKeywords = normalizedProtected
        ).first

        val keeperOriginalIndex = group.items.indexOfFirst { it === bestKeeper }
            .takeIf { it >= 0 }
            ?: group.items.indexOf(bestKeeper)

        val safeItems = tentativelyUpdated.mapIndexed { index, item ->
            if (index == keeperOriginalIndex) {
                item.copy(
                    isSelectedForDelete = false,
                    keepBadgeReason = FOLDER_AUTO_PROTECT_BADGE_REASON
                )
            } else {
                item
            }
        }

        return group.copy(items = safeItems)
    }

    fun isProtectedFolderItem(
        item: MediaFileItem,
        protectedFolderKeywords: Set<String>
    ): Boolean {
        if (protectedFolderKeywords.isEmpty()) return false
        return matchesNormalizedProtectedFolders(item, normalizeKeywords(protectedFolderKeywords))
    }

    private fun normalizeKeyword(keyword: String): String =
        keyword.trim().replace('\\', '/').lowercase()

    private fun normalizeKeywords(keywords: Set<String>): Set<String> =
        keywords.map(::normalizeKeyword).filter { it.isNotEmpty() }.toSet()

    private fun matchesNormalizedKeyword(item: MediaFileItem, normalizedKeyword: String): Boolean {
        if (normalizedKeyword.isEmpty()) return false
        val rel = item.relativePath.replace('\\', '/').lowercase()
        val abs = (item.absolutePath ?: "").replace('\\', '/').lowercase()
        return rel.contains(normalizedKeyword) || abs.contains(normalizedKeyword)
    }

    private fun matchesNormalizedProtectedFolders(
        item: MediaFileItem,
        normalizedKeywords: Set<String>
    ): Boolean {
        if (normalizedKeywords.isEmpty()) return false
        return normalizedKeywords.any { keyword -> matchesNormalizedKeyword(item, keyword) }
    }

    private fun selectBestKeeper(
        items: List<MediaFileItem>,
        matchType: MatchType,
        rule: SmartSelectionRule,
        normalizedProtectedKeywords: Set<String>
    ): Pair<MediaFileItem, String> {
        val protectedCandidates = if (normalizedProtectedKeywords.isNotEmpty()) {
            items.filter { matchesNormalizedProtectedFolders(it, normalizedProtectedKeywords) }
        } else {
            emptyList()
        }
        val candidatePool = if (protectedCandidates.isNotEmpty()) protectedCandidates else items

        // 유사 영상이거나 최고 화질 보존 규칙일 때 화질/크기/초점 선명도 순으로 고화질 우선
        if (rule == SmartSelectionRule.KEEP_HIGHEST_QUALITY || matchType == MatchType.SIMILAR_VIDEO) {
            val hasMixedAudio = candidatePool.any { it.hasAudioTrack } && candidatePool.any { !it.hasAudioTrack }

            val bestQuality = candidatePool.maxWithOrNull(
                compareBy<MediaFileItem> { it.hasAudioTrack }
                    .thenBy { it.width.toLong() * it.height.toLong() }
                    .thenBy { it.effectiveBitrateKbps }
                    .thenBy { it.sizeBytes }
                    .thenBy { it.sharpnessScore }
                    .thenBy { it.authenticityScore }
                    .thenBy { !hasDuplicateSuffix(it.displayName) }
                    .thenBy { -it.lastModifiedSeconds }
            )!!
            val badge = when {
                protectedCandidates.isNotEmpty() -> PROTECTED_FOLDER_BADGE_REASON
                hasMixedAudio && bestQuality.hasAudioTrack -> AUDIO_INTACT_BADGE_REASON
                bestQuality.authenticityScore >= 60 -> EXIF_AUTHENTIC_BADGE_REASON
                else -> "최고 화질·원본 용량 보존"
            }
            return bestQuality to badge
        }

        return when (rule) {
            SmartSelectionRule.KEEP_OLDEST -> {
                val oldest = candidatePool.minWithOrNull(
                    compareBy<MediaFileItem> {
                        if (it.lastModifiedSeconds > 0) it.lastModifiedSeconds else Long.MAX_VALUE
                    }.thenByDescending { it.authenticityScore }
                        .thenBy { hasDuplicateSuffix(it.displayName) }
                        .thenBy { it.displayName.length }
                )!!
                val badge = if (protectedCandidates.isNotEmpty()) {
                    PROTECTED_FOLDER_BADGE_REASON
                } else if (oldest.authenticityScore >= 60) {
                    EXIF_AUTHENTIC_BADGE_REASON
                } else {
                    "최초 생성 원본 보존"
                }
                oldest to badge
            }

            SmartSelectionRule.KEEP_NEWEST -> {
                val newest = candidatePool.maxWithOrNull(
                    compareBy<MediaFileItem> { it.lastModifiedSeconds }
                        .thenBy { !hasDuplicateSuffix(it.displayName) }
                )!!
                val badge = if (protectedCandidates.isNotEmpty()) {
                    PROTECTED_FOLDER_BADGE_REASON
                } else {
                    "최신 파일 보존"
                }
                newest to badge
            }

            SmartSelectionRule.SMART_ORIGINAL -> {
                val hasMixedAudio = items.any { it.category == FileCategory.VIDEO && it.hasAudioTrack } &&
                    items.any { it.category == FileCategory.VIDEO && !it.hasAudioTrack }

                val scored = items.map { item ->
                    var score = 0
                    val reasons = mutableListOf<String>()
                    val inProtectedFolder = matchesNormalizedProtectedFolders(item, normalizedProtectedKeywords)

                    if (inProtectedFolder) {
                        score += 100
                        reasons.add(PROTECTED_FOLDER_BADGE_REASON)
                    }

                    // 오디오 트랙 유무 판별 (오디오 있는 원본 +40 가점, 무음 복사본 -40 감점)
                    if (item.category == FileCategory.VIDEO) {
                        if (item.hasAudioTrack) {
                            score += 40
                            if (hasMixedAudio) {
                                reasons.add("오디오 포함")
                            }
                        } else {
                            score -= 40
                        }
                    }

                    // 1. 폴더 경로 신뢰도 평가
                    val pathLower = (item.relativePath + "/" + (item.absolutePath ?: "")).lowercase()
                    when {
                        pathLower.contains("dcim/camera") || pathLower.contains("dcim\\camera") -> {
                            score += 50
                            reasons.add("카메라 원본 폴더")
                        }
                        pathLower.contains("movies") || pathLower.contains("pictures") -> {
                            score += 25
                            reasons.add("미디어 표준 폴더")
                        }
                        pathLower.contains("kakaotalk") || pathLower.contains("telegram") || pathLower.contains("temp") -> {
                            score -= 20
                        }
                    }

                    // 2. 파일명 깔끔도 평가 ((1), 복사본 등이 없으면 가점)
                    if (!hasDuplicateSuffix(item.displayName)) {
                        score += 35
                        reasons.add("원본 파일명")
                    } else {
                        score -= 35
                    }

                    // 3. 고화질/용량 가점
                    val pixels = item.width.toLong() * item.height.toLong()
                    if (pixels > 0) {
                        score += (pixels / 500_000L).toInt().coerceAtMost(20)
                    }

                    // 4. EXIF 메타데이터 정품/촬영 원본 평가 (0..100) -> 최대 +50
                    val authBonus = (item.authenticityScore / 2).coerceIn(0, 50)
                    score += authBonus
                    if (item.authenticityScore >= 60) {
                        reasons.add("EXIF 원본")
                    }

                    Triple(item, score, reasons)
                }

                // 점수 높은 순 -> 유효 비트레이트 높은 순 -> 초점 선명도 높은 순 -> 메타데이터 신뢰도 높은 순 -> 수정 시각 오래된 순 -> 파일명 짧은 순
                val winner = scored.maxWithOrNull(
                    compareBy<Triple<MediaFileItem, Int, List<String>>> { it.second }
                        .thenBy { it.first.effectiveBitrateKbps }
                        .thenBy { it.first.sharpnessScore }
                        .thenBy { it.first.authenticityScore }
                        .thenBy {
                            val mod = it.first.lastModifiedSeconds
                            if (mod > 0) -mod else Long.MIN_VALUE
                        }
                        .thenBy { -it.first.displayName.length }
                )!!

                val isAudioDecidingFactor = hasMixedAudio && winner.first.hasAudioTrack && run {
                    val maxSilentBaseScore = scored
                        .filter { it.first.category == FileCategory.VIDEO && !it.first.hasAudioTrack }
                        .maxOfOrNull { it.second + 40 } ?: Int.MIN_VALUE
                    val winnerBaseScore = winner.second - 40
                    maxSilentBaseScore >= winnerBaseScore
                }

                val winnerInProtected = matchesNormalizedProtectedFolders(
                    winner.first,
                    normalizedProtectedKeywords
                )
                val summaryReason = when {
                    winnerInProtected -> PROTECTED_FOLDER_BADGE_REASON
                    isAudioDecidingFactor -> AUDIO_INTACT_BADGE_REASON
                    winner.first.authenticityScore >= 60 -> EXIF_AUTHENTIC_BADGE_REASON
                    hasMixedAudio && winner.first.hasAudioTrack && winner.third.contains("오디오 포함") -> {
                        val other = winner.third.filter { it != "오디오 포함" }
                        if (other.isNotEmpty()) {
                            "오디오 포함 · ${other.first()} 보존"
                        } else {
                            AUDIO_INTACT_BADGE_REASON
                        }
                    }
                    winner.third.isNotEmpty() -> winner.third.take(2).joinToString(" · ") + " 보존"
                    else -> "최초 원본 보존"
                }
                winner.first to summaryReason
            }

            SmartSelectionRule.KEEP_HIGHEST_QUALITY -> {
                // Handled above, fallback
                items.first() to "원본 보존"
            }
        }
    }

    fun hasDuplicateSuffix(fileName: String): Boolean {
        val nameWithoutExt = fileName.substringBeforeLast('.')
        return DUPLICATE_NAME_REGEX.containsMatchIn(nameWithoutExt)
    }

    private fun detectDuplicateHint(item: MediaFileItem): String? {
        val nameWithoutExt = item.displayName.substringBeforeLast('.')
        val pathLower = item.relativePath.lowercase()
        return when {
            Regex("""\(\d+\)|\[\d+\]""").containsMatchIn(nameWithoutExt) -> "중복 다운로드 번호 감지"
            nameWithoutExt.contains("복사본") || nameWithoutExt.lowercase().contains("copy") -> "복사본 파일명 감지"
            pathLower.contains("kakaotalk") || pathLower.contains("telegram") -> "메신저 중복 저장본"
            pathLower.contains("download") -> "다운로드 폴더 중복본"
            else -> "중복 사본"
        }
    }
}
