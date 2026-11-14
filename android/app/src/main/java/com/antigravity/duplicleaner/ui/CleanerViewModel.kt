package com.antigravity.duplicleaner.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.duplicleaner.data.hash.CorruptedFileReport
import com.antigravity.duplicleaner.data.local.CleanupHistoryStore
import com.antigravity.duplicleaner.data.local.EmptyFolderCandidate
import com.antigravity.duplicleaner.data.local.EmptyFolderCleaner
import com.antigravity.duplicleaner.data.local.HashCacheDatabase
import com.antigravity.duplicleaner.data.local.QuarantineVaultManager
import com.antigravity.duplicleaner.data.local.QuarantinedItemRecord
import com.antigravity.duplicleaner.data.local.WhitelistPreferences
import com.antigravity.duplicleaner.data.repository.CleanupRequestOutcome
import com.antigravity.duplicleaner.data.repository.DemoSampleGenerator
import com.antigravity.duplicleaner.data.repository.MediaScannerRepository
import com.antigravity.duplicleaner.data.repository.ScanPhase
import com.antigravity.duplicleaner.data.repository.ScanProgressUpdate
import com.antigravity.duplicleaner.data.repository.StorageOverview
import com.antigravity.duplicleaner.domain.model.AutoScanInterval
import com.antigravity.duplicleaner.domain.model.CleanerViewTab
import com.antigravity.duplicleaner.domain.model.DuplicateGroup
import com.antigravity.duplicleaner.domain.model.MatchType
import com.antigravity.duplicleaner.domain.model.MediaFileItem
import com.antigravity.duplicleaner.domain.model.ResultSortOption
import com.antigravity.duplicleaner.domain.model.ScanConfig
import com.antigravity.duplicleaner.domain.model.ScanScopeMode
import com.antigravity.duplicleaner.domain.model.SmartSelectionRule
import com.antigravity.duplicleaner.domain.usecase.SmartSelectUseCase
import com.antigravity.duplicleaner.service.AutoScanScheduler
import com.antigravity.duplicleaner.service.ForegroundScanService
import com.antigravity.duplicleaner.ui.theme.AppThemeMode
import com.antigravity.duplicleaner.util.AuditReportExporter
import com.antigravity.duplicleaner.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PendingSystemConfirmation(
    val intentSender: IntentSender,
    val targetUris: List<Uri>,
    val expectedFreedBytes: Long,
    val isTrashMove: Boolean
)

