package com.antigravity.duplicleaner.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.antigravity.duplicleaner.data.hash.CorruptedFileReport
import com.antigravity.duplicleaner.data.repository.ScanPhase
import com.antigravity.duplicleaner.domain.model.AutoScanInterval
import com.antigravity.duplicleaner.domain.model.CleanerViewTab
import com.antigravity.duplicleaner.domain.model.DuplicateGroup
import com.antigravity.duplicleaner.domain.model.MatchType
import com.antigravity.duplicleaner.domain.model.MediaFileItem
import com.antigravity.duplicleaner.domain.model.ResultSortOption
import com.antigravity.duplicleaner.domain.model.ScanScopeMode
import com.antigravity.duplicleaner.domain.model.SmartSelectionRule
import com.antigravity.duplicleaner.ui.CleanerUiState
import com.antigravity.duplicleaner.ui.components.CleanerModeTabRow
import com.antigravity.duplicleaner.ui.components.CleanupConfirmDialog
import com.antigravity.duplicleaner.ui.components.CleanupImpactSummary
import com.antigravity.duplicleaner.ui.components.CleanupSafetyHelper
import com.antigravity.duplicleaner.ui.components.CorruptedFileCard
import com.antigravity.duplicleaner.ui.components.EmptyFoldersCard
import com.antigravity.duplicleaner.ui.components.FilterAndFolderBar
import com.antigravity.duplicleaner.ui.components.FolderHeatmapCard
import com.antigravity.duplicleaner.ui.components.LargeFileHunterCard
import com.antigravity.duplicleaner.ui.components.PipelineFunnelDialog
import com.antigravity.duplicleaner.ui.components.PrivacyAndSafetyDialog
import com.antigravity.duplicleaner.ui.components.QuarantineVaultSheet
import com.antigravity.duplicleaner.ui.components.StorageBreakdownCard
import com.antigravity.duplicleaner.ui.components.TargetFolderAndScheduleCard
import com.antigravity.duplicleaner.ui.components.VideoCompareBottomSheet
import com.antigravity.duplicleaner.ui.components.VideoThumbnailBox
import com.antigravity.duplicleaner.ui.components.WhitelistAndHistorySheet
import com.antigravity.duplicleaner.util.FormatUtils
import com.antigravity.duplicleaner.util.ThrottleLevel

