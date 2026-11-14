package com.antigravity.duplicleaner.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.antigravity.duplicleaner.domain.model.DuplicateGroup
import com.antigravity.duplicleaner.domain.model.FileCategory
import com.antigravity.duplicleaner.domain.model.MediaFileItem
import com.antigravity.duplicleaner.util.FormatUtils
import com.antigravity.duplicleaner.util.ShareUriHelper
import com.antigravity.duplicleaner.util.ThumbnailMemoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val KEYFRAME_SAMPLE_RATIOS = listOf(0.15f, 0.50f, 0.85f)
private val KEYFRAME_LABELS = listOf("15%", "50%", "85%")
private val SUPERIOR_COLOR = Color(0xFF059669)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoCompareBottomSheet(
    group: DuplicateGroup,
    onDismiss: () -> Unit,
    onToggleSelectItem: (MediaFileItem) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var activeIndex by remember(group.groupId) { mutableIntStateOf(0) }
    val activeItem = group.items.getOrElse(activeIndex) { group.items.first() }
    val keeper = group.items.firstOrNull { !it.isSelectedForDelete } ?: group.items.first()
    val isComparingDifferentFile = activeItem != keeper

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build()
    }

    LaunchedEffect(activeItem.uri) {
        if (activeItem.category == FileCategory.VIDEO || activeItem.category == FileCategory.AUDIO) {
            exoPlayer.setMediaItem(MediaItem.fromUri(activeItem.uri))
            exoPlayer.prepare()
        } else {
            exoPlayer.stop()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    val isVideoGroup = group.category == FileCategory.VIDEO ||
        keeper.category == FileCategory.VIDEO ||
        activeItem.category == FileCategory.VIDEO

    var keeperFrames by remember(keeper.uri) { mutableStateOf<List<Bitmap?>>(emptyList()) }
    var isLoadingKeeperFrames by remember(keeper.uri) { mutableStateOf(false) }

    var activeFrames by remember(activeItem.uri) { mutableStateOf<List<Bitmap?>>(emptyList()) }
    var isLoadingActiveFrames by remember(activeItem.uri) { mutableStateOf(false) }

    LaunchedEffect(keeper.uri, keeper.category) {
        if (keeper.category == FileCategory.VIDEO) {
            isLoadingKeeperFrames = true
            keeperFrames = extractThreePointKeyframes(context, keeper)
            isLoadingKeeperFrames = false
        } else if (keeper.category == FileCategory.IMAGE) {
            isLoadingKeeperFrames = true
            val img = decodeSafePreviewBitmap(context, keeper, maxDim = 1024)
            keeperFrames = if (img != null) listOf(img) else emptyList()
            isLoadingKeeperFrames = false
        } else {
            keeperFrames = emptyList()
            isLoadingKeeperFrames = false
        }
    }

    LaunchedEffect(activeItem.uri, isComparingDifferentFile, activeItem.category) {
        if (isComparingDifferentFile) {
            if (activeItem.category == FileCategory.VIDEO) {
                isLoadingActiveFrames = true
                activeFrames = extractThreePointKeyframes(context, activeItem)
                isLoadingActiveFrames = false
            } else if (activeItem.category == FileCategory.IMAGE) {
                isLoadingActiveFrames = true
                val img = decodeSafePreviewBitmap(context, activeItem, maxDim = 1024)
                activeFrames = if (img != null) listOf(img) else emptyList()
                isLoadingActiveFrames = false
            } else {
                activeFrames = emptyList()
                isLoadingActiveFrames = false
            }
        } else {
            activeFrames = emptyList()
            isLoadingActiveFrames = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "중복 파일 정밀 비교 및 미리보기",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${group.matchType.badgeLabel} (${group.similarityPercent}% 일치) · 총 ${group.items.size}개 파일",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "닫기")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Video / Media Player Preview Area or Image Preview Area
            if (activeItem.category == FileCategory.VIDEO || activeItem.category == FileCategory.AUDIO) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = true
                            }
                        },
                        update = { view ->
                            view.player = exoPlayer
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            } else if (activeItem.category == FileCategory.IMAGE) {
                val previewImage = if (isComparingDifferentFile) {
                    activeFrames.firstOrNull() ?: keeperFrames.firstOrNull()
                } else {
                    keeperFrames.firstOrNull()
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (previewImage != null) {
                        Image(
                            bitmap = previewImage.asImageBitmap(),
                            contentDescription = activeItem.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Tab selector for files in the group
            Text(
                text = "비교할 파일 선택 (탭하여 영상 전환, 우측 버튼으로 정리 대상 토글)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            group.items.forEachIndexed { index, item ->
                val isViewing = index == activeIndex
                val isKeep = !item.isSelectedForDelete
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .border(
                            width = if (isViewing) 2.dp else 1.dp,
                            color = if (isViewing) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { activeIndex = index },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isViewing) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VideoThumbnailBox(item = item)

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = if (isKeep) SUPERIOR_COLOR else MaterialTheme.colorScheme.error,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (isKeep) "원본 보존" else "정리 선택됨",
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
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${item.relativePath} · ${FormatUtils.formatBytes(item.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(onClick = { onToggleSelectItem(item) }) {
                            Icon(
                                imageVector = if (isKeep) Icons.Default.CheckCircle else Icons.Default.DeleteOutline,
                                contentDescription = "선택 전환",
                                tint = if (isKeep) SUPERIOR_COLOR else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            val keeperFirstFrame = keeperFrames.firstOrNull()
            val activeFirstFrame = if (isComparingDifferentFile) activeFrames.firstOrNull() else keeperFirstFrame

            val keeperSharpness = remember(keeperFirstFrame) {
                keeperFirstFrame?.let { computeFrameSharpness(it) } ?: -1
            }
            val activeSharpness = remember(activeFirstFrame) {
                activeFirstFrame?.let { computeFrameSharpness(it) } ?: -1
            }

            // 1:1 원본(Keep) vs 현재 선택 파일 스펙 비교 카드
            SideBySideSpecComparisonCard(
                keeper = keeper,
                activeItem = activeItem,
                isComparingDifferentFile = isComparingDifferentFile,
                keeperSharpness = keeperSharpness,
                activeSharpness = activeSharpness
            )

            // Interactive A/B Split-Screen Wipe Comparison View
            val activeComparedFrame = activeFrames.firstOrNull()
            if (isComparingDifferentFile && keeperFirstFrame != null && activeComparedFrame != null) {
                Spacer(modifier = Modifier.height(12.dp))
                InteractiveSplitWipeCard(
                    keeperBitmap = keeperFirstFrame,
                    activeBitmap = activeComparedFrame
                )
            }

            // 3-Point 대표 키프레임 시각적 비교 스트립 (15% · 50% · 85%)
            if (isVideoGroup) {
                Spacer(modifier = Modifier.height(12.dp))
                ThreePointKeyframeComparisonStrip(
                    keeper = keeper,
                    activeItem = activeItem,
                    isComparingDifferentFile = isComparingDifferentFile,
                    keeperFrames = keeperFrames,
                    isLoadingKeeperFrames = isLoadingKeeperFrames,
                    activeFrames = activeFrames,
                    isLoadingActiveFrames = isLoadingActiveFrames
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // Fingerprint & Metadata Comparison Table
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Verified,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "선택된 파일 해시 & 메타데이터 상세",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            MetadataRow("파일 이름", activeItem.displayName)
            MetadataRow("폴더 경로", activeItem.relativePath)
            MetadataRow("정확한 크기", "${FormatUtils.formatBytes(activeItem.sizeBytes)} (${activeItem.sizeBytes} bytes)")
            activeItem.resolutionLabel?.let { MetadataRow("해상도", it) }
            val displayBitrate = if (activeItem.actualBitrateKbps > 0) activeItem.actualBitrateKbps else activeItem.estimatedBitrateKbps
            if (displayBitrate > 0) {
                val bitrateLabel = if (activeItem.actualBitrateKbps > 0) "실제 비트레이트" else "추정 비트레이트"
                MetadataRow(bitrateLabel, "$displayBitrate kbps")
            }
            activeItem.videoCodec?.let { MetadataRow("비디오 코덱", it) }
            if (activeItem.category == FileCategory.VIDEO || activeItem.category == FileCategory.AUDIO) {
                MetadataRow("오디오 트랙", if (activeItem.hasAudioTrack) "오디오 있음" else "무음 (오디오 없음)")
            }
            activeItem.formattedDuration?.let { MetadataRow("재생 시간", it) }
            MetadataRow("마지막 수정일", FormatUtils.formatDate(activeItem.lastModifiedSeconds))
            activeItem.partialHash?.let {
                MetadataRow("48KB 부분 해시 (XXH64)", "0x${it.toULong().toString(16).uppercase()}", mono = true)
            }
            activeItem.payloadHash?.let {
                MetadataRow("MP4 스트림 해시 (mdat)", "0x${it.toULong().toString(16).uppercase()}", mono = true)
            }
            activeItem.fullHash?.let {
                MetadataRow("전체 무결성 해시", it, mono = true)
            }
            if (activeItem.perceptualHashes.isNotEmpty()) {
                MetadataRow(
                    "키프레임 dHash",
                    activeItem.perceptualHashes.joinToString(" · ") {
                        it.toULong().toString(16).take(8).uppercase()
                    },
                    mono = true
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Bottom Action Buttons: 경로 복사 & 외부 앱으로 열기
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val pathToCopy = activeItem.absolutePath ?: activeItem.relativePath
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("file_path", pathToCopy))
                        Toast.makeText(context, "경로가 복사되었습니다: $pathToCopy", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "경로 복사",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = {
                        val safeUri = ShareUriHelper.toViewableUri(context, activeItem.uri, activeItem.absolutePath)
                        val intent = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(safeUri, activeItem.mimeType)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(
                                context,
                                "이 파일을 열 수 있는 외부 앱을 찾지 못했습니다.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "외부 앱으로 열기",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SideBySideSpecComparisonCard(
    keeper: MediaFileItem,
    activeItem: MediaFileItem,
    isComparingDifferentFile: Boolean,
    keeperSharpness: Int = -1,
    activeSharpness: Int = -1
) {
    val keeperPixels = keeper.width.toLong() * keeper.height.toLong()
    val activePixels = activeItem.width.toLong() * activeItem.height.toLong()
    val keeperBetterRes = isComparingDifferentFile && keeperPixels > activePixels
    val activeBetterRes = isComparingDifferentFile && activePixels > keeperPixels

    val keeperBitrate = if (keeper.actualBitrateKbps > 0) keeper.actualBitrateKbps else keeper.estimatedBitrateKbps
    val activeBitrate = if (activeItem.actualBitrateKbps > 0) activeItem.actualBitrateKbps else activeItem.estimatedBitrateKbps
    val keeperBetterBitrate = isComparingDifferentFile && keeperBitrate > activeBitrate
    val activeBetterBitrate = isComparingDifferentFile && activeBitrate > keeperBitrate

    val keeperLargerSize = isComparingDifferentFile && keeper.sizeBytes > activeItem.sizeBytes
    val activeLargerSize = isComparingDifferentFile && activeItem.sizeBytes > keeper.sizeBytes

    val bothValidDates = keeper.lastModifiedSeconds > 0L && activeItem.lastModifiedSeconds > 0L
    val keeperOlderDate = isComparingDifferentFile && bothValidDates &&
        keeper.lastModifiedSeconds < activeItem.lastModifiedSeconds
    val activeOlderDate = isComparingDifferentFile && bothValidDates &&
        activeItem.lastModifiedSeconds < keeper.lastModifiedSeconds

    val keeperFolderScore = folderTrustRank(keeper.relativePath)
    val activeFolderScore = folderTrustRank(activeItem.relativePath)
    val keeperBetterFolder = isComparingDifferentFile && keeperFolderScore > activeFolderScore
    val activeBetterFolder = isComparingDifferentFile && activeFolderScore > keeperFolderScore

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Compare,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "1:1 원본(Keep) vs 현재 선택 스펙 비교",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (!isComparingDifferentFile) {
                Text(
                    text = "현재 원본(Keep) 파일을 보고 있습니다. 위 목록에서 다른 사본을 탭하면 1:1 우위 비교가 표시됩니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "비교 항목",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(68.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "원본 (Keep)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SUPERIOR_COLOR
                    )
                    Text(
                        text = keeper.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isComparingDifferentFile) "현재 선택" else "현재 선택 (원본)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = activeItem.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            SpecCompareRow(
                label = "해상도",
                keeperValue = keeper.resolutionLabel ?: "-",
                activeValue = activeItem.resolutionLabel ?: "-",
                isKeeperSuperior = keeperBetterRes,
                isActiveSuperior = activeBetterRes
            )
            if (keeperSharpness >= 0 || activeSharpness >= 0) {
                val keeperBetterSharpness = isComparingDifferentFile &&
                    keeperSharpness > activeSharpness && keeperSharpness >= 0
                val activeBetterSharpness = isComparingDifferentFile &&
                    activeSharpness > keeperSharpness && activeSharpness >= 0
                SpecCompareRow(
                    label = "초점 선명도",
                    keeperValue = if (keeperSharpness >= 0) "$keeperSharpness 점" else "-",
                    activeValue = if (activeSharpness >= 0) "$activeSharpness 점" else "-",
                    isKeeperSuperior = keeperBetterSharpness,
                    isActiveSuperior = activeBetterSharpness
                )
            }
            val isBothImages = keeper.category == FileCategory.IMAGE && activeItem.category == FileCategory.IMAGE
            if (!isBothImages) {
                val keeperCodecAudio = "${keeper.videoCodec ?: "-"} · ${if (keeper.hasAudioTrack) "오디오 있음" else "무음"}"
                val activeCodecAudio = "${activeItem.videoCodec ?: "-"} · ${if (activeItem.hasAudioTrack) "오디오 있음" else "무음"}"
                val keeperBetterAudio = isComparingDifferentFile && keeper.hasAudioTrack && !activeItem.hasAudioTrack
                val activeBetterAudio = isComparingDifferentFile && activeItem.hasAudioTrack && !keeper.hasAudioTrack
                SpecCompareRow(
                    label = "코덱 · 오디오",
                    keeperValue = keeperCodecAudio,
                    activeValue = activeCodecAudio,
                    isKeeperSuperior = keeperBetterAudio,
                    isActiveSuperior = activeBetterAudio
                )
                SpecCompareRow(
                    label = "비트레이트",
                    keeperValue = "$keeperBitrate kbps",
                    activeValue = "$activeBitrate kbps",
                    isKeeperSuperior = keeperBetterBitrate,
                    isActiveSuperior = activeBetterBitrate
                )
            }
            SpecCompareRow(
                label = "파일 크기",
                keeperValue = FormatUtils.formatBytes(keeper.sizeBytes),
                activeValue = FormatUtils.formatBytes(activeItem.sizeBytes),
                isKeeperSuperior = keeperLargerSize,
                isActiveSuperior = activeLargerSize
            )
            SpecCompareRow(
                label = "수정일",
                keeperValue = FormatUtils.formatDate(keeper.lastModifiedSeconds),
                activeValue = FormatUtils.formatDate(activeItem.lastModifiedSeconds),
                isKeeperSuperior = keeperOlderDate,
                isActiveSuperior = activeOlderDate
            )
            SpecCompareRow(
                label = "폴더",
                keeperValue = keeper.relativePath.ifBlank { "/" },
                activeValue = activeItem.relativePath.ifBlank { "/" },
                isKeeperSuperior = keeperBetterFolder,
                isActiveSuperior = activeBetterFolder
            )
        }
    }
}

@Composable
private fun SpecCompareRow(
    label: String,
    keeperValue: String,
    activeValue: String,
    isKeeperSuperior: Boolean,
    isActiveSuperior: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(68.dp)
        )
        SpecValueCell(
            value = keeperValue,
            isSuperior = isKeeperSuperior,
            modifier = Modifier.weight(1f)
        )
        SpecValueCell(
            value = activeValue,
            isSuperior = isActiveSuperior,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SpecValueCell(
    value: String,
    isSuperior: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (isSuperior) {
            SUPERIOR_COLOR.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
        }
    ) {
        Text(
            text = if (isSuperior) "★ $value" else value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSuperior) FontWeight.Bold else FontWeight.Normal,
            color = if (isSuperior) SUPERIOR_COLOR else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun InteractiveSplitWipeCard(
    keeperBitmap: Bitmap,
    activeBitmap: Bitmap,
    modifier: Modifier = Modifier
) {
    var splitFraction by remember { mutableFloatStateOf(0.5f) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Compare,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "A/B 실시간 스플릿 와이프(Wipe) 비교",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "슬라이더를 좌우로 드래그하여 원본과 비교 영상의 디테일을 동일 위치에서 1:1 비교하세요.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                // Bottom: Active item frame
                Image(
                    bitmap = activeBitmap.asImageBitmap(),
                    contentDescription = "비교 대상 프레임",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Top: Keeper frame clipped on the left
                Image(
                    bitmap = keeperBitmap.asImageBitmap(),
                    contentDescription = "원본 프레임",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            clipRect(right = size.width * splitFraction) {
                                this@drawWithContent.drawContent()
                            }
                        }
                )

                // Vertical white divider line at splitFraction
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val lineX = size.width * splitFraction
                    drawLine(
                        color = Color.White,
                        start = Offset(lineX, 0f),
                        end = Offset(lineX, size.height),
                        strokeWidth = 2.dp.toPx()
                    )
                }

                // Badges
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "◀ 원본 (Keep)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "비교 대상 (Selected) ▶",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            Slider(
                value = splitFraction,
                onValueChange = { splitFraction = it },
                valueRange = 0.05f..0.95f,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ThreePointKeyframeComparisonStrip(
    keeper: MediaFileItem,
    activeItem: MediaFileItem,
    isComparingDifferentFile: Boolean,
    keeperFrames: List<Bitmap?>,
    isLoadingKeeperFrames: Boolean,
    activeFrames: List<Bitmap?>,
    isLoadingActiveFrames: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "3-Point 대표 키프레임 비교 (15% · 50% · 85%)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            KeyframeRowSection(
                badgeText = "원본 (Keep)",
                badgeColor = SUPERIOR_COLOR,
                fileName = keeper.displayName,
                frames = keeperFrames,
                isLoading = isLoadingKeeperFrames
            )

            if (isComparingDifferentFile) {
                KeyframeRowSection(
                    badgeText = "현재 선택",
                    badgeColor = MaterialTheme.colorScheme.primary,
                    fileName = activeItem.displayName,
                    frames = activeFrames,
                    isLoading = isLoadingActiveFrames
                )
            }
        }
    }
}

@Composable
private fun KeyframeRowSection(
    badgeText: String,
    badgeColor: Color,
    fileName: String,
    frames: List<Bitmap?>,
    isLoading: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = badgeColor,
                shape = RoundedCornerShape(5.dp)
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = fileName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            KEYFRAME_LABELS.forEachIndexed { index, positionLabel ->
                val frame = frames.getOrNull(index)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        frame != null -> {
                            Image(
                                bitmap = frame.asImageBitmap(),
                                contentDescription = "$fileName $positionLabel 키프레임",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        isLoading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Default.Movie,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                    ) {
                        Text(
                            text = positionLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 2D Laplacian sharpness score estimator based on variance of discrete Laplacian.
 * Higher variance indicates sharper edges and richer high-frequency details.
 */
fun computeFrameSharpness(bitmap: Bitmap): Int {
    var safeBitmap: Bitmap? = null
    try {
        val workingBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.HARDWARE
        ) {
            safeBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            safeBitmap ?: return 0
        } else {
            bitmap
        }

        val width = workingBitmap.width
        val height = workingBitmap.height
        if (width < 3 || height < 3) return 0

        val step = if (width > 320 || height > 320) 2 else 1
        val pixels = IntArray(width * height)
        workingBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var sumLaplacian = 0.0
        var sumLaplacianSq = 0.0
        var count = 0

        for (y in 1 until height - 1 step step) {
            val yOffset = y * width
            for (x in 1 until width - 1 step step) {
                val idx = yOffset + x

                val center = ((pixels[idx] ushr 16 and 0xFF) * 77 +
                    (pixels[idx] ushr 8 and 0xFF) * 151 +
                    (pixels[idx] and 0xFF) * 28) shr 8
                val up = ((pixels[idx - width] ushr 16 and 0xFF) * 77 +
                    (pixels[idx - width] ushr 8 and 0xFF) * 151 +
                    (pixels[idx - width] and 0xFF) * 28) shr 8
                val down = ((pixels[idx + width] ushr 16 and 0xFF) * 77 +
                    (pixels[idx + width] ushr 8 and 0xFF) * 151 +
                    (pixels[idx + width] and 0xFF) * 28) shr 8
                val left = ((pixels[idx - 1] ushr 16 and 0xFF) * 77 +
                    (pixels[idx - 1] ushr 8 and 0xFF) * 151 +
                    (pixels[idx - 1] and 0xFF) * 28) shr 8
                val right = ((pixels[idx + 1] ushr 16 and 0xFF) * 77 +
                    (pixels[idx + 1] ushr 8 and 0xFF) * 151 +
                    (pixels[idx + 1] and 0xFF) * 28) shr 8

                val lap = (up + down + left + right) - (4 * center)
                sumLaplacian += lap
                sumLaplacianSq += (lap * lap).toDouble()
                count++
            }
        }

        if (count == 0) return 0
        val mean = sumLaplacian / count
        val variance = (sumLaplacianSq / count) - (mean * mean)
        return variance.coerceAtLeast(0.0).toInt()
    } catch (_: Exception) {
        return 0
    } finally {
        safeBitmap?.recycle()
    }
}

private fun folderTrustRank(relativePath: String): Int {
    val normalized = relativePath.replace('\\', '/').lowercase()
    return when {
        normalized.contains("dcim/camera") -> 3
        normalized.contains("movies") || normalized.contains("pictures") -> 2
        normalized.contains("kakaotalk") || normalized.contains("telegram") ||
            normalized.contains("download") || normalized.contains("temp") -> 0
        else -> 1
    }
}

private suspend fun extractThreePointKeyframes(
    context: Context,
    item: MediaFileItem
): List<Bitmap?> = withContext(Dispatchers.IO) {
    if (item.category != FileCategory.VIDEO) return@withContext emptyList()
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, item.uri)
        val durationMs = if (item.durationMs > 0L) {
            item.durationMs
        } else {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        }
        if (durationMs <= 0L) return@withContext listOf(null, null, null)

        KEYFRAME_SAMPLE_RATIOS.map { ratio ->
            val timeUs = (durationMs * ratio * 1000L).toLong().coerceAtLeast(0L)
            val rawFrame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    320,
                    180
                )
            } else {
                retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            }
            scaleThumbnailSafely(rawFrame, maxDimension = 320)
        }
    } catch (_: Exception) {
        listOf(null, null, null)
    } finally {
        try {
            retriever.release()
        } catch (_: Exception) {
        }
    }
}

private fun scaleThumbnailSafely(source: Bitmap?, maxDimension: Int): Bitmap? {
    if (source == null) return null
    val w = source.width
    val h = source.height
    if (w <= maxDimension && h <= maxDimension) return source
    val scale = maxDimension.toFloat() / maxOf(w, h).toFloat()
    val targetW = (w * scale).toInt().coerceAtLeast(1)
    val targetH = (h * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(source, targetW, targetH, true)
    if (scaled !== source) {
        source.recycle()
    }
    return scaled
}

@Composable
private fun MetadataRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}

suspend fun decodeSafeThumbnailBitmap(
    context: Context,
    item: MediaFileItem,
    reqWidth: Int = 256,
    reqHeight: Int = 256
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val file = item.absolutePath?.let { File(it) }
        val hasFile = file != null && file.exists()

        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        if (hasFile) {
            BitmapFactory.decodeFile(file!!.absolutePath, boundsOptions)
        } else {
            context.contentResolver.openInputStream(item.uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
        }

        val rawWidth = boundsOptions.outWidth
        val rawHeight = boundsOptions.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) return@withContext null

        val sampleSize = ThumbnailMemoryCache.computeSafeInSampleSize(
            rawWidth,
            rawHeight,
            reqWidth,
            reqHeight
        )

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = if (hasFile) {
            BitmapFactory.decodeFile(file!!.absolutePath, decodeOptions)
        } else {
            context.contentResolver.openInputStream(item.uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        } ?: return@withContext null

        scaleThumbnailSafely(decoded, maxDimension = maxOf(reqWidth, reqHeight))
    } catch (_: Throwable) {
        null
    }
}

suspend fun decodeSafePreviewBitmap(
    context: Context,
    item: MediaFileItem,
    maxDim: Int = 1024
): Bitmap? = withContext(Dispatchers.IO) {
    val cacheKey = "preview_${maxDim}_${item.uri}"
    val cached = ThumbnailMemoryCache.get(cacheKey)
    if (cached != null) return@withContext cached

    val bitmap = decodeSafeThumbnailBitmap(context, item, reqWidth = maxDim, reqHeight = maxDim)
    if (bitmap != null) {
        ThumbnailMemoryCache.put(cacheKey, bitmap)
    }
    bitmap
}

@Composable
fun VideoThumbnailBox(item: MediaFileItem) {
    val context = LocalContext.current
    val cacheKey = "thumb_${item.uri}"
    var bitmap by remember(item.uri) {
        mutableStateOf(ThumbnailMemoryCache.get(cacheKey))
    }

    LaunchedEffect(item.uri, item.category) {
        if (bitmap != null) return@LaunchedEffect

        when (item.category) {
            FileCategory.VIDEO -> {
                val loaded = withContext(Dispatchers.IO) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, item.uri)
                        val rawFrame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            retriever.getScaledFrameAtTime(
                                1_000_000L,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                256,
                                256
                            )
                        } else {
                            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                        scaleThumbnailSafely(rawFrame, maxDimension = 256)
                    } catch (_: Exception) {
                        null
                    } finally {
                        try {
                            retriever.release()
                        } catch (_: Exception) {
                        }
                    }
                }
                if (loaded != null) {
                    ThumbnailMemoryCache.put(cacheKey, loaded)
                    bitmap = loaded
                }
            }
            FileCategory.IMAGE -> {
                val loaded = decodeSafeThumbnailBitmap(context, item, reqWidth = 256, reqHeight = 256)
                if (loaded != null) {
                    ThumbnailMemoryCache.put(cacheKey, loaded)
                    bitmap = loaded
                }
            }
            FileCategory.AUDIO -> {
                // Audio badge is rendered directly
            }
            else -> Unit
        }
    }

    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val currentBitmap = bitmap
        when (item.category) {
            FileCategory.VIDEO -> {
                if (currentBitmap != null) {
                    Image(
                        bitmap = currentBitmap.asImageBitmap(),
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
                }
                Icon(
                    imageVector = Icons.Default.PlayCircleFilled,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(24.dp)
                )
            }
            FileCategory.IMAGE -> {
                if (currentBitmap != null) {
                    Image(
                        bitmap = currentBitmap.asImageBitmap(),
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            FileCategory.AUDIO -> {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            else -> {
                if (currentBitmap != null) {
                    Image(
                        bitmap = currentBitmap.asImageBitmap(),
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
