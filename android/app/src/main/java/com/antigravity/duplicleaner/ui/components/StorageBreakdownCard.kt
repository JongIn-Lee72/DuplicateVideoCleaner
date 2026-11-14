package com.antigravity.duplicleaner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.antigravity.duplicleaner.domain.model.DuplicateGroup
import com.antigravity.duplicleaner.domain.model.FileCategory
import com.antigravity.duplicleaner.domain.model.MatchType
import com.antigravity.duplicleaner.domain.model.ScanFunnelMetrics
import com.antigravity.duplicleaner.util.FormatUtils

private data class CategorySegment(
    val category: FileCategory,
    val color: Color,
    val reclaimableBytes: Long,
    val totalBytes: Long,
    val groupCount: Int
)

@Composable
fun StorageBreakdownCard(
    groups: List<DuplicateGroup>,
    cachedHashCount: Int,
    onOpenSettingsSheet: () -> Unit,
    modifier: Modifier = Modifier,
    funnelMetrics: ScanFunnelMetrics = ScanFunnelMetrics(),
    onOpenFunnelDialog: () -> Unit = {}
) {
    val segments = remember(groups) {
        val colorMap = mapOf(
            FileCategory.VIDEO to Color(0xFF38BDF8),
            FileCategory.IMAGE to Color(0xFF818CF8),
            FileCategory.AUDIO to Color(0xFF34D399),
            FileCategory.DOCUMENT_ARCHIVE to Color(0xFFFBBF24),
            FileCategory.OTHER to Color(0xFFF472B6)
        )
        FileCategory.entries.map { category ->
            val catGroups = groups.filter { it.category == category }
            CategorySegment(
                category = category,
                color = colorMap[category] ?: Color(0xFF94A3B8),
                reclaimableBytes = catGroups.sumOf { it.reclaimableBytes },
                totalBytes = catGroups.sumOf { it.totalBytes },
                groupCount = catGroups.size
            )
        }
    }

    val totalReclaimable = remember(segments) { segments.sumOf { it.reclaimableBytes } }
    val exactMatchCount = remember(groups) { groups.count { it.matchType == MatchType.EXACT_HASH } }
    val payloadMatchCount = remember(groups) { groups.count { it.matchType == MatchType.CONTAINER_PAYLOAD_MATCH } }
    val trimmedClipCount = remember(groups) { groups.count { it.matchType == MatchType.TRIMMED_CLIP_MATCH } }
    val similarVideoCount = remember(groups) { groups.count { it.matchType == MatchType.SIMILAR_VIDEO } }
    val audioFpMatchCount = remember(groups) { groups.count { it.matchType.name == "AUDIO_FINGERPRINT_MATCH" } }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PieChart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "카테고리별 중복 분포 · 고급 설정",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "해시 캐시 ${cachedHashCount}건",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Segmented horizontal bar
            val nonZeroSegments = segments.filter { it.reclaimableBytes > 0L }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (totalReclaimable > 0L && nonZeroSegments.isNotEmpty()) {
                    Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                        nonZeroSegments.forEach { seg ->
                            val fraction = (seg.reclaimableBytes.toFloat() / totalReclaimable.toFloat())
                                .coerceAtLeast(0.04f)
                            Box(
                                modifier = Modifier
                                    .weight(fraction)
                                    .fillMaxHeight()
                                    .background(seg.color)
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.25f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Category chips row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                segments.forEach { seg ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.border(
                            width = 1.dp,
                            color = if (seg.reclaimableBytes > 0L) seg.color.copy(alpha = 0.6f) else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(seg.color)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = seg.category.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = FormatUtils.formatBytes(seg.reclaimableBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (funnelMetrics.totalIndexedFiles > 0) {
                val savedPercent = if (funnelMetrics.totalIndexedBytes > 0L) {
                    ((funnelMetrics.savedIoBytes.toDouble() / funnelMetrics.totalIndexedBytes.toDouble()) * 100.0)
                        .coerceIn(0.0, 100.0)
                        .toInt()
                } else {
                    0
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenFunnelDialog() }
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(12.dp)
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "다단계 해시 I/O 절감 리포트",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (savedPercent > 0) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFF059669).copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "${savedPercent}% I/O 절감",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF059669),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                TextButton(
                                    onClick = onOpenFunnelDialog,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        text = "퍼널 상세 ▶",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        Text(
                            text = "전체 인덱싱: ${funnelMetrics.totalIndexedFiles}개 (${FormatUtils.formatBytes(funnelMetrics.totalIndexedBytes)})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "실제 디스크 읽기: ${FormatUtils.formatBytes(funnelMetrics.actualBytesRead)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "절약한 디스크 I/O: ${FormatUtils.formatBytes(funnelMetrics.savedIoBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF059669)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(modifier = Modifier.height(10.dp))

            // Match type breakdown & Settings Sheet Trigger
            val matchTypeSummary = buildString {
                append("바이트 일치 ${exactMatchCount}그룹 · MP4 스트림 일치 ${payloadMatchCount}그룹 · 구간 클립 ${trimmedClipCount}그룹")
                if (audioFpMatchCount > 0) {
                    append(" · 오디오 지문 ${audioFpMatchCount}그룹")
                }
                append(" · 유사 영상 ${similarVideoCount}그룹")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = matchTypeSummary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (totalReclaimable > 0L) {
                            "선택된 정리 가능 용량 ${FormatUtils.formatBytes(totalReclaimable)}"
                        } else {
                            "보호 폴더 · 제외 키워드 · 최소 파일 크기를 설정할 수 있습니다"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = onOpenSettingsSheet,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "보호 폴더 · 최소 크기 · 정리 기록",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