data class CleanerUiState(
    val storageOverview: StorageOverview = StorageOverview(0L, 0L, 0L),
    val config: ScanConfig = ScanConfig(),
    val protectedFolders: Set<String> = WhitelistPreferences.DEFAULT_PROTECTED_KEEP_FOLDERS,
    val historySummaryText: String = "아직 정리 기록이 없습니다.",
    val isSettingsSheetOpen: Boolean = false,
    val isScanning: Boolean = false,
    val progress: ScanProgressUpdate = ScanProgressUpdate(ScanPhase.IDLE, 0, 0),
    val activeTab: CleanerViewTab = CleanerViewTab.DUPLICATE_GROUPS,
    val groups: List<DuplicateGroup> = emptyList(),
    val largestFiles: List<MediaFileItem> = emptyList(),
    val corruptedFiles: List<CorruptedFileReport> = emptyList(),
    val ignoredGroupCount: Int = 0,
    val cachedHashCount: Int = 0,
    val cumulativeFreedBytes: Long = 0L,
    val bannerMessage: String? = null,
    val activeCompareGroup: DuplicateGroup? = null,
    val pendingConfirmation: PendingSystemConfirmation? = null,
    val searchQuery: String = "",
    val selectedMatchType: MatchType? = null,
    val sortOption: ResultSortOption = ResultSortOption.RECLAIMABLE_DESC,
    val onOpenSettingsSheet: () -> Unit = {},
    val onCloseSettingsSheet: () -> Unit = {},
    val onSelectMinFileSize: (Long) -> Unit = {},
    val onAddExcludedKeyword: (String) -> Unit = {},
    val onRemoveExcludedKeyword: (String) -> Unit = {},
    val onAddProtectedFolder: (String) -> Unit = {},
    val onRemoveProtectedFolder: (String) -> Unit = {},
    val onSearchQueryChange: (String) -> Unit = {},
    val onSelectMatchType: (MatchType?) -> Unit = {},
    val onSelectSortOption: (ResultSortOption) -> Unit = {},
    val onSelectFolderDuplicates: (String) -> Unit = {},
    val onSelectTab: (CleanerViewTab) -> Unit = {},
    val onIgnoreGroup: (DuplicateGroup) -> Unit = {},
    val onClearIgnoredGroups: () -> Unit = {},
    val onUpdateSimilarityThreshold: (Int) -> Unit = {},
    val onToggleLargeFileSelection: (MediaFileItem) -> Unit = {},
    val onPreviewLargeFile: (MediaFileItem) -> Unit = {},
    val onToggleCorruptedFileSelection: (CorruptedFileReport) -> Unit = {},
    val onBuildAuditReportText: () -> String = { "" },
    val quarantinedCount: Int = 0,
    val quarantinedBytes: Long = 0L,
    val quarantinedItems: List<QuarantinedItemRecord> = emptyList(),
    val isQuarantineSheetOpen: Boolean = false,
    val targetFolderKeywords: Set<String> = emptySet(),
    val autoScanInterval: AutoScanInterval = AutoScanInterval.OFF,
    val onRestoreQuarantineVault: () -> Unit = {},
    val onEmptyQuarantineVault: () -> Unit = {},
    val onOpenQuarantineSheet: () -> Unit = {},
    val onCloseQuarantineSheet: () -> Unit = {},
    val onRestoreSingleQuarantineItem: (String) -> Unit = {},
    val onDeleteSingleQuarantineItem: (String) -> Unit = {},
    val onToggleTargetFolder: (String) -> Unit = {},
    val onClearTargetFolders: () -> Unit = {},
    val onSelectAutoScanInterval: (AutoScanInterval) -> Unit = {},
    val isFunnelDialogOpen: Boolean = false,
    val onOpenFunnelDialog: () -> Unit = {},
    val onCloseFunnelDialog: () -> Unit = {},
    val lastUndoQuarantineIds: List<String> = emptyList(),
    val lastUndoSummary: String? = null,
    val emptyFolders: List<EmptyFolderCandidate> = emptyList(),
    val onUndoLastQuarantine: () -> Unit = {},
    val onCleanEmptyFolders: () -> Unit = {},
    val appThemeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val onSelectThemeMode: (AppThemeMode) -> Unit = {}
) {
    val totalSelectedCount: Int
        get() = when (activeTab) {
            CleanerViewTab.DUPLICATE_GROUPS -> groups.sumOf { it.selectedCount }
            CleanerViewTab.LARGE_FILES_HUNTER -> largestFiles.count { it.isSelectedForDelete }
            CleanerViewTab.CORRUPTED_FILES -> corruptedFiles.count { it.item.isSelectedForDelete }
        }

    val totalReclaimableBytes: Long
        get() = when (activeTab) {
            CleanerViewTab.DUPLICATE_GROUPS -> groups.sumOf { it.reclaimableBytes }
            CleanerViewTab.LARGE_FILES_HUNTER -> largestFiles
                .filter { it.isSelectedForDelete }
                .sumOf { it.sizeBytes }
            CleanerViewTab.CORRUPTED_FILES -> corruptedFiles
                .filter { it.item.isSelectedForDelete }
                .sumOf { it.item.sizeBytes }
        }

    val hasAnyAllSelectedGroup: Boolean
        get() = groups.any { it.isAllSelectedWarning }

    val filteredGroups: List<DuplicateGroup>
        get() {
            val query = searchQuery.trim().lowercase()
            val filtered = groups.filter { group ->
                val matchesType = selectedMatchType == null || group.matchType == selectedMatchType
                val matchesQuery = if (query.isEmpty()) {
                    true
                } else {
                    group.items.any { item ->
                        item.displayName.lowercase().contains(query) ||
                            item.relativePath.lowercase().contains(query) ||
                            (item.absolutePath?.lowercase()?.contains(query) == true)
                    }
                }
                matchesType && matchesQuery
            }
            return when (sortOption) {
                ResultSortOption.RECLAIMABLE_DESC -> filtered.sortedByDescending { it.reclaimableBytes }
                ResultSortOption.FILE_SIZE_DESC -> filtered.sortedByDescending { group ->
                    group.items.maxOfOrNull { it.sizeBytes } ?: 0L
                }
                ResultSortOption.ITEM_COUNT_DESC -> filtered.sortedByDescending { it.items.size }
                ResultSortOption.NEWEST_DESC -> filtered.sortedByDescending { group ->
                    group.items.maxOfOrNull { it.lastModifiedSeconds } ?: 0L
                }
            }
        }

    val folderDuplicateSummary: List<Pair<String, Int>>
        get() {
            val counts = mutableMapOf<String, Int>()
            for (group in groups) {
                for (item in group.items) {
                    val folder = item.relativePath.trim().trim('/', '\\')
                        .ifEmpty { "내장 스토리지" }
                    counts[folder] = (counts[folder] ?: 0) + 1
                }
            }
            return counts.entries
                .sortedByDescending { it.value }
                .take(8)
                .map { it.key to it.value }
        }
}

class CleanerViewModel(application: Application) : AndroidViewModel(application) {

    private val database = HashCacheDatabase.getInstance(application)
    private val repository = MediaScannerRepository(application, database.hashCacheDao())
    private val whitelistPrefs = WhitelistPreferences(application)
    private val historyStore = CleanupHistoryStore(application)
    private val quarantineVault = QuarantineVaultManager(application)

