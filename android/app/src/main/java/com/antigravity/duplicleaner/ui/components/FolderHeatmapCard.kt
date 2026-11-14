package com.antigravity.duplicleaner.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.antigravity.duplicleaner.domain.model.DuplicateGroup
import com.antigravity.duplicleaner.util.FormatUtils

data class FolderDuplicateStat(
    val folderPath: String,
    val duplicateFileCount: Int,
    val reclaimableBytes: Long,
    val totalBytes: Long,
    val isProtected: Boolean
)

@Composable
fun FolderHeatmapCard(
    groups: List<DuplicateGroup>,
    protectedFolders: Set<String>,
    onSelectFolderDuplicates: (String) -> Unit,
    onToggleProtectFolder: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (groups.isEmpty()) return

    val folderStats: List<FolderDuplicateStat> = remember(groups, protectedFolders) {
        // Map from folder path to accumulators
        class FolderAccumulator(
            var duplicateCount: Int = 0,
            var reclaimableBytes: Long = 0L,
            var totalBytes: Long = 0L
        )

        val folderMap = mutableMapOf<String, FolderAccumulator>()

        for (group in groups) {
            for (item in group.items) {
                val folder = item.relativePath.trim().trim('/', '\\').ifEmpty { "내장 스토리지" }
                val acc = folderMap.getOrPut(folder) { FolderAccumulator() }
                acc.duplicateCount += 1
                acc.totalBytes += item.sizeBytes
                if (item.isSelectedForDelete) {
                    acc.reclaimableBytes += item.sizeBytes
                }
            }
        }

        folderMap.map { (folder, acc) ->
            FolderDuplicateStat(
                folderPath = folder,
                duplicateFileCount = acc.duplicateCount,
                reclaimableBytes = acc.reclaimableBytes,
                totalBytes = acc.totalBytes,
                isProtected = protectedFolders.contains(folder)
            )
        }
            .sortedByDescending { if (it.reclaimableBytes > 0L) it.reclaimableBytes else it.totalBytes }
            .take(5)
    }

    if (folderStats.isEmpty()) return

    var isExpanded by remember { mutableStateOf(true) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Whatshot,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "폴더별 중복 용량 히트맵 Top ${folderStats.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "접기" else "펼치기"
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val maxReclaimable = remember(folderStats) {
                        folderStats.maxOfOrNull { it.reclaimableBytes }?.coerceAtLeast(1L) ?: 1L
                    }

                    folderStats.forEachIndexed { index, stat ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                                thickness = 0.8.dp
                            )
                        }

                        FolderHeatmapRow(
                            stat = stat,
                            maxReclaimableBytes = maxReclaimable,
                            onSelectFolderDuplicates = onSelectFolderDuplicates,
                            onToggleProtectFolder = onToggleProtectFolder
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderHeatmapRow(
    stat: FolderDuplicateStat,
    maxReclaimableBytes: Long,
    onSelectFolderDuplicates: (String) -> Unit,
    onToggleProtectFolder: (String) -> Unit
) {
    // Heat color: green when protected; otherwise gradient feel (red for high ratio/volume, orange for medium, warm yellow/amber for low)
    val heatColor = when {
        stat.isProtected -> Color(0xFF4CAF50) // Green
        stat.reclaimableBytes <= 0L -> MaterialTheme.colorScheme.outline
        else -> {
            val ratio = (stat.reclaimableBytes.toFloat() / maxReclaimableBytes.toFloat()).coerceIn(0f, 1f)
            when {
                ratio >= 0.65f -> Color(0xFFE53935) // Red
                ratio >= 0.30f -> Color(0xFFFF9800) // Orange
                else -> Color(0xFFFFC107)           // Amber
            }
        }
    }

    val progressFraction = if (maxReclaimableBytes > 0L && stat.reclaimableBytes > 0L) {
        (stat.reclaimableBytes.toFloat() / maxReclaimableBytes.toFloat()).coerceIn(0.08f, 1f)
    } else if (stat.totalBytes > 0L) {
        0.05f
    } else {
        0f
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Folder Name and Stats Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = heatColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stat.folderPath,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = FormatUtils.formatBytes(stat.reclaimableBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (stat.isProtected) MaterialTheme.colorScheme.onSurfaceVariant else heatColor
                )
                Text(
                    text = "중복 ${stat.duplicateFileCount}개 (총 ${FormatUtils.formatBytes(stat.totalBytes)})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Horizontal Heat Progress Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = progressFraction)
                    .clip(RoundedCornerShape(4.dp))
                    .background(heatColor)
            )
        }

        // Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistChip(
                onClick = { onToggleProtectFolder(stat.folderPath) },
                label = {
                    Text(
                        text = if (stat.isProtected) "보호됨" else "보호",
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (stat.isProtected) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (stat.isProtected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (stat.isProtected) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color.Transparent,
                    labelColor = if (stat.isProtected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                border = AssistChipDefaults.assistChipBorder(
                    enabled = true,
                    borderColor = if (stat.isProtected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                modifier = Modifier.height(32.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            TextButton(
                onClick = { onSelectFolderDuplicates(stat.folderPath) },
                enabled = !stat.isProtected && stat.duplicateFileCount > 0,
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    text = "사본 선택",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
