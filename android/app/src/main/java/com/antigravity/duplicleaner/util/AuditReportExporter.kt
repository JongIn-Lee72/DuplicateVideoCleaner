package com.antigravity.duplicleaner.util

import com.antigravity.duplicleaner.domain.model.DuplicateGroup
import com.antigravity.duplicleaner.domain.model.MatchType
import com.antigravity.duplicleaner.domain.model.MediaFileItem
import com.antigravity.duplicleaner.domain.model.ScanFunnelMetrics

/**
 * 스캔 결과, Stage 1~4 퍼널 I/O 절감 지표, 대용량 파일 목록을
 * 공유 및 클립보드 복사용 한국어 Markdown 감사 리포트로 직렬화한다.
 */
object AuditReportExporter {

    private const val MAX_DETAILED_GROUPS = 25
    private const val MAX_TOP_LARGE_FILES = 5

    private val ORDERED_MATCH_TYPES = listOf(
        MatchType.EXACT_HASH,
        MatchType.CONTAINER_PAYLOAD_MATCH,
        MatchType.TRIMMED_CLIP_MATCH,
        MatchType.SIMILAR_VIDEO
    )

    fun buildMarkdownAuditReport(
        groups: List<DuplicateGroup>,
        largestFiles: List<MediaFileItem>,
        funnelMetrics: ScanFunnelMetrics,
        cumulativeFreedBytes: Long
    ): String {
        val totalDuplicateFiles = groups.sumOf { it.items.size }
        val totalSelectedCount = groups.sumOf { it.selectedCount }
        val totalReclaimableBytes = groups.sumOf { it.reclaimableBytes }
        val savedPercent = if (funnelMetrics.totalIndexedBytes > 0L) {
            ((funnelMetrics.savedIoBytes.toDouble() / funnelMetrics.totalIndexedBytes.toDouble()) * 100.0)
                .coerceIn(0.0, 100.0)
                .toInt()
        } else {
            0
        }

        return buildString {
            appendLine("# DuplicateVideoCleaner 스캔·정리 감사 리포트")
            appendLine("- 생성 시각: ${FormatUtils.formatDate(System.currentTimeMillis() / 1000L)}")
            appendLine()

            appendLine("## 1. 요약 지표")
            appendLine("- 탐지된 중복 그룹: **${groups.size}개** (총 ${totalDuplicateFiles}개 파일)")
            appendLine(
                "- 정리 선택된 중복 파일: **${totalSelectedCount}개** · 정리 가능 용량: **${
                    FormatUtils.formatBytes(totalReclaimableBytes)
                }**"
            )
            appendLine("- 누적 정리(확보) 용량: **${FormatUtils.formatBytes(cumulativeFreedBytes)}**")
            appendLine(
                "- Stage 1~4 퍼널 스캔: 전체 ${funnelMetrics.totalIndexedFiles}개 (${
                    FormatUtils.formatBytes(funnelMetrics.totalIndexedBytes)
                }) → 크기 후보 ${funnelMetrics.sizeFilteredCandidates}개 → 부분해시 ${funnelMetrics.partialHashCandidates}개 → 전체해시 ${funnelMetrics.fullHashCandidates}개"
            )
            appendLine(
                "- 실제 디스크 읽기: ${FormatUtils.formatBytes(funnelMetrics.actualBytesRead)} / 절약한 디스크 I/O (savedIoBytes): **${
                    FormatUtils.formatBytes(funnelMetrics.savedIoBytes)
                }** (${funnelMetrics.savedIoBytes} B${if (savedPercent > 0) ", ${savedPercent}% 절감" else ""})"
            )
            appendLine()

            appendLine("## 2. 매칭 유형별 분석")
            ORDERED_MATCH_TYPES.forEach { matchType ->
                val matchingGroups = groups.filter { it.matchType == matchType }
                val fileCount = matchingGroups.sumOf { it.items.size }
                val reclaimable = matchingGroups.sumOf { it.reclaimableBytes }
                appendLine(
                    "- **${matchType.badgeLabel}** (`${matchType.name}`): ${matchingGroups.size}그룹 · ${fileCount}개 파일 · 확보 가능 ${
                        FormatUtils.formatBytes(reclaimable)
                    }"
                )
            }
            appendLine()

            appendLine("## 3. 중복 그룹 상세 (최대 ${MAX_DETAILED_GROUPS}개)")
            if (groups.isEmpty()) {
                appendLine("- 탐지된 중복 그룹이 없습니다.")
            } else {
                val displayedGroups = groups.take(MAX_DETAILED_GROUPS)
                displayedGroups.forEachIndexed { index, group ->
                    appendGroupDetail(index + 1, group)
                }
                if (groups.size > MAX_DETAILED_GROUPS) {
                    appendLine("_외 ${groups.size - MAX_DETAILED_GROUPS}개 그룹이 생략되었습니다._")
                }
            }
            appendLine()

            appendLine("## 4. 대용량 단독 파일 Top ${MAX_TOP_LARGE_FILES}")
            val topLargest = largestFiles
                .sortedByDescending { it.sizeBytes }
                .take(MAX_TOP_LARGE_FILES)
            if (topLargest.isEmpty()) {
                appendLine("- 대용량 파일 목록이 없습니다.")
            } else {
                topLargest.forEachIndexed { index, item ->
                    val metadataTags = listOfNotNull(
                        item.resolutionLabel,
                        item.formattedDuration
                    ).joinToString(" · ")
                    val metadataSuffix = if (metadataTags.isNotEmpty()) " [$metadataTags]" else ""
                    val selectedSuffix = if (item.isSelectedForDelete) " (삭제 선택됨)" else ""
                    appendLine(
                        "${index + 1}. **${item.displayName}** — ${
                            FormatUtils.formatBytes(item.sizeBytes)
                        }$metadataSuffix · ${formatItemPath(item)}$selectedSuffix"
                    )
                }
            }
        }.trimEnd()
    }

    private fun StringBuilder.appendGroupDetail(rank: Int, group: DuplicateGroup) {
        appendLine(
            "### $rank. [${group.matchType.badgeLabel}] 유사도 ${group.similarityPercent}% · 시그니처 `${group.signature}`"
        )
        appendLine(
            "- 카테고리: ${group.category.label} · 총 ${group.items.size}개 파일 (${
                FormatUtils.formatBytes(group.totalBytes)
            })"
        )

        val keptItems = group.items.filter { !it.isSelectedForDelete }
        val selectedDuplicates = group.items.filter { it.isSelectedForDelete }

        if (keptItems.isEmpty()) {
            appendLine("- 보존 원본: 없음 (그룹 내 모든 파일이 삭제 선택됨)")
        } else {
            keptItems.forEach { kept ->
                val reasonSuffix = kept.keepBadgeReason?.takeIf { it.isNotBlank() }
                    ?.let { " [$it]" }
                    .orEmpty()
                appendLine(
                    "- 보존 원본: **${kept.displayName}** (${
                        FormatUtils.formatBytes(kept.sizeBytes)
                    })$reasonSuffix · ${formatItemPath(kept)}"
                )
            }
        }

        if (selectedDuplicates.isEmpty()) {
            appendLine("- 삭제 선택 중복본: 없음")
        } else {
            appendLine(
                "- 삭제 선택 중복본 (${selectedDuplicates.size}개 · ${
                    FormatUtils.formatBytes(group.reclaimableBytes)
                }):"
            )
            selectedDuplicates.forEach { dup ->
                appendLine(
                    "  - ${dup.displayName} (${FormatUtils.formatBytes(dup.sizeBytes)}) · ${
                        formatItemPath(dup)
                    }"
                )
            }
        }
    }

    private fun formatItemPath(item: MediaFileItem): String {
        val absolute = item.absolutePath?.takeIf { it.isNotBlank() }
        if (absolute != null) return absolute
        val combined = listOf(item.relativePath.trim('/'), item.displayName)
            .filter { it.isNotBlank() }
            .joinToString("/")
        return combined.ifBlank { "(경로 없음)" }
    }
}
