package com.antigravity.duplicleaner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.antigravity.duplicleaner.domain.model.DuplicateGroup
import com.antigravity.duplicleaner.domain.model.MatchType
import com.antigravity.duplicleaner.domain.model.ScanFunnelMetrics
import com.antigravity.duplicleaner.util.FormatUtils

data class FunnelStageStat(
    val stageName: String,
    val survivingCount: Int,
    val dropPercent: Int,
    val note: String
)

object FunnelAnalyticsHelper {
    fun buildStageStats(
        metrics: ScanFunnelMetrics,
        groups: List<DuplicateGroup>
    ): List<FunnelStageStat> {
        val totalIndexed = metrics.totalIndexedFiles.coerceAtLeast(0)
        val sizeFiltered = metrics.sizeFilteredCandidates.coerceAtLeast(0)
        val partialHashed = metrics.partialHashCandidates.coerceAtLeast(0)
        val fullHashed = metrics.fullHashCandidates.coerceAtLeast(0)
        val finalDuplicateFiles = groups.sumOf { it.items.size }

        // Drop from total indexed to size filtered candidates
        val drop1 = if (totalIndexed > 0) {
            (((totalIndexed - sizeFiltered).toDouble() / totalIndexed.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else 0

        // Drop from size filtered to partial hash candidates
        val drop2 = if (sizeFiltered > 0) {
            (((sizeFiltered - partialHashed).toDouble() / sizeFiltered.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else 0

        // Drop from partial hash candidates to full hash candidates
        val drop3 = if (partialHashed > 0) {
            (((partialHashed - fullHashed).toDouble() / partialHashed.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else 0

        // Drop from full hash / intermediate to final duplicate files
        val dropFinal = if (totalIndexed > 0) {
            (((totalIndexed - finalDuplicateFiles).toDouble() / totalIndexed.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else 0

        return listOf(
            FunnelStageStat(
                stageName = "Stage 1: 전체 인덱싱",
                survivingCount = totalIndexed,
                dropPercent = 0,
                note = "MediaStore 및 파일 시스템에서 인덱싱된 전체 파일"
            ),
            FunnelStageStat(
                stageName = "Stage 2: 크기 중복 후보 (48KB 부분 해시)",
                survivingCount = sizeFiltered,
                dropPercent = drop1,
                note = "동일 파일 크기를 공유하는 후보 (고유 크기 제외 $drop1% 필터링)"
            ),
            FunnelStageStat(
                stageName = "Stage 3: 전체 바이트 검증 대상",
                survivingCount = partialHashed,
                dropPercent = drop2,
                note = "헤더/중간/푸터 48KB 일치 후보 ($drop2% 불일치 제외)"
            ),
            FunnelStageStat(
                stageName = "최종 탐지된 중복 파일",
                survivingCount = finalDuplicateFiles,
                dropPercent = dropFinal,
                note = "${groups.size}개 그룹에 속한 총 중복 파일 (전체 대비 $dropFinal% 필터링 완료)"
            )
        )
    }
}

@Composable
fun PipelineFunnelDialog(
    metrics: ScanFunnelMetrics,
    groups: List<DuplicateGroup>,
    cacheHitCount: Int,
    elapsedMs: Long,
    onDismiss: () -> Unit
) {
    val stageStats = FunnelAnalyticsHelper.buildStageStats(metrics, groups)
    val totalFiles = metrics.totalIndexedFiles.coerceAtLeast(1)

    // Match type breakdown counts
    val matchTypeCounts = groups.groupBy { it.matchType }

    val savedPercent = if (metrics.totalIndexedBytes > 0L) {
        ((metrics.savedIoBytes.toDouble() / metrics.totalIndexedBytes.toDouble()) * 100.0)
            .coerceIn(0.0, 100.0)
            .toInt()
    } else 0

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .clip(RoundedCornerShape(20.dp)),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.FilterAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "스캔 파이프라인 퍼널 리포트",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "단계별 필터링 효과 및 I/O 절감 통계",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Highlighted I/O Savings Summary Card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(14.dp)
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Savings,
                                    contentDescription = null,
                                    tint = Color(0xFF059669),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "디스크 I/O 최적화 요약",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF059669)
                                )
                            }
                            if (savedPercent > 0) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF059669).copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "${savedPercent}% 절약",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF059669),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        Text(
                            text = "전체 ${FormatUtils.formatBytes(metrics.totalIndexedBytes)} 중 ${FormatUtils.formatBytes(metrics.actualBytesRead)}만 읽음 → ${FormatUtils.formatBytes(metrics.savedIoBytes)} I/O 절감!",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Timer,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "소요 시간: ${elapsedMs}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "해시 캐시 히트: ${cacheHitCount}건",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // 2. Visual Funnel Stages
                Text(
                    text = "파이프라인 통과 단계별 현황",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        stageStats.forEachIndexed { index, stat ->
                            val progressFraction = (stat.survivingCount.toFloat() / totalFiles.toFloat())
                                .coerceIn(0.05f, 1f)

                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stat.stageName,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${stat.survivingCount}개" + if (stat.dropPercent > 0) " (-${stat.dropPercent}%)" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (index == stageStats.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                // Funnel bar
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(progressFraction)
                                            .height(8.dp)
                                            .background(
                                                when (index) {
                                                    0 -> Color(0xFF38BDF8)
                                                    1 -> Color(0xFF818CF8)
                                                    2 -> Color(0xFFFBBF24)
                                                    else -> Color(0xFF10B981)
                                                }
                                            )
                                    )
                                }

                                Text(
                                    text = stat.note,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (index < stageStats.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                // 3. Breakdown of matched groups by MatchType
                Text(
                    text = "탐지된 중복 유형 분포",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (groups.isEmpty()) {
                            Text(
                                text = "탐지된 중복 그룹이 없습니다.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            matchTypeCounts.forEach { (type, typeGroups) ->
                                val totalFilesForType = typeGroups.sumOf { it.items.size }
                                val totalReclaimable = typeGroups.sumOf { it.reclaimableBytes }
                                val label = when (type.name) {
                                    "EXACT_HASH" -> "100% 바이트 일치"
                                    "CONTAINER_PAYLOAD_MATCH" -> "MP4 스트림(mdat) 일치"
                                    "TRIMMED_CLIP_MATCH" -> "구간 편집·클립 일치"
                                    "AUDIO_FINGERPRINT_MATCH" -> "크로스 오디오 일치"
                                    "SIMILAR_VIDEO" -> "유사 미디어 (키프레임)"
                                    else -> type.badgeLabel
                                }
                                val dotColor = when (type.name) {
                                    "EXACT_HASH" -> Color(0xFF10B981)
                                    "CONTAINER_PAYLOAD_MATCH" -> Color(0xFF38BDF8)
                                    "TRIMMED_CLIP_MATCH" -> Color(0xFFF59E0B)
                                    "AUDIO_FINGERPRINT_MATCH" -> Color(0xFFEC4899)
                                    "SIMILAR_VIDEO" -> Color(0xFF8B5CF6)
                                    else -> Color(0xFF64748B)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(dotColor)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Text(
                                        text = "${typeGroups.size}그룹 (${totalFilesForType}개 / ${FormatUtils.formatBytes(totalReclaimable)})",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(text = "닫기")
            }
        }
    )
}