@Composable
fun CleanerMainScreen(
    uiState: CleanerUiState,
    hasMediaPermission: Boolean,
    hasAllFilesPermission: Boolean,
    onRequestMediaPermission: () -> Unit,
    onRequestAllFilesPermission: () -> Unit,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit,
    onSelectScopeMode: (ScanScopeMode) -> Unit,
    onToggleSimilarVideo: (Boolean) -> Unit,
    onSelectRule: (SmartSelectionRule) -> Unit,
    onToggleItemSelection: (String, MediaFileItem) -> Unit,
    onKeepAtLeastOneAllGroups: () -> Unit,
    onRequestCleanup: (Boolean) -> Unit,
    onOpenCompare: (DuplicateGroup) -> Unit,
    onCloseCompare: () -> Unit,
    onGenerateDemoSamples: () -> Unit,
    onClearCache: () -> Unit,
    onDismissBanner: () -> Unit,
    onOpenSettingsSheet: () -> Unit = uiState.onOpenSettingsSheet,
    onCloseSettingsSheet: () -> Unit = uiState.onCloseSettingsSheet,
    onSelectMinFileSize: (Long) -> Unit = uiState.onSelectMinFileSize,
    onAddExcludedKeyword: (String) -> Unit = uiState.onAddExcludedKeyword,
    onRemoveExcludedKeyword: (String) -> Unit = uiState.onRemoveExcludedKeyword,
    onAddProtectedFolder: (String) -> Unit = uiState.onAddProtectedFolder,
    onRemoveProtectedFolder: (String) -> Unit = uiState.onRemoveProtectedFolder,
    onSelectTab: (CleanerViewTab) -> Unit = uiState.onSelectTab,
    onIgnoreGroup: (DuplicateGroup) -> Unit = uiState.onIgnoreGroup,
    onClearIgnoredGroups: () -> Unit = uiState.onClearIgnoredGroups,
    onUpdateSimilarityThreshold: (Int) -> Unit = uiState.onUpdateSimilarityThreshold,
    onToggleLargeFileSelection: (MediaFileItem) -> Unit = uiState.onToggleLargeFileSelection,
    onPreviewLargeFile: (MediaFileItem) -> Unit = uiState.onPreviewLargeFile,
    onBuildAuditReportText: () -> String = uiState.onBuildAuditReportText,
    onRestoreQuarantineVault: () -> Unit = uiState.onRestoreQuarantineVault,
    onEmptyQuarantineVault: () -> Unit = uiState.onEmptyQuarantineVault,
    onToggleTargetFolder: (String) -> Unit = uiState.onToggleTargetFolder,
    onClearTargetFolders: () -> Unit = uiState.onClearTargetFolders,
    onPickSafFolder: () -> Unit = {},
    onSelectAutoScanInterval: (AutoScanInterval) -> Unit = uiState.onSelectAutoScanInterval,
    onOpenQuarantineSheet: () -> Unit = uiState.onOpenQuarantineSheet,
    onCloseQuarantineSheet: () -> Unit = uiState.onCloseQuarantineSheet,
    onRestoreSingleQuarantineItem: (String) -> Unit = uiState.onRestoreSingleQuarantineItem,
    onDeleteSingleQuarantineItem: (String) -> Unit = uiState.onDeleteSingleQuarantineItem,
    onToggleCorruptedFileSelection: (CorruptedFileReport) -> Unit = uiState.onToggleCorruptedFileSelection,
    onOpenFunnelDialog: () -> Unit = uiState.onOpenFunnelDialog,
    onCloseFunnelDialog: () -> Unit = uiState.onCloseFunnelDialog
) {
    val context = LocalContext.current
    var pendingCleanupSummary by remember { mutableStateOf<CleanupImpactSummary?>(null) }
    var isPrivacyDialogOpen by remember { mutableStateOf(false) }
    val hasResults = uiState.groups.isNotEmpty() ||
        uiState.largestFiles.isNotEmpty() ||
        uiState.corruptedFiles.isNotEmpty()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        topBar = {
            TopSecurityHeader(
                cachedCount = uiState.cachedHashCount,
                quarantinedCount = uiState.quarantinedCount,
                quarantinedBytes = uiState.quarantinedBytes,
                autoScanInterval = uiState.autoScanInterval,
                onOpenQuarantineSheet = onOpenQuarantineSheet,
                onOpenPrivacyDialog = { isPrivacyDialogOpen = true },
                onGenerateDemoSamples = onGenerateDemoSamples,
                onClearCache = onClearCache
            )
        },
        bottomBar = {
            if (hasResults && !uiState.isScanning) {
                val openCleanupConfirm = { isPermanentDelete: Boolean ->
                    pendingCleanupSummary = CleanupSafetyHelper.buildImpactSummary(
                        groups = uiState.groups,
                        selectedLargeFiles = uiState.largestFiles.filter { it.isSelectedForDelete },
                        selectedCorruptedFiles = uiState.corruptedFiles.filter { it.item.isSelectedForDelete },
                        isPermanentDelete = isPermanentDelete,
                        includeGroups = uiState.activeTab == CleanerViewTab.DUPLICATE_GROUPS,
                        includeLargeFiles = uiState.activeTab == CleanerViewTab.LARGE_FILES_HUNTER,
                        includeCorruptedFiles = uiState.activeTab == CleanerViewTab.CORRUPTED_FILES
                    )
                }
                BottomCleanupActionBar(
                    selectedCount = uiState.totalSelectedCount,
                    reclaimableBytes = uiState.totalReclaimableBytes,
                    onMoveToTrash = { openCleanupConfirm(false) },
                    onPermanentDelete = { openCleanupConfirm(true) }
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 알림 배너
            uiState.bannerMessage?.let { message ->
                item {
                    BannerNoticeCard(message = message, onDismiss = onDismissBanner)
                }
            }

            // 1-Tap 실행 취소 (Undo) 바
            if (uiState.lastUndoSummary != null && uiState.lastUndoQuarantineIds.isNotEmpty()) {
                item {
                    QuickUndoQuarantineBar(
                        summary = uiState.lastUndoSummary,
                        onUndo = uiState.onUndoLastQuarantine
                    )
                }
            }

            // 1. 스토리지 현황 & 다단계 스캔 컨트롤 카드
            item {
                StorageAndScanControlCard(
                    uiState = uiState,
                    hasMediaPermission = hasMediaPermission,
                    hasAllFilesPermission = hasAllFilesPermission,
                    onRequestMediaPermission = onRequestMediaPermission,
                    onRequestAllFilesPermission = onRequestAllFilesPermission,
                    onSelectScopeMode = onSelectScopeMode,
                    onToggleSimilarVideo = onToggleSimilarVideo,
                    onStartScan = onStartScan,
                    onCancelScan = onCancelScan
                )
            }

            // 1-1. 타겟 폴더 집중 스캔 & 충전 중 자동 주기 스캔 카드
            item {
                TargetFolderAndScheduleCard(
                    targetFolders = uiState.targetFolderKeywords,
                    autoScanInterval = uiState.autoScanInterval,
                    onToggleTargetFolder = onToggleTargetFolder,
                    onClearTargetFolders = onClearTargetFolders,
                    onPickSafFolder = onPickSafFolder,
                    onSelectAutoScanInterval = onSelectAutoScanInterval,
                    autoCollapseTrigger = !uiState.isScanning && uiState.groups.isNotEmpty()
                )
            }

            // 1-2. 카테고리별 용량 분포 & 보호 폴더·최소 크기·정리 기록 설정 진입 카드
            item {
                StorageBreakdownCard(
                    groups = uiState.groups,
                    cachedHashCount = uiState.cachedHashCount,
                    onOpenSettingsSheet = onOpenSettingsSheet,
                    funnelMetrics = uiState.progress.funnelMetrics,
                    onOpenFunnelDialog = onOpenFunnelDialog
                )
            }

            // 2. 실시간 파이프라인 스캔 진행 상태
            if (uiState.isScanning || uiState.progress.phase == ScanPhase.COMPLETED) {
                item {
                    PipelineProgressCard(uiState = uiState)
                }
            }

            // 2-1. 정리 가능한 빈 폴더 1-Tap 정리 카드
            if (uiState.emptyFolders.isNotEmpty() && !uiState.isScanning) {
                item {
                    EmptyFoldersCard(
                        emptyFolders = uiState.emptyFolders,
                        onCleanEmptyFolders = uiState.onCleanEmptyFolders
                    )
                }
            }

            // 2-2. 모드 전환 탭 (중복 그룹 vs 대용량 파일 Top 30 vs 손상·0바이트)
            if (hasResults || (!uiState.isScanning && uiState.progress.phase == ScanPhase.COMPLETED)) {
                item {
                    CleanerModeTabRow(
                        activeTab = uiState.activeTab,
                        duplicateGroupCount = uiState.groups.size,
                        largeFileCount = uiState.largestFiles.size,
                        onSelectTab = onSelectTab,
                        corruptedFileCount = uiState.corruptedFiles.size
                    )
                }
            }

            // 3. 탭별 결과 렌더링
            when (uiState.activeTab) {
                CleanerViewTab.DUPLICATE_GROUPS -> {
                    if (uiState.groups.isNotEmpty()) {
                        item {
                            SmartSelectionRuleSection(
                                selectedRule = uiState.config.selectionRule,
                                hasAllSelectedWarning = uiState.hasAnyAllSelectedGroup,
                                groupCount = uiState.groups.size,
                                reclaimableBytes = uiState.groups.sumOf { it.reclaimableBytes },
                                onSelectRule = onSelectRule,
                                onKeepAtLeastOneAllGroups = onKeepAtLeastOneAllGroups
                            )
                        }

                        // 3-1. 폴더별 중복 용량 히트맵 Top 5
                        item {
                            FolderHeatmapCard(
                                groups = uiState.groups,
                                protectedFolders = uiState.protectedFolders,
                                onSelectFolderDuplicates = uiState.onSelectFolderDuplicates,
                                onToggleProtectFolder = { folder ->
                                    if (uiState.protectedFolders.contains(folder)) {
                                        onRemoveProtectedFolder(folder)
                                    } else {
                                        onAddProtectedFolder(folder)
                                    }
                                }
                            )
                        }

                        // 3-2. 실시간 검색 · 매칭 유형 필터 · 정렬 · 폴더별 원클릭 안전 선택 바
                        item {
                            FilterAndFolderBar(
                                searchQuery = uiState.searchQuery,
                                onSearchQueryChange = uiState.onSearchQueryChange,
                                selectedMatchType = uiState.selectedMatchType,
                                onSelectMatchType = uiState.onSelectMatchType,
                                selectedSortOption = uiState.sortOption,
                                onSelectSortOption = uiState.onSelectSortOption,
                                folderDuplicateSummary = uiState.folderDuplicateSummary,
                                onSelectFolderDuplicates = uiState.onSelectFolderDuplicates
                            )
                        }

                        // 4. 중복 파일 그룹 리스트
                        items(
                            items = uiState.filteredGroups,
                            key = { it.groupId }
                        ) { group ->
                            DuplicateGroupCard(
                                group = group,
                                onToggleItem = { item -> onToggleItemSelection(group.groupId, item) },
                                onOpenCompare = { onOpenCompare(group) },
                                onIgnoreGroup = { onIgnoreGroup(group) }
                            )
                        }
                    } else if (!uiState.isScanning && uiState.progress.phase == ScanPhase.COMPLETED) {
                        item {
                            EmptyResultCard(onGenerateDemoSamples = onGenerateDemoSamples)
                        }
                    }
                }

                CleanerViewTab.LARGE_FILES_HUNTER -> {
                    if (uiState.largestFiles.isNotEmpty()) {
                        val maxFileSizeBytes = uiState.largestFiles.maxOfOrNull { it.sizeBytes } ?: 0L
                        itemsIndexed(
                            items = uiState.largestFiles,
                            key = { _, item -> "large_${item.uri}" }
                        ) { index, item ->
                            LargeFileHunterCard(
                                rank = index + 1,
                                item = item,
                                maxFileSizeBytes = maxFileSizeBytes,
                                onToggleSelect = onToggleLargeFileSelection,
                                onPreviewItem = onPreviewLargeFile
                            )
                        }
                    } else if (!uiState.isScanning && uiState.progress.phase == ScanPhase.COMPLETED) {
                        item {
                            EmptyResultCard(onGenerateDemoSamples = onGenerateDemoSamples)
                        }
                    }
                }

                CleanerViewTab.CORRUPTED_FILES -> {
                    if (uiState.corruptedFiles.isNotEmpty()) {
                        items(
                            items = uiState.corruptedFiles,
                            key = { "corrupt_${it.item.uri}" }
                        ) { report ->
                            CorruptedFileCard(
                                report = report,
                                isSelected = report.item.isSelectedForDelete,
                                onToggleSelect = { onToggleCorruptedFileSelection(report) }
                            )
                        }
                    } else if (!uiState.isScanning && uiState.progress.phase == ScanPhase.COMPLETED) {
                        item {
                            EmptyCorruptedResultCard()
                        }
                    }
                }
            }
        }
    }

    // 영상 비교 & 재생 바텀시트
    uiState.activeCompareGroup?.let { activeGroup ->
        val latestGroup = uiState.groups.find { it.groupId == activeGroup.groupId } ?: activeGroup
        VideoCompareBottomSheet(
            group = latestGroup,
            onDismiss = onCloseCompare,
            onToggleSelectItem = { item ->
                onToggleItemSelection(latestGroup.groupId, item)
            }
        )
    }

    // 화이트리스트·보호 폴더·최소 크기·정리 기록 바텀시트
    if (uiState.isSettingsSheetOpen) {
        WhitelistAndHistorySheet(
            minFileSizeBytes = uiState.config.minFileSizeBytes,
            excludedKeywords = uiState.config.excludedFolderKeywords,
            protectedFolders = uiState.protectedFolders,
            historySummaryText = uiState.historySummaryText,
            onSelectMinFileSize = onSelectMinFileSize,
            onAddExcludedKeyword = onAddExcludedKeyword,
            onRemoveExcludedKeyword = onRemoveExcludedKeyword,
            onAddProtectedFolder = onAddProtectedFolder,
            onRemoveProtectedFolder = onRemoveProtectedFolder,
            onDismiss = onCloseSettingsSheet,
            similarityThresholdPercent = uiState.config.similarityThresholdPercent,
            onSimilarityThresholdChange = onUpdateSimilarityThreshold,
            ignoredGroupCount = uiState.ignoredGroupCount,
            onClearIgnoredGroups = onClearIgnoredGroups,
            quarantinedCount = uiState.quarantinedCount,
            quarantinedBytes = uiState.quarantinedBytes,
            onRestoreQuarantineVault = onRestoreQuarantineVault,
            onEmptyQuarantineVault = onEmptyQuarantineVault,
            appThemeMode = uiState.appThemeMode,
            onSelectThemeMode = uiState.onSelectThemeMode,
            onShareAuditReport = {
                val reportText = onBuildAuditReportText()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("duplicleaner_audit_report", reportText))
                Toast.makeText(context, "감사 리포트가 클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "DuplicateVideoCleaner 스캔·정리 감사 리포트")
                    putExtra(Intent.EXTRA_TEXT, reportText)
                }
                try {
                    context.startActivity(Intent.createChooser(sendIntent, "감사 리포트 공유"))
                } catch (_: Exception) {
                }
            }
        )
    }

    // 안전 격리 보관함 개별 복원·영구 삭제 바텀시트
    if (uiState.isQuarantineSheetOpen) {
        QuarantineVaultSheet(
            items = uiState.quarantinedItems,
            totalBytes = uiState.quarantinedBytes,
            onRestoreSingle = onRestoreSingleQuarantineItem,
            onDeleteSingle = onDeleteSingleQuarantineItem,
            onRestoreAll = onRestoreQuarantineVault,
            onEmptyVault = onEmptyQuarantineVault,
            onDismiss = onCloseQuarantineSheet
        )
    }

    // 스캔 파이프라인 퍼널 & I/O 절감 상세 다이얼로그
    if (uiState.isFunnelDialogOpen) {
        PipelineFunnelDialog(
            metrics = uiState.progress.funnelMetrics,
            groups = uiState.groups,
            cacheHitCount = uiState.progress.cacheHitCount,
            elapsedMs = uiState.progress.elapsedMs,
            onDismiss = onCloseFunnelDialog
        )
    }

    // 삭제/정리 실행 전 최종 확인 안전 다이얼로그
    pendingCleanupSummary?.let { summary ->
        CleanupConfirmDialog(
            summary = summary,
            onConfirm = {
                onRequestCleanup(!summary.isPermanentDelete)
                pendingCleanupSummary = null
            },
            onDismiss = { pendingCleanupSummary = null }
        )
    }

    // 100% 온디바이스 오프라인 & 3중 안전장치 안내 다이얼로그
    if (isPrivacyDialogOpen) {
        PrivacyAndSafetyDialog(
            cachedHashCount = uiState.cachedHashCount,
            onDismiss = { isPrivacyDialogOpen = false }
        )
    }
}