    private val _uiState = MutableStateFlow(
        run {
            val initialTargetFolders = whitelistPrefs.getTargetFolderKeywords()
            val initialAutoScanInterval = whitelistPrefs.getAutoScanInterval()
            CleanerUiState(
                config = ScanConfig(
                    minFileSizeBytes = whitelistPrefs.getMinFileSizeBytes(),
                    excludedFolderKeywords = whitelistPrefs.getExcludedKeywords(),
                    similarityThresholdPercent = whitelistPrefs.getSimilarityThresholdPercent(),
                    ignoredGroupSignatures = whitelistPrefs.getIgnoredGroupSignatures(),
                    targetFolderKeywords = initialTargetFolders
                ),
                protectedFolders = whitelistPrefs.getProtectedKeepFolders(),
                ignoredGroupCount = whitelistPrefs.getIgnoredGroupSignatures().size,
                targetFolderKeywords = initialTargetFolders,
                autoScanInterval = initialAutoScanInterval,
                historySummaryText = historyStore.buildHistorySummaryText(),
                cumulativeFreedBytes = historyStore.getTotalLifetimeFreedBytes(),
                onOpenSettingsSheet = ::openSettingsSheet,
                onCloseSettingsSheet = ::closeSettingsSheet,
                onSelectMinFileSize = ::updateMinFileSize,
                onAddExcludedKeyword = ::addExcludedKeyword,
                onRemoveExcludedKeyword = ::removeExcludedKeyword,
                onAddProtectedFolder = ::addProtectedFolder,
                onRemoveProtectedFolder = ::removeProtectedFolder,
                onSearchQueryChange = ::onSearchQueryChange,
                onSelectMatchType = ::onSelectMatchType,
                onSelectSortOption = ::onSelectSortOption,
                onSelectFolderDuplicates = ::onSelectFolderDuplicates,
                onSelectTab = ::onSelectTab,
                onIgnoreGroup = ::onIgnoreGroup,
                onClearIgnoredGroups = ::onClearIgnoredGroups,
                onUpdateSimilarityThreshold = ::onUpdateSimilarityThreshold,
                onToggleLargeFileSelection = ::onToggleLargeFileSelection,
                onPreviewLargeFile = ::onPreviewLargeFile,
                onToggleCorruptedFileSelection = ::toggleCorruptedFileSelection,
                onBuildAuditReportText = ::buildAuditReportText,
                onRestoreQuarantineVault = ::restoreQuarantineVault,
                onEmptyQuarantineVault = ::emptyQuarantineVault,
                onOpenQuarantineSheet = ::openQuarantineSheet,
                onCloseQuarantineSheet = ::closeQuarantineSheet,
                onRestoreSingleQuarantineItem = ::restoreSingleQuarantineItem,
                onDeleteSingleQuarantineItem = ::deleteSingleQuarantineItem,
                onToggleTargetFolder = ::toggleTargetFolder,
                onClearTargetFolders = ::clearTargetFolders,
                onSelectAutoScanInterval = ::selectAutoScanInterval,
                onOpenFunnelDialog = ::openFunnelDialog,
                onCloseFunnelDialog = ::closeFunnelDialog,
                onUndoLastQuarantine = ::undoLastQuarantine,
                onCleanEmptyFolders = ::cleanEmptyFolders,
                appThemeMode = whitelistPrefs.getAppThemeMode(),
                onSelectThemeMode = ::setAppThemeMode
            )
        }
    )
    val uiState: StateFlow<CleanerUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    init {
        refreshStorageAndCacheMetrics()
    }

    fun refreshStorageAndCacheMetrics() {
        viewModelScope.launch {
            val overview = repository.getStorageOverview()
            val cachedCount = repository.getCachedFileCount()
            val qItems = readQuarantineVaultItems()
            val emptyList = scanEmptyFoldersInternal()
            _uiState.update {
                it.withQuarantineItems(qItems).copy(
                    storageOverview = overview,
                    cachedHashCount = cachedCount,
                    historySummaryText = historyStore.buildHistorySummaryText(),
                    cumulativeFreedBytes = historyStore.getTotalLifetimeFreedBytes(),
                    emptyFolders = emptyList
                )
            }
        }
    }

    private suspend fun scanEmptyFoldersInternal(): List<EmptyFolderCandidate> = withContext(Dispatchers.IO) {
        val roots = mutableListOf<File>()
        try {
            val appExtDir = getApplication<Application>().getExternalFilesDir(null)
            if (appExtDir != null) {
                val demoDir = File(appExtDir, "DemoDuplicates")
                if (demoDir.exists() && demoDir.isDirectory) {
                    roots.add(demoDir)
                }
                roots.add(appExtDir)
            }
            val extRoot = android.os.Environment.getExternalStorageDirectory()
            if (extRoot != null && extRoot.exists() && extRoot.canRead()) {
                roots.add(extRoot)
            }
        } catch (e: Exception) {
            // ignore
        }
        val state = _uiState.value
        EmptyFolderCleaner.findEmptyFolders(
            rootDirs = roots.distinct(),
            excludedKeywords = state.config.excludedFolderKeywords,
            protectedFolders = state.protectedFolders
        )
    }

    private fun refreshEmptyFoldersInternal() {
        viewModelScope.launch {
            val list = scanEmptyFoldersInternal()
            _uiState.update { it.copy(emptyFolders = list) }
        }
    }

    private suspend fun readQuarantineVaultItems(): List<QuarantinedItemRecord> =
        withContext(Dispatchers.IO) {
            quarantineVault.getQuarantinedItems()
        }

    private fun CleanerUiState.withQuarantineItems(
        items: List<QuarantinedItemRecord>
    ): CleanerUiState = copy(
        quarantinedItems = items,
        quarantinedCount = items.size,
        quarantinedBytes = items.sumOf { it.sizeBytes }
    )

