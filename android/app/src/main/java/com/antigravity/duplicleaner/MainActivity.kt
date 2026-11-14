package com.antigravity.duplicleaner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.antigravity.duplicleaner.domain.model.AutoScanInterval
import com.antigravity.duplicleaner.ui.CleanerViewModel
import com.antigravity.duplicleaner.ui.screens.CleanerMainScreen
import com.antigravity.duplicleaner.ui.theme.DupliCleanerTheme

class MainActivity : ComponentActivity() {

    private val viewModel: CleanerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val uiState by viewModel.uiState.collectAsState()

            DupliCleanerTheme(themeMode = uiState.appThemeMode) {

                var hasMediaPermission by remember { mutableStateOf(checkMediaPermissions()) }
                var hasAllFilesPermission by remember { mutableStateOf(checkAllFilesPermission()) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    hasMediaPermission = checkMediaPermissions()
                    if (hasMediaPermission) {
                        viewModel.startScan()
                    }
                }

                val allFilesSettingsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    hasAllFilesPermission = checkAllFilesPermission()
                }

                val systemTrashOrDeleteLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartIntentSenderForResult()
                ) { result ->
                    viewModel.onSystemConfirmationResult(result.resultCode == Activity.RESULT_OK)
                }

                val safFolderLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { treeUri ->
                    if (treeUri != null) {
                        extractFolderSegmentFromTreeUri(treeUri)?.let { folderName ->
                            viewModel.addTargetFolder(folderName)
                        }
                    }
                }

                var pendingAutoScanInterval by remember { mutableStateOf<AutoScanInterval?>(null) }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ ->
                    pendingAutoScanInterval?.let { interval ->
                        viewModel.selectAutoScanInterval(interval)
                        pendingAutoScanInterval = null
                    }
                }

                // OS 휴지통/삭제 승인 다이얼로그(IntentSender) 트리거
                LaunchedEffect(uiState.pendingConfirmation) {
                    val pending = uiState.pendingConfirmation ?: return@LaunchedEffect
                    try {
                        val request = IntentSenderRequest.Builder(pending.intentSender).build()
                        systemTrashOrDeleteLauncher.launch(request)
                    } catch (_: Exception) {
                        viewModel.onSystemConfirmationResult(false)
                    }
                }

                CleanerMainScreen(
                    uiState = uiState,
                    hasMediaPermission = hasMediaPermission,
                    hasAllFilesPermission = hasAllFilesPermission,
                    onRequestMediaPermission = {
                        permissionLauncher.launch(requiredMediaPermissions())
                    },
                    onRequestAllFilesPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                            allFilesSettingsLauncher.launch(intent)
                        }
                    },
                    onStartScan = {
                        hasMediaPermission = checkMediaPermissions()
                        hasAllFilesPermission = checkAllFilesPermission()
                        viewModel.startScan()
                    },
                    onCancelScan = viewModel::cancelScan,
                    onSelectScopeMode = viewModel::updateScopeMode,
                    onToggleSimilarVideo = viewModel::updateIncludeSimilarVideos,
                    onSelectRule = viewModel::updateSelectionRule,
                    onToggleItemSelection = { groupId, item ->
                        viewModel.toggleItemSelection(groupId, item.uri)
                    },
                    onKeepAtLeastOneAllGroups = viewModel::ensureKeepAtLeastOneInAllGroups,
                    onRequestCleanup = viewModel::requestCleanupSelected,
                    onOpenCompare = viewModel::openCompareSheet,
                    onCloseCompare = viewModel::closeCompareSheet,
                    onGenerateDemoSamples = viewModel::generateDemoSamplesAndScan,
                    onClearCache = viewModel::clearCache,
                    onDismissBanner = viewModel::dismissBanner,
                    onToggleTargetFolder = viewModel::toggleTargetFolder,
                    onClearTargetFolders = viewModel::clearTargetFolders,
                    onPickSafFolder = { safFolderLauncher.launch(null) },
                    onSelectAutoScanInterval = { interval ->
                        if (interval != AutoScanInterval.OFF &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingAutoScanInterval = interval
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.selectAutoScanInterval(interval)
                        }
                    },
                    onOpenQuarantineSheet = viewModel::openQuarantineSheet,
                    onCloseQuarantineSheet = viewModel::closeQuarantineSheet,
                    onRestoreSingleQuarantineItem = viewModel::restoreSingleQuarantineItem,
                    onDeleteSingleQuarantineItem = viewModel::deleteSingleQuarantineItem
                )
            }
        }
    }

    private fun extractFolderSegmentFromTreeUri(uri: Uri): String? {
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            uri.lastPathSegment
        } ?: return null

        val cleaned = when {
            docId.startsWith("raw:") -> docId.removePrefix("raw:")
            docId.contains(":") -> docId.substringAfter(":")
            else -> docId
        }.trim().trim('/', '\\')

        if (cleaned.isNotEmpty()) {
            return cleaned
        }
        return uri.lastPathSegment
            ?.substringAfter(':')
            ?.trim()
            ?.trim('/', '\\')
            ?.takeIf { it.isNotEmpty() }
    }

    private fun requiredMediaPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun checkMediaPermissions(): Boolean {
        return requiredMediaPermissions().any { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAllFilesPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkMediaPermissions()
        }
    }
}