@Composable
private fun TopSecurityHeader(
    cachedCount: Int,
    quarantinedCount: Int,
    quarantinedBytes: Long,
    autoScanInterval: AutoScanInterval,
    onOpenQuarantineSheet: () -> Unit,
    onOpenPrivacyDialog: () -> Unit,
    onGenerateDemoSamples: () -> Unit,
    onClearCache: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
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
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MovieFilter,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "중복 영상·파일 클리너",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF059669).copy(alpha = 0.10f))
                                .clickable(onClick = onOpenPrivacyDialog)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "온디바이스 보안 원칙 안내",
                                tint = Color(0xFF059669),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "100% 온디바이스 오프라인 · 캐시 ${cachedCount}건",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onGenerateDemoSamples) {
                        Icon(
                            imageVector = Icons.Default.Science,
                            contentDescription = "샘플 생성",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("샘플 테스트", style = MaterialTheme.typography.labelMedium)
                    }
                    if (cachedCount > 0) {
                        IconButton(onClick = onClearCache) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "해시 캐시 초기화",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusLabel = when {
                    autoScanInterval != AutoScanInterval.OFF ->
                        "충전 중 자동 스캔: ${autoScanInterval.label}"
                    quarantinedCount > 0 ->
                        "안전 격리 보관 중 (${FormatUtils.formatBytes(quarantinedBytes)})"
                    else ->
                        "EXIF 촬영 원본 보호 · 안전 금고 준비됨"
                }
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (autoScanInterval != AutoScanInterval.OFF) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                AssistChip(
                    onClick = onOpenQuarantineSheet,
                    label = {
                        Text(
                            text = "보관함 (${quarantinedCount})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.FolderZip,
                            contentDescription = "격리 보관함 열기",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun BannerNoticeCard(
    message: String,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text("확인", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun QuickUndoQuarantineBar(
    summary: String,
    onUndo: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "방금 격리됨: $summary",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onUndo,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "실행 취소 (복원)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StorageAndScanControlCard(
    uiState: CleanerUiState,
    hasMediaPermission: Boolean,
    hasAllFilesPermission: Boolean,
    onRequestMediaPermission: () -> Unit,
    onRequestAllFilesPermission: () -> Unit,
    onSelectScopeMode: (ScanScopeMode) -> Unit,
    onToggleSimilarVideo: (Boolean) -> Unit,
    onStartScan: () -> Unit,
    onCancelScan: () -> Unit
) {
    val overview = uiState.storageOverview
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // 스토리지 바
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "내장 스토리지 사용 현황",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "${FormatUtils.formatBytes(overview.usedBytes)} / ${FormatUtils.formatBytes(overview.totalBytes)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { overview.usedRatio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape),
                color = if (overview.usedRatio > 0.85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "여유 공간: ${FormatUtils.formatBytes(overview.freeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.cumulativeFreedBytes > 0L) {
                    Text(
                        text = "이번 정리로 확보: +${FormatUtils.formatBytes(uiState.cumulativeFreedBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF059669)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // 스캔 모드 선택
            Text(
                text = "스캔 대상 범위",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScanScopeMode.entries.forEach { mode ->
                    val selected = uiState.config.scopeMode == mode
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectScopeMode(mode) },
                        label = { Text(mode.title) }
                    )
                }
            }

            Text(
                text = uiState.config.scopeMode.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 권한 안내 배너 (필요시)
            if (!hasMediaPermission) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onRequestMediaPermission,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("미디어(영상·사진) 스캔 권한 허용하기")
                }
            } else if (uiState.config.scopeMode == ScanScopeMode.ALL_FILES && !hasAllFilesPermission) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onRequestAllFilesPermission,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderSpecial, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("모든 파일(문서·ZIP·APK) 접근 권한 허용하기")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 유사 영상(Perceptual Hash) 스위치
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "유사 영상 탐지 포함 (키프레임 dHash)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "메신저 재인코딩·해상도 변경으로 해시가 달라진 동일 영상도 탐지",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = uiState.config.includeSimilarVideos,
                    onCheckedChange = onToggleSimilarVideo
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 스캔 시작 / 중지 버튼
            if (uiState.isScanning) {
                OutlinedButton(
                    onClick = onCancelScan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("스캔 중지", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onStartScan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "다단계 해시 고속 스캔 시작",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun PipelineProgressCard(uiState: CleanerUiState) {
    val prog = uiState.progress
    val throttleColor = when (prog.throttleLevel) {
        ThrottleLevel.NORMAL -> Color(0xFF059669)
        ThrottleLevel.BALANCED -> Color(0xFFD97706)
        ThrottleLevel.THERMAL_COOLING -> MaterialTheme.colorScheme.error
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = prog.phase.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = throttleColor.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = prog.throttleLevel.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = throttleColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "${prog.elapsedMs} ms · 캐시 히트 ${prog.cacheHitCount}건 · 병렬 ${prog.throttleLevel.maxParallelWorkers}스레드",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.isScanning) {
                val fraction = if (prog.totalCount > 0) {
                    (prog.processedCount.toFloat() / prog.totalCount.toFloat()).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "분석 중 (${prog.processedCount}/${prog.totalCount}): ${prog.currentFileName}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    text = "중복 그룹 ${uiState.groups.size}개 발견 · 정리 시 최대 ${FormatUtils.formatBytes(uiState.totalReclaimableBytes)} 확보 가능",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SmartSelectionRuleSection(
    selectedRule: SmartSelectionRule,
    hasAllSelectedWarning: Boolean,
    groupCount: Int,
    reclaimableBytes: Long,
    onSelectRule: (SmartSelectionRule) -> Unit,
    onKeepAtLeastOneAllGroups: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "스마트 원본 보존 기준 (${groupCount}개 그룹)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "절약 가능: ${FormatUtils.formatBytes(reclaimableBytes)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmartSelectionRule.entries.forEach { rule ->
                FilterChip(
                    selected = selectedRule == rule,
                    onClick = { onSelectRule(rule) },
                    label = { Text(rule.title) }
                )
            }
        }

        AnimatedVisibility(visible = hasAllSelectedWarning) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "원본까지 모두 삭제 선택된 그룹이 있습니다!",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    TextButton(onClick = onKeepAtLeastOneAllGroups) {
                        Text("원본 1개 자동 보호", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    onToggleItem: (MediaFileItem) -> Unit,
    onOpenCompare: () -> Unit,
    onIgnoreGroup: () -> Unit = {}
) {
    val badgeBgColor = when (group.matchType) {
        MatchType.EXACT_HASH -> MaterialTheme.colorScheme.primaryContainer
        MatchType.CONTAINER_PAYLOAD_MATCH -> MaterialTheme.colorScheme.tertiaryContainer
        MatchType.TRIMMED_CLIP_MATCH -> Color(0xFFFEF3C7)
        MatchType.AUDIO_FINGERPRINT_MATCH -> Color(0xFFFCE7F3)
        MatchType.SIMILAR_VIDEO -> MaterialTheme.colorScheme.secondaryContainer
    }
    val badgeTextColor = when (group.matchType) {
        MatchType.EXACT_HASH -> MaterialTheme.colorScheme.onPrimaryContainer
        MatchType.CONTAINER_PAYLOAD_MATCH -> MaterialTheme.colorScheme.onTertiaryContainer
        MatchType.TRIMMED_CLIP_MATCH -> Color(0xFFB45309)
        MatchType.AUDIO_FINGERPRINT_MATCH -> Color(0xFFBE185D)
        MatchType.SIMILAR_VIDEO -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val badgeText = when (group.matchType) {
        MatchType.EXACT_HASH -> "100% 해시 일치"
        MatchType.CONTAINER_PAYLOAD_MATCH -> "컨테이너 스트림 일치"
        MatchType.TRIMMED_CLIP_MATCH -> "${group.similarityPercent}% 구간·클립 일치"
        MatchType.AUDIO_FINGERPRINT_MATCH -> "${group.similarityPercent}% 크로스 오디오 일치"
        MatchType.SIMILAR_VIDEO -> "${group.similarityPercent}% 유사 미디어"
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 그룹 헤더
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = badgeBgColor,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeTextColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${group.items.size}개 중복 · ${FormatUtils.formatBytes(group.reclaimableBytes)} 확보",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onIgnoreGroup,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VisibilityOff,
                            contentDescription = "그룹 무시",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("무시", style = MaterialTheme.typography.labelMedium)
                    }

                    TextButton(
                        onClick = onOpenCompare,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Compare,
                            contentDescription = "비교 및 재생",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("비교·재생", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Text(
                text = "해시 시그니처: ${group.signature}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 그룹 내 개별 파일 리스트
            group.items.forEach { item ->
                val isKeep = !item.isSelectedForDelete
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isKeep) {
                                Color(0xFF059669).copy(alpha = 0.08f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = if (isKeep) {
                                Color(0xFF059669).copy(alpha = 0.35f)
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onToggleItem(item) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VideoThumbnailBox(item = item)

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = if (isKeep) Color(0xFF059669) else MaterialTheme.colorScheme.error,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = if (isKeep) "보존(원본)" else "정리 대상",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            item.keepBadgeReason?.let { reason ->
                                Text(
                                    text = reason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isKeep) Color(0xFF059669) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = item.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        val metaParts = buildList {
                            add(item.relativePath)
                            add(FormatUtils.formatBytes(item.sizeBytes))
                            item.resolutionLabel?.let { add(it) }
                            item.formattedDuration?.let { add(it) }
                            item.videoCodec?.let { add(it) }
                            if (!item.hasAudioTrack) {
                                add("무음")
                            }
                            if (item.authenticityScore > 0) {
                                add("EXIF ${item.authenticityScore}점")
                            }
                        }
                        Text(
                            text = metaParts.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Checkbox(
                        checked = item.isSelectedForDelete,
                        onCheckedChange = { onToggleItem(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyCorruptedResultCard() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "손상·0바이트 이상 파일이 없습니다",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "0바이트 빈 파일, 미완료 임시 파일(.tmp/.part), 헤더가 손상된 영상이 발견되지 않았습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyResultCard(onGenerateDemoSamples: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "발견된 중복 영상·파일이 없습니다",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "파일명이 다르지만 해시가 같은 영상 탐지 동작을 지금 바로 확인해 보려면 아래 버튼으로 샘플 중복 세트를 생성해 보세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedButton(onClick = onGenerateDemoSamples) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("테스트용 중복 영상 샘플 생성 후 스캔")
            }
        }
    }
}

@Composable
private fun BottomCleanupActionBar(
    selectedCount: Int,
    reclaimableBytes: Long,
    onMoveToTrash: () -> Unit,
    onPermanentDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onPermanentDelete,
                enabled = selectedCount > 0,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(50.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("영구 삭제", color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = onMoveToTrash,
                enabled = selectedCount > 0,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "휴지통 이동 (${selectedCount}개 · ${FormatUtils.formatBytes(reclaimableBytes)})",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