    fun updateScopeMode(mode: ScanScopeMode) {
        _uiState.update { it.copy(config = it.config.copy(scopeMode = mode)) }
    }

    fun updateIncludeSimilarVideos(include: Boolean) {
        _uiState.update { it.copy(config = it.config.copy(includeSimilarVideos = include)) }
    }

    fun updateMinFileSize(bytes: Long) {
        whitelistPrefs.setMinFileSizeBytes(bytes)
        _uiState.update {
            it.copy(
                config = it.config.copy(minFileSizeBytes = bytes),
                bannerMessage = "최소 스캔 파일 크기가 ${FormatUtils.formatBytes(bytes)}로 설정되었습니다."
            )
        }
    }

    fun addExcludedKeyword(keyword: String) {
        val updated = whitelistPrefs.addExcludedKeyword(keyword)
        _uiState.update {
            it.copy(
                config = it.config.copy(excludedFolderKeywords = updated),
                bannerMessage = "스캔 제외 키워드 '${keyword.trim()}'가 추가되었습니다."
            )
        }
    }

    fun removeExcludedKeyword(keyword: String) {
        val updated = whitelistPrefs.removeExcludedKeyword(keyword)
        _uiState.update {
            it.copy(
                config = it.config.copy(excludedFolderKeywords = updated),
                bannerMessage = "스캔 제외 키워드 '${keyword.trim()}'가 해제되었습니다."
            )
        }
    }

    fun addProtectedFolder(folder: String) {
        val updated = whitelistPrefs.addProtectedKeepFolder(folder)
        _uiState.update { state ->
            val reselectedGroups = SmartSelectUseCase.applyRuleToGroups(
                groups = state.groups,
                rule = state.config.selectionRule,
                protectedFolderKeywords = updated
            )
            state.copy(
                protectedFolders = updated,
                groups = reselectedGroups,
                bannerMessage = "원본 보호 폴더 '${folder.trim()}'가 등록되어 보존 규칙에 반영되었습니다."
            )
        }
    }

    fun removeProtectedFolder(folder: String) {
        val updated = whitelistPrefs.removeProtectedKeepFolder(folder)
        _uiState.update { state ->
            val reselectedGroups = SmartSelectUseCase.applyRuleToGroups(
                groups = state.groups,
                rule = state.config.selectionRule,
                protectedFolderKeywords = updated
            )
            state.copy(
                protectedFolders = updated,
                groups = reselectedGroups,
                bannerMessage = "원본 보호 폴더 '${folder.trim()}'가 해제되었습니다."
            )
        }
    }

    fun openSettingsSheet() {
        viewModelScope.launch {
            val qItems = readQuarantineVaultItems()
            _uiState.update {
                it.withQuarantineItems(qItems).copy(
                    isSettingsSheetOpen = true,
                    historySummaryText = historyStore.buildHistorySummaryText()
                )
            }
        }
    }

    fun openQuarantineSheet() {
        viewModelScope.launch {
            val qItems = readQuarantineVaultItems()
            _uiState.update {
                it.withQuarantineItems(qItems).copy(
                    isQuarantineSheetOpen = true
                )
            }
        }
    }

    fun closeQuarantineSheet() {
        _uiState.update { it.copy(isQuarantineSheetOpen = false) }
    }

