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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.duplicleaner.data.hash.CorruptedFileReport
import com.antigravity.duplicleaner.domain.model.DuplicateGroup
import com.antigravity.duplicleaner.domain.model.MediaFileItem
import com.antigravity.duplicleaner.util.FormatUtils

data class CleanupImpactSummary(
    val selectedDeleteCount: Int,
    val reclaimableBytes: Long,
    val preservedOriginalCount: Int,
    val isPermanentDelete: Boolean
)

object CleanupSafetyHelper {

    fun buildImpactSummary(
        groups: List<DuplicateGroup>,
        selectedLargeFiles: List<MediaFileItem>,
        selectedCorruptedFiles: List<CorruptedFileReport>,
        isPermanentDelete: Boolean,
        includeGroups: Boolean = true,
        includeLargeFiles: Boolean = false,
        includeCorruptedFiles: Boolean = false
    ): CleanupImpactSummary {
        var deleteCount = 0
        var bytes = 0L
        var preservedCount = 0

        if (includeGroups) {
            for (group in groups) {
                for (item in group.items) {
                    if (item.isSelectedForDelete) {
                        deleteCount++
                        bytes += item.sizeBytes
                    } else {
                        preservedCount++
                    }
                }
            }
        }

        if (includeLargeFiles) {
            deleteCount += selectedLargeFiles.size
            bytes += selectedLargeFiles.sumOf { it.sizeBytes }
        }

        if (includeCorruptedFiles) {
            deleteCount += selectedCorruptedFiles.size
            bytes += selectedCorruptedFiles.sumOf { it.item.sizeBytes }
        }

        return CleanupImpactSummary(
            selectedDeleteCount = deleteCount,
            reclaimableBytes = bytes,
            preservedOriginalCount = preservedCount,
            isPermanentDelete = isPermanentDelete
        )
    }
}

@Composable
fun CleanupConfirmDialog(
    summary: CleanupImpactSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isPermanent = summary.isPermanentDelete
    val titleText = if (isPermanent) "영구 삭제 최종 확인" else "휴지통·안전 보관함 이동 확인"
    val titleColor = if (isPermanent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isPermanent) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPermanent) Icons.Default.DeleteForever else Icons.Default.Restore,
                    contentDescription = null,
                    tint = if (isPermanent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = titleColor
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Impact Summary Box
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "정리 대상",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${summary.selectedDeleteCount}개 (${FormatUtils.formatBytes(summary.reclaimableBytes)})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isPermanent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }

                        if (summary.preservedOriginalCount > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                thickness = 0.5.dp
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "보호되는 원본",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "${summary.preservedOriginalCount}개",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // Warning / Assurance Note
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isPermanent) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (isPermanent) Icons.Default.WarningAmber else Icons.Default.Info,
                            contentDescription = null,
                            tint = if (isPermanent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .size(20.dp)
                                .padding(top = 2.dp)
                        )
                        Text(
                            text = if (isPermanent) {
                                "휴지통을 거치지 않고 즉시 영구 삭제되며 복구할 수 없습니다."
                            } else {
                                "OS 휴지통(30일) 또는 인앱 안전 보관함으로 이동되어 언제든 복원할 수 있습니다."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 18.sp,
                            color = if (isPermanent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPermanent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = if (isPermanent) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = if (isPermanent) "영구 삭제" else "정리 실행",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

@Composable
fun PrivacyAndSafetyDialog(
    cachedHashCount: Int,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(30.dp)
                )
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "개인정보 보호 및 3중 안전 설계",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "100% 온디바이스 오프라인 아키텍처",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1: Zero-Network Architecture
                SafetyFeatureItem(
                    icon = Icons.Default.Lock,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "Zero-Network 오프라인 원칙",
                    description = "AndroidManifest.xml에 INTERNET 권한이 전혀 선언되지 않아 개인 사진·영상이 외부 서버나 클라우드로 전송될 수 없습니다. 모든 파일 분석과 중복 감지는 사용자 기기 내부(On-Device)에서만 완결됩니다."
                )

                // Section 2: 3-layer protection
                SafetyFeatureItem(
                    icon = Icons.Default.CheckCircle,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    title = "3중 실수 방지 안전장치",
                    description = "1. 그룹당 원본 최소 1개 자동 보호 (스마트 선택)\n2. 삭제 직전 파일 크기·수정일 변조(TOCTOU) 실시간 재검증\n3. 30일 OS 휴지통 & 인앱 격리 보관함(Vault) 복원 지원"
                )

                // Section 3: Incremental Hash Cache
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "증분 해시 캐시 상태",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "현재 저장된 분석 캐시: ${cachedHashCount}건",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "동일 파일의 불필요한 반복 연산과 배터리 소모를 방지합니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("확인")
            }
        }
    )
}

@Composable
private fun SafetyFeatureItem(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