    fun restoreSingleQuarantineItem(itemId: String) {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                quarantineVault.restoreSingleItem(itemId)
            }
            val qItems = readQuarantineVaultItems()
            _uiState.update {
                it.withQuarantineItems(qItems).copy(
                    storageOverview = repository.getStorageOverview(),
                    bannerMessage = if (restored) {
                        "선택한 파일을 원래 위치로 복구했습니다. 다시 스캔하면 목록에 표시됩니다."
                    } else {
                        "선택한 파일을 복구하지 못했습니다."
                    }
                )
            }
        }
    }

    fun deleteSingleQuarantineItem(itemId: String) {
        viewModelScope.launch {
            val freedBytes = withContext(Dispatchers.IO) {
                quarantineVault.deleteSingleVaultItem(itemId)
            }
            val qItems = readQuarantineVaultItems()
            _uiState.update {
                it.withQuarantineItems(qItems).copy(
                    storageOverview = repository.getStorageOverview(),
                    bannerMessage = if (freedBytes > 0L) {
                        "격리 파일 1개를 영구 삭제해 ${FormatUtils.formatBytes(freedBytes)}의 공간을 확보했습니다."
                    } else {
                        "격리 항목을 보관함에서 제거했습니다."
                    }
                )
            }
        }
    }

    fun restoreQuarantineVault() {
        viewModelScope.launch {
            val restoredCount = withContext(Dispatchers.IO) {
                quarantineVault.restoreAllQuarantinedFiles()
            }
            val qItems = readQuarantineVaultItems()
            _uiState.update {
                it.withQuarantineItems(qItems).copy(
                    storageOverview = repository.getStorageOverview(),
                    bannerMessage = if (restoredCount > 0) {
                        "안전 격리 보관함에서 ${restoredCount}개 파일을 원래 위치로 복구했습니다. 다시 스캔하면 목록에 표시됩니다."
                    } else {
                        "복구할 격리 파일이 없습니다."
                    }
                )
            }
        }
    }

    fun emptyQuarantineVault() {
        viewModelScope.launch {
            val freedBytes = withContext(Dispatchers.IO) {
                quarantineVault.emptyQuarantineVault()
            }
            val qItems = readQuarantineVaultItems()
            _uiState.update {
                it.withQuarantineItems(qItems).copy(
                    storageOverview = repository.getStorageOverview(),
                    bannerMessage = if (freedBytes > 0L) {
                        "안전 격리 보관함을 비워 ${FormatUtils.formatBytes(freedBytes)}의 공간을 영구 확보했습니다."
                    } else {
                        "안전 격리 보관함을 비웠습니다."
                    }
                )
            }
        }
    }

    fun toggleTargetFolder(folder: String) {
        val updated = whitelistPrefs.toggleTargetFolderKeyword(folder)
        _uiState.update { state ->
            state.copy(
                config = state.config.copy(targetFolderKeywords = updated),
                targetFolderKeywords = updated,
                bannerMessage = if (updated.isEmpty()) {
                    "타겟 폴더 제한을 해제하여 전체 스토리지를 스캔합니다."
                } else {
                    "타겟 폴더 집중 스캔: ${updated.joinToString(", ")}"
                }
            )
        }
    }

    fun addTargetFolder(folder: String) {
        val trimmed = folder.trim()
        if (trimmed.isEmpty()) return
        val updated = whitelistPrefs.addTargetFolderKeyword(trimmed)
        _uiState.update { state ->
            state.copy(
                config = state.config.copy(targetFolderKeywords = updated),
                targetFolderKeywords = updated,
                bannerMessage = "SAF 타겟 폴더 '${trimmed}'가 집중 스캔 대상에 추가되었습니다."
            )
        }
    }

    fun clearTargetFolders() {
        val updated = whitelistPrefs.clearTargetFolderKeywords()
        _uiState.update { state ->
            state.copy(
                config = state.config.copy(targetFolderKeywords = updated),
                targetFolderKeywords = updated,
                bannerMessage = "전체 기기 스토리지 스캔으로 전환되었습니다."
            )
        }
    }

    fun selectAutoScanInterval(interval: AutoScanInterval) {
        whitelistPrefs.setAutoScanInterval(interval)
        val scheduled = AutoScanScheduler.schedulePeriodicScan(
            context = getApplication(),
            interval = interval,
            requireCharging = true
        )
        _uiState.update { state ->
            val message = when {
                interval == AutoScanInterval.OFF ->
                    "충전 중 자동 주기 스캔이 해제되었습니다."
                scheduled ->
                    "충전 중 자동 스캔이 '${interval.label}' 주기로 예약되었습니다."
                else ->
                    "자동 스캔 주기(${interval.label})가 저장되었습니다."
            }
            state.copy(
                autoScanInterval = interval,
                bannerMessage = message
            )
        }
    }

    fun closeSettingsSheet() {
        _uiState.update { it.copy(isSettingsSheetOpen = false) }
    }

    fun setAppThemeMode(mode: AppThemeMode) {
        whitelistPrefs.setAppThemeMode(mode)
        _uiState.update { state ->
            state.copy(appThemeMode = mode)
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onSelectMatchType(matchType: MatchType?) {
        _uiState.update { it.copy(selectedMatchType = matchType) }
    }

    fun onSelectSortOption(sortOption: ResultSortOption) {
        _uiState.update { it.copy(sortOption = sortOption) }
    }

    fun onSelectFolderDuplicates(folder: String) {
        _uiState.update { state ->
            val updatedGroups = SmartSelectUseCase.selectFolderDuplicatesSafely(
                groups = state.groups,
                targetFolderKeyword = folder,
                protectedFolderKeywords = state.protectedFolders
            )
            state.copy(
                groups = updatedGroups,
                bannerMessage = "'$folder' 폴더의 사본을 선택했습니다 (그룹별 원본 최소 1개 자동 보호)."
            )
        }
    }

    fun onSelectTab(tab: CleanerViewTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun onIgnoreGroup(group: DuplicateGroup) {
        val updatedIgnored = whitelistPrefs.addIgnoredGroupSignature(group.signature)
        _uiState.update { state ->
            val filteredGroups = state.groups.filterNot {
                it.groupId == group.groupId || it.signature == group.signature
            }
            state.copy(
                config = state.config.copy(ignoredGroupSignatures = updatedIgnored),
                groups = filteredGroups,
                ignoredGroupCount = updatedIgnored.size,
                bannerMessage = "중복 그룹(${group.signature})을 숨김(무시) 처리했습니다. 고급 설정에서 복원할 수 있습니다."
            )
        }
    }

    fun onClearIgnoredGroups() {
        val cleared = whitelistPrefs.clearIgnoredGroupSignatures()
        _uiState.update { state ->
            state.copy(
                config = state.config.copy(ignoredGroupSignatures = cleared),
                ignoredGroupCount = 0,
                bannerMessage = "무시한 중복 그룹 목록이 모두 복원되었습니다. 다시 스캔하면 표시됩니다."
            )
        }
    }

    fun onUpdateSimilarityThreshold(percent: Int) {
        val coerced = percent.coerceIn(
            WhitelistPreferences.MIN_SIMILARITY_THRESHOLD_PERCENT,
            WhitelistPreferences.MAX_SIMILARITY_THRESHOLD_PERCENT
        )
        whitelistPrefs.setSimilarityThresholdPercent(coerced)
        _uiState.update { state ->
            state.copy(
                config = state.config.copy(similarityThresholdPercent = coerced)
            )
        }
    }

    fun onToggleLargeFileSelection(item: MediaFileItem) {
        _uiState.update { state ->
            val updatedLargest = state.largestFiles.map { file ->
                if (file.uri == item.uri) {
                    file.copy(isSelectedForDelete = !file.isSelectedForDelete)
                } else {
                    file
                }
            }
            state.copy(largestFiles = updatedLargest)
        }
    }

    fun toggleCorruptedFileSelection(report: CorruptedFileReport) {
        _uiState.update { state ->
            val updatedCorrupted = state.corruptedFiles.map { entry ->
                if (entry.item.uri == report.item.uri) {
                    entry.copy(item = entry.item.copy(isSelectedForDelete = !entry.item.isSelectedForDelete))
                } else {
                    entry
                }
            }
            state.copy(corruptedFiles = updatedCorrupted)
        }
    }

    fun onPreviewLargeFile(item: MediaFileItem) {
        val previewGroup = DuplicateGroup(
            groupId = "LARGE_PREVIEW_${item.id}",
            signature = "large:${item.sizeBytes}",
            matchType = MatchType.EXACT_HASH,
            similarityPercent = 100,
            category = item.category,
            items = listOf(item)
        )
        _uiState.update { it.copy(activeCompareGroup = previewGroup) }
    }

    fun buildAuditReportText(): String {
        val state = _uiState.value
        return AuditReportExporter.buildMarkdownAuditReport(
            groups = state.groups,
            largestFiles = state.largestFiles,
            funnelMetrics = state.progress.funnelMetrics,
            cumulativeFreedBytes = state.cumulativeFreedBytes
        )
    }

    fun updateSelectionRule(rule: SmartSelectionRule) {
        _uiState.update { state ->
            val updatedConfig = state.config.copy(selectionRule = rule)
            val reselectedGroups = SmartSelectUseCase.applyRuleToGroups(
                groups = state.groups,
                rule = rule,
                protectedFolderKeywords = state.protectedFolders
            )
            state.copy(
                config = updatedConfig,
                groups = reselectedGroups,
                bannerMessage = "'${rule.title}' 규칙이 모든 중복 그룹에 적용되었습니다."
            )
        }
    }

    fun startScan() {
        scanJob?.cancel()
        val currentConfig = _uiState.value.config
        scanJob = viewModelScope.launch {
            try {
                ForegroundScanService.start(
                    getApplication(),
                    "중복 파일 고속 스캔 중",
                    "스토리지 메타데이터 분석 중..."
                )
            } catch (e: Exception) {
                // ignore
            }
            _uiState.update {
                it.copy(
                    isScanning = true,
                    bannerMessage = null,
                    progress = ScanProgressUpdate(ScanPhase.STAGE_1_INDEXING, 0, 0)
                )
            }

            try {
                repository.scanDuplicates(currentConfig).collect { update ->
                    try {
                        ForegroundScanService.updateProgress(
                            getApplication(),
                            update.processedCount,
                            update.totalCount,
                            update.phase.title
                        )
                    } catch (e: Exception) {
                        // ignore
                    }

                    if (update.phase == ScanPhase.COMPLETED) {
                        try {
                            ForegroundScanService.stop(getApplication())
                        } catch (e: Exception) {
                            // ignore
                        }
                        val cachedCount = repository.getCachedFileCount()
                        val emptyList = scanEmptyFoldersInternal()
                        _uiState.update { state ->
                            val evaluatedGroups = SmartSelectUseCase.applyRuleToGroups(
                                groups = update.groups,
                                rule = state.config.selectionRule,
                                protectedFolderKeywords = state.protectedFolders
                            )
                            val refreshedLargest = update.largestFiles.map {
                                it.copy(isSelectedForDelete = false)
                            }
                            val refreshedCorrupted = update.corruptedFiles.map { report ->
                                report.copy(item = report.item.copy(isSelectedForDelete = true))
                            }
                            state.copy(
                                isScanning = false,
                                progress = update.copy(
                                    groups = evaluatedGroups,
                                    largestFiles = refreshedLargest,
                                    corruptedFiles = refreshedCorrupted
                                ),
                                groups = evaluatedGroups,
                                largestFiles = refreshedLargest,
                                corruptedFiles = refreshedCorrupted,
                                cachedHashCount = cachedCount,
                                storageOverview = repository.getStorageOverview(),
                                emptyFolders = emptyList
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                progress = update,
                                largestFiles = if (update.largestFiles.isNotEmpty()) {
                                    update.largestFiles
                                } else {
                                    it.largestFiles
                                }
                            )
                        }
                    }
                }
            } finally {
                try {
                    ForegroundScanService.stop(getApplication())
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        try {
            ForegroundScanService.stop(getApplication())
        } catch (e: Exception) {
            // ignore
        }
        _uiState.update {
            it.copy(
                isScanning = false,
                progress = it.progress.copy(phase = ScanPhase.IDLE),
                bannerMessage = "스캔이 중지되었습니다."
            )
        }
    }

    fun toggleItemSelection(groupId: String, itemUri: Uri) {
        _uiState.update { state ->
            if (groupId.startsWith("LARGE_PREVIEW_")) {
                val updatedLargest = state.largestFiles.map { file ->
                    if (file.uri == itemUri) {
                        file.copy(isSelectedForDelete = !file.isSelectedForDelete)
                    } else {
                        file
                    }
                }
                val updatedPreview = state.activeCompareGroup?.let { active ->
                    active.copy(
                        items = active.items.map { item ->
                            if (item.uri == itemUri) {
                                item.copy(isSelectedForDelete = !item.isSelectedForDelete)
                            } else {
                                item
                            }
                        }
                    )
                }
                return@update state.copy(
                    largestFiles = updatedLargest,
                    activeCompareGroup = updatedPreview
                )
            }

            val updatedGroups = state.groups.map { group ->
                if (group.groupId != groupId) return@map group
                val updatedItems = group.items.map { item ->
                    if (item.uri == itemUri) {
                        item.copy(isSelectedForDelete = !item.isSelectedForDelete)
                    } else {
                        item
                    }
                }
                group.copy(items = updatedItems)
            }
            state.copy(groups = updatedGroups)
        }
    }

    fun ensureKeepAtLeastOneInAllGroups() {
        _uiState.update { state ->
            val safeGroups = SmartSelectUseCase.applyRuleToGroups(
                groups = state.groups,
                rule = state.config.selectionRule,
                protectedFolderKeywords = state.protectedFolders
            )
            state.copy(
                groups = safeGroups,
                bannerMessage = "모든 그룹에서 원본 최소 1개가 안전하게 보존되도록 재설정했습니다."
            )
        }
    }

    fun requestCleanupSelected(moveToTrash: Boolean) {
        val state = _uiState.value
        val selectedItems = when (state.activeTab) {
            CleanerViewTab.DUPLICATE_GROUPS -> state.groups.flatMap { group ->
                group.items.filter { it.isSelectedForDelete }
            }
            CleanerViewTab.LARGE_FILES_HUNTER -> state.largestFiles.filter { it.isSelectedForDelete }
            CleanerViewTab.CORRUPTED_FILES -> state.corruptedFiles
                .map { it.item }
                .filter { it.isSelectedForDelete }
        }
        if (selectedItems.isEmpty()) return

        viewModelScope.launch {
            val beforeVaultIds = withContext(Dispatchers.IO) {
                quarantineVault.getQuarantinedItems().map { it.id }.toSet()
            }
            when (val outcome = repository.prepareCleanupRequest(selectedItems, moveToTrash)) {
                is CleanupRequestOutcome.CompletedDirectly -> {
                    val afterItems = withContext(Dispatchers.IO) {
                        quarantineVault.getQuarantinedItems()
                    }
                    val newlyQuarantined = afterItems.filter { it.id !in beforeVaultIds }
                    val undoIds = if (newlyQuarantined.isNotEmpty()) newlyQuarantined.map { it.id } else null
                    val undoSummary = if (newlyQuarantined.isNotEmpty()) {
                        "${newlyQuarantined.size}개 파일 (${FormatUtils.formatBytes(newlyQuarantined.sumOf { it.sizeBytes })})"
                    } else null

                    removeCleanedItemsFromState(
                        cleanedUris = selectedItems.map { it.uri }.toSet(),
                        freedBytes = outcome.freedBytes,
                        isTrashMove = moveToTrash,
                        newUndoIds = undoIds,
                        newUndoSummary = undoSummary
                    )
                    refreshEmptyFoldersInternal()
                }

                is CleanupRequestOutcome.RequiresSystemConfirmation -> {
                    val expectedBytes = selectedItems.sumOf { it.sizeBytes }
                    _uiState.update {
                        it.copy(
                            pendingConfirmation = PendingSystemConfirmation(
                                intentSender = outcome.intentSender,
                                targetUris = selectedItems.map { item -> item.uri },
                                expectedFreedBytes = expectedBytes,
                                isTrashMove = outcome.isTrashMove
                            )
                        )
                    }
                }

                is CleanupRequestOutcome.IntegrityFailed -> {
                    _uiState.update {
                        it.copy(bannerMessage = outcome.message)
                    }
                }
            }
        }
    }

    fun onSystemConfirmationResult(approved: Boolean) {
        val pending = _uiState.value.pendingConfirmation ?: return
        _uiState.update { it.copy(pendingConfirmation = null) }

        if (approved) {
            viewModelScope.launch {
                val beforeVaultIds = withContext(Dispatchers.IO) {
                    quarantineVault.getQuarantinedItems().map { it.id }.toSet()
                }
                repository.removeUrisFromCache(pending.targetUris)
                val afterItems = withContext(Dispatchers.IO) {
                    quarantineVault.getQuarantinedItems()
                }
                val newlyQuarantined = afterItems.filter { it.id !in beforeVaultIds }
                val undoIds = if (newlyQuarantined.isNotEmpty()) newlyQuarantined.map { it.id } else null
                val undoSummary = if (newlyQuarantined.isNotEmpty()) {
                    "${newlyQuarantined.size}개 파일 (${FormatUtils.formatBytes(newlyQuarantined.sumOf { it.sizeBytes })})"
                } else null

                removeCleanedItemsFromState(
                    cleanedUris = pending.targetUris.toSet(),
                    freedBytes = pending.expectedFreedBytes,
                    isTrashMove = pending.isTrashMove,
                    newUndoIds = undoIds,
                    newUndoSummary = undoSummary
                )
                refreshEmptyFoldersInternal()
            }
        } else {
            _uiState.update {
                it.copy(bannerMessage = "파일 정리 요청이 취소되었습니다.")
            }
        }
    }

    private suspend fun removeCleanedItemsFromState(
        cleanedUris: Set<Uri>,
        freedBytes: Long,
        isTrashMove: Boolean,
        newUndoIds: List<String>? = null,
        newUndoSummary: String? = null
    ) {
        val qItems = readQuarantineVaultItems()
        _uiState.update { state ->
            val sampleNames = (state.groups.flatMap { it.items } + state.largestFiles + state.corruptedFiles.map { it.item })
                .filter { it.uri in cleanedUris }
                .map { it.displayName }
                .distinct()
                .take(5)

            historyStore.recordCleanup(
                cleanedFileCount = cleanedUris.size,
                freedBytes = freedBytes,
                isTrashMove = isTrashMove,
                sampleFileNames = sampleNames
            )

            val remainingGroups = state.groups.mapNotNull { group ->
                val remainingItems = group.items.filterNot { it.uri in cleanedUris }
                if (remainingItems.size > 1) {
                    group.copy(items = remainingItems)
                } else {
                    null // 중복이 해소되어 원본 1개만 남으면 목록에서 제거
                }
            }
            val remainingLargeFiles = state.largestFiles.filterNot { it.uri in cleanedUris }
            val remainingCorruptedFiles = state.corruptedFiles.filterNot { it.item.uri in cleanedUris }
            val actionText = if (isTrashMove) "휴지통/격리 보관함 이동" else "영구 삭제"
            state.withQuarantineItems(qItems).copy(
                groups = remainingGroups,
                largestFiles = remainingLargeFiles,
                corruptedFiles = remainingCorruptedFiles,
                cumulativeFreedBytes = historyStore.getTotalLifetimeFreedBytes(),
                historySummaryText = historyStore.buildHistorySummaryText(),
                storageOverview = repository.getStorageOverview(),
                bannerMessage = "${cleanedUris.size}개 파일 $actionText 완료!",
                lastUndoQuarantineIds = newUndoIds ?: state.lastUndoQuarantineIds,
                lastUndoSummary = newUndoSummary ?: state.lastUndoSummary
            )
        }
    }

    fun undoLastQuarantine() {
        viewModelScope.launch {
            val idsToRestore = _uiState.value.lastUndoQuarantineIds
            if (idsToRestore.isEmpty()) return@launch
            val restoredCount = withContext(Dispatchers.IO) {
                var count = 0
                for (id in idsToRestore) {
                    if (quarantineVault.restoreSingleItem(id)) {
                        count++
                    }
                }
                count
            }
            val qItems = readQuarantineVaultItems()
            _uiState.update {
                it.withQuarantineItems(qItems).copy(
                    lastUndoQuarantineIds = emptyList(),
                    lastUndoSummary = null,
                    storageOverview = repository.getStorageOverview(),
                    bannerMessage = "방금 정리한 ${restoredCount}개 파일을 원래 위치로 복원했습니다."
                )
            }
            refreshEmptyFoldersInternal()
        }
    }

    fun cleanEmptyFolders() {
        viewModelScope.launch {
            val currentEmpty = _uiState.value.emptyFolders
            if (currentEmpty.isEmpty()) return@launch
            val deletedCount = withContext(Dispatchers.IO) {
                EmptyFolderCleaner.deleteEmptyFolders(currentEmpty.map { it.path })
            }
            val refreshed = scanEmptyFoldersInternal()
            _uiState.update {
                it.copy(
                    emptyFolders = refreshed,
                    bannerMessage = "빈 폴더 ${deletedCount}개를 성공적으로 정리했습니다."
                )
            }
        }
    }

    fun generateDemoSamplesAndScan() {
        viewModelScope.launch {
            val count = DemoSampleGenerator.createRealisticDuplicateSamples(getApplication())
            _uiState.update {
                it.copy(
                    bannerMessage = "테스트용 샘플 미디어 파일 ${count}개(바이트 동일 + MP4/MP3 스트림 + 좌우 반전 PNG + 크로스 볼륨 WAV 그룹)를 생성했습니다."
                )
            }
            startScan()
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            repository.clearHashCache()
            val count = repository.getCachedFileCount()
            _uiState.update {
                it.copy(
                    cachedHashCount = count,
                    bannerMessage = "증분 해시 캐시를 초기화했습니다."
                )
            }
        }
    }

    fun openFunnelDialog() {
        _uiState.update { it.copy(isFunnelDialogOpen = true) }
    }

    fun closeFunnelDialog() {
        _uiState.update { it.copy(isFunnelDialogOpen = false) }
    }

    fun openCompareSheet(group: DuplicateGroup) {
        _uiState.update { it.copy(activeCompareGroup = group) }
    }

    fun closeCompareSheet() {
        _uiState.update { it.copy(activeCompareGroup = null) }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(bannerMessage = null) }
    }
}
