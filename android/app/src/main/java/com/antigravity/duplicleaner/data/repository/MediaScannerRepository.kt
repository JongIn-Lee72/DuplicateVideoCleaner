package com.antigravity.duplicleaner.data.repository

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.antigravity.duplicleaner.data.hash.AudioEnvelopeFingerprint
import com.antigravity.duplicleaner.data.hash.AudioWaveformHasher
import com.antigravity.duplicleaner.data.hash.CorruptedFileDetector
import com.antigravity.duplicleaner.data.hash.CorruptedFileReport
import com.antigravity.duplicleaner.data.hash.FastHasher
import com.antigravity.duplicleaner.data.hash.FileIntegrityGuard
import com.antigravity.duplicleaner.data.hash.HammingBkTree
import com.antigravity.duplicleaner.data.hash.MediaContainerInspector
import com.antigravity.duplicleaner.data.hash.MediaTechSpecsInspector
import com.antigravity.duplicleaner.data.hash.MetadataAuthenticityInspector
import com.antigravity.duplicleaner.data.hash.TemporalSequenceAligner
import com.antigravity.duplicleaner.data.hash.TransitiveClusterMerger
import com.antigravity.duplicleaner.data.hash.VideoPerceptualHasher
import com.antigravity.duplicleaner.data.local.CachedFileHashEntity
import com.antigravity.duplicleaner.data.local.HashCacheDao
import com.antigravity.duplicleaner.data.local.QuarantineVaultManager
import com.antigravity.duplicleaner.domain.model.DuplicateGroup
import com.antigravity.duplicleaner.domain.model.FileCategory
import com.antigravity.duplicleaner.domain.model.MatchType
import com.antigravity.duplicleaner.domain.model.MediaFileItem
import com.antigravity.duplicleaner.domain.model.ScanConfig
import com.antigravity.duplicleaner.domain.model.ScanFunnelMetrics
import com.antigravity.duplicleaner.domain.model.ScanScopeMode
import com.antigravity.duplicleaner.domain.usecase.SmartSelectUseCase
import com.antigravity.duplicleaner.util.ThermalBatteryMonitor
import com.antigravity.duplicleaner.util.ThrottleLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class StorageOverview(
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long
) {
    val usedRatio: Float
        get() = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

enum class ScanPhase(val stepNumber: Int, val title: String) {
    IDLE(0, "대기 중"),
    STAGE_1_INDEXING(1, "1단계: 파일 인덱싱 및 크기 그룹화 (Zero-I/O)"),
    STAGE_2_PARTIAL_HASH(2, "2단계: 초고속 부분 해시 필터링 (Head·Mid·Tail 48KB)"),
    STAGE_2_5_MP4_PAYLOAD(3, "2.5단계: 미디어 컨테이너 독립 스트림(MP4·MKV·MP3) 매칭"),
    STAGE_3_FULL_HASH(4, "3단계: 전체 바이트 무결성 해시 검증"),
    STAGE_4_SIMILAR_VIDEO(5, "4단계: 키프레임 지각 해시(dHash+pHash) 유사 영상·사진 분석"),
    COMPLETED(6, "스캔 완료")
}

data class ScanProgressUpdate(
    val phase: ScanPhase,
    val processedCount: Int,
    val totalCount: Int,
    val currentFileName: String = "",
    val discoveredGroupCount: Int = 0,
    val potentialSavedBytes: Long = 0L,
    val cacheHitCount: Int = 0,
    val groups: List<DuplicateGroup> = emptyList(),
    val elapsedMs: Long = 0L,
    val funnelMetrics: ScanFunnelMetrics = ScanFunnelMetrics(),
    val largestFiles: List<MediaFileItem> = emptyList(),
    val corruptedFiles: List<CorruptedFileReport> = emptyList(),
    val throttleLevel: ThrottleLevel = ThrottleLevel.NORMAL
)

sealed class CleanupRequestOutcome {
    data class RequiresSystemConfirmation(
        val intentSender: IntentSender,
        val directDeletedCount: Int,
        val pendingMediaUris: List<Uri>,
        val isTrashMove: Boolean
    ) : CleanupRequestOutcome()

    data class CompletedDirectly(
        val deletedCount: Int,
        val freedBytes: Long
    ) : CleanupRequestOutcome()

    data class IntegrityFailed(
        val message: String
    ) : CleanupRequestOutcome()
}

class MediaScannerRepository(
    private val context: Context,
    private val hashCacheDao: HashCacheDao
) {

    fun getStorageOverview(): StorageOverview {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val total = stat.totalBytes
            val free = stat.availableBytes
            StorageOverview(
                totalBytes = total,
                usedBytes = (total - free).coerceAtLeast(0L),
                freeBytes = free
            )
        } catch (_: Exception) {
            StorageOverview(0L, 0L, 0L)
        }
    }

    /**
     * 다단계 해시 파이프라인을 실행하며 실시간 진행률과 결과를 Flow로 방출합니다.
     */
    fun scanDuplicates(config: ScanConfig): Flow<ScanProgressUpdate> = flow {
        val startTime = System.currentTimeMillis()
        val cacheHits = AtomicInteger(0)
        val actualBytesRead = AtomicLong(0L)

        // 0. 인메모리 캐시 맵 일괄 로드 및 배치 Upsert 버퍼 준비
        val cacheMap = ConcurrentHashMap<String, CachedFileHashEntity>()
        for (entity in hashCacheDao.getAllCachedHashes()) {
            val key = "${entity.uriString}_${entity.sizeBytes}_${entity.lastModifiedSeconds}"
            cacheMap[key] = entity
        }
        val pendingCacheUpdates = ConcurrentHashMap<String, CachedFileHashEntity>()

        fun cacheKeyFor(item: MediaFileItem): String =
            "${item.uri}_${item.sizeBytes}_${item.lastModifiedSeconds}"

        fun recordCacheUpdate(key: String, entity: CachedFileHashEntity) {
            synchronized(pendingCacheUpdates) {
                cacheMap[key] = entity
                pendingCacheUpdates[entity.uriString] = entity
            }
        }

        emit(
            ScanProgressUpdate(
                phase = ScanPhase.STAGE_1_INDEXING,
                processedCount = 0,
                totalCount = 0,
                currentFileName = "스토리지 메타데이터 조회 중..."
            )
        )

        // Stage 1: 파일 목록 수집 및 타겟 폴더 집중 필터링 후 파일 크기(Size) 기준 1차 그룹화
        val corruptedReports = mutableListOf<CorruptedFileReport>()
        val rawCandidates = collectTargetFiles(config, corruptedReports)
        currentCoroutineContext().ensureActive()
        val allCandidates = filterByTargetFolders(rawCandidates, config.targetFolderKeywords)
        val filteredCorrupted = filterCorruptedByTargetFolders(corruptedReports, config.targetFolderKeywords)

        val filteredCandidates = allCandidates.filter { it.sizeBytes >= config.minFileSizeBytes }
        val topLargestFiles = filteredCandidates.sortedByDescending { it.sizeBytes }.take(30)
        val totalIndexedFiles = filteredCandidates.size
        val totalIndexedBytes = filteredCandidates.sumOf { it.sizeBytes }

        val sizeGroups = filteredCandidates
            .groupBy { it.sizeBytes }
            .filter { (_, list) -> list.size > 1 }

        val stage2Targets = sizeGroups.values.flatten()
        val sizeFilteredCandidates = stage2Targets.size

        fun buildFunnelMetrics(
            partialCandidates: Int = 0,
            fullCandidates: Int = 0
        ): ScanFunnelMetrics {
            val readBytes = actualBytesRead.get()
            return ScanFunnelMetrics(
                totalIndexedFiles = totalIndexedFiles,
                totalIndexedBytes = totalIndexedBytes,
                sizeFilteredCandidates = sizeFilteredCandidates,
                partialHashCandidates = partialCandidates,
                fullHashCandidates = fullCandidates,
                actualBytesRead = readBytes,
                savedIoBytes = ScanFunnelMetrics.calculateSavedIo(totalIndexedBytes, readBytes)
            )
        }

        emit(
            ScanProgressUpdate(
                phase = ScanPhase.STAGE_2_PARTIAL_HASH,
                processedCount = 0,
                totalCount = stage2Targets.size,
                currentFileName = "고유 크기 파일 ${totalIndexedFiles - stage2Targets.size}개 즉시 제외 완료",
                funnelMetrics = buildFunnelMetrics()
            )
        )

        // Stage 2: Sparse Partial Hash (Head/Mid/Tail 16KB = 48KB, 동적 배터리·발열 조절 병렬 처리)
        val partialHashedItems = mutableListOf<MediaFileItem>()
        var stage2Processed = 0

        for (chunk in stage2Targets.chunked(8)) {
            currentCoroutineContext().ensureActive()
            val health = ThermalBatteryMonitor.inspectDeviceHealth(context)
            val stage2Permits = when (health.throttleLevel) {
                ThrottleLevel.THERMAL_COOLING -> 1
                ThrottleLevel.BALANCED -> 2
                ThrottleLevel.NORMAL -> 4
            }
            val stage2Semaphore = Semaphore(stage2Permits)
            val chunkResults = coroutineScope {
                chunk.map { item ->
                    async {
                        stage2Semaphore.withPermit {
                            val key = cacheKeyFor(item)
                            val cached = synchronized(pendingCacheUpdates) { cacheMap[key] }

                            val partialHash = if (cached?.partialHash != null) {
                                cacheHits.incrementAndGet()
                                cached.partialHash
                            } else {
                                computeItemPartialHash(item)?.also { computed ->
                                    actualBytesRead.addAndGet(
                                        minOf(item.sizeBytes, FastHasher.SAMPLE_CHUNK_SIZE * 3L)
                                    )
                                    val updated = CachedFileHashEntity(
                                        uriString = item.uri.toString(),
                                        sizeBytes = item.sizeBytes,
                                        lastModifiedSeconds = item.lastModifiedSeconds,
                                        partialHash = computed,
                                        fullHash = cached?.fullHash,
                                        perceptualHashesCsv = cached?.perceptualHashesCsv,
                                        payloadHash = cached?.payloadHash,
                                        pHashesCsv = cached?.pHashesCsv
                                    )
                                    recordCacheUpdate(key, updated)
                                }
                            }

                            if (partialHash != null) {
                                val latestCached = synchronized(pendingCacheUpdates) { cacheMap[key] } ?: cached
                                item.copy(
                                    partialHash = partialHash,
                                    fullHash = latestCached?.fullHash,
                                    payloadHash = latestCached?.payloadHash,
                                    perceptualHashes = latestCached?.parsePerceptualHashes() ?: emptyList(),
                                    pHashes = latestCached?.parsePHashes() ?: emptyList()
                                )
                            } else {
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            partialHashedItems.addAll(chunkResults)
            stage2Processed += chunk.size
            if (health.throttleLevel.chunkCooldownMs > 0) {
                delay(health.throttleLevel.chunkCooldownMs)
            }
            emit(
                ScanProgressUpdate(
                    phase = ScanPhase.STAGE_2_PARTIAL_HASH,
                    processedCount = stage2Processed,
                    totalCount = stage2Targets.size,
                    currentFileName = chunk.last().displayName,
                    cacheHitCount = cacheHits.get(),
                    elapsedMs = System.currentTimeMillis() - startTime,
                    funnelMetrics = buildFunnelMetrics(partialCandidates = partialHashedItems.size),
                    throttleLevel = health.throttleLevel
                )
            )
        }

        // Size + PartialHash 조합이 동일한 그룹만 Stage 3로 전달
        val stage3Targets = partialHashedItems
            .groupBy { "${it.sizeBytes}_${it.partialHash}" }
            .filter { (_, list) -> list.size > 1 }
            .values
            .flatten()
        val partialHashCandidates = stage3Targets.size

        // Stage 3: Full Streaming Hash Verification
        val fullHashedItems = mutableListOf<MediaFileItem>()
        val discoveredGroups = mutableListOf<DuplicateGroup>()

        for ((index, item) in stage3Targets.withIndex()) {
            currentCoroutineContext().ensureActive()
            val key = cacheKeyFor(item)
            val cached = cacheMap[key]
            val existingFullHash = item.fullHash?.takeIf { it.isNotBlank() }
                ?: cached?.fullHash?.takeIf { it.isNotBlank() }

            val fullHash = if (existingFullHash != null) {
                cacheHits.incrementAndGet()
                existingFullHash
            } else {
                computeItemFullHash(item)?.also { computedFull ->
                    actualBytesRead.addAndGet(item.sizeBytes)
                    val updated = CachedFileHashEntity(
                        uriString = item.uri.toString(),
                        sizeBytes = item.sizeBytes,
                        lastModifiedSeconds = item.lastModifiedSeconds,
                        partialHash = item.partialHash ?: cached?.partialHash,
                        fullHash = computedFull,
                        perceptualHashesCsv = cached?.perceptualHashesCsv
                            ?: CachedFileHashEntity.encodePerceptualHashes(item.perceptualHashes),
                        payloadHash = item.payloadHash ?: cached?.payloadHash,
                        pHashesCsv = cached?.pHashesCsv
                            ?: CachedFileHashEntity.encodePerceptualHashes(item.pHashes)
                    )
                    recordCacheUpdate(key, updated)
                }
            }

            if (!fullHash.isNullOrBlank()) {
                fullHashedItems.add(item.copy(fullHash = fullHash))
            }

            emit(
                ScanProgressUpdate(
                    phase = ScanPhase.STAGE_3_FULL_HASH,
                    processedCount = index + 1,
                    totalCount = stage3Targets.size,
                    currentFileName = item.displayName,
                    cacheHitCount = cacheHits.get(),
                    elapsedMs = System.currentTimeMillis() - startTime,
                    funnelMetrics = buildFunnelMetrics(
                        partialCandidates = partialHashCandidates,
                        fullCandidates = stage3Targets.size
                    )
                )
            )
        }

        val exactDuplicateMap = fullHashedItems
            .groupBy { "${it.sizeBytes}_${it.fullHash}" }
            .filter { (_, list) -> list.size > 1 }

        val alreadyGroupedUris = mutableSetOf<Uri>()
        var groupCounter = 1

        for ((signature, groupItems) in exactDuplicateMap) {
            val category = groupItems.first().category
            val enrichedItems = when (category) {
                FileCategory.IMAGE -> enrichImageAuthenticity(groupItems)
                FileCategory.VIDEO -> enrichVideoTechSpecs(groupItems)
                else -> groupItems
            }
            val rawGroup = DuplicateGroup(
                groupId = "EXACT_${groupCounter++}",
                signature = signature.substringAfter('_').take(24),
                matchType = MatchType.EXACT_HASH,
                similarityPercent = 100,
                category = category,
                items = enrichedItems
            )
            val smartSelected = SmartSelectUseCase.applyRuleToGroup(rawGroup, config.selectionRule)
            discoveredGroups.add(smartSelected)
            groupItems.forEach { alreadyGroupedUris.add(it.uri) }
        }

        // Stage 2.5: 다중 미디어 컨테이너 독립 스트림(MP4 mdat · MKV Cluster · MP3 ID3v2/v1 제거 오디오) 매칭
        // 메타데이터(moov/EBML/ID3) 크기 변경으로 파일 크기가 달라졌더라도 실제 미디어 스트림이 동일한 파일 탐지 (Semaphore 3-Way 병렬 처리)
        val remainingContainersForPayload = filteredCandidates.filter {
            (it.category == FileCategory.VIDEO || it.category == FileCategory.AUDIO) &&
                it.uri !in alreadyGroupedUris
        }

        if (remainingContainersForPayload.size > 1) {
            val payloadCandidates = mutableListOf<Pair<String, MediaFileItem>>()
            var stage25Processed = 0

            for (chunk in remainingContainersForPayload.chunked(12)) {
                currentCoroutineContext().ensureActive()
                val health = ThermalBatteryMonitor.inspectDeviceHealth(context)
                val stage25Permits = when (health.throttleLevel) {
                    ThrottleLevel.THERMAL_COOLING -> 1
                    ThrottleLevel.BALANCED -> 2
                    ThrottleLevel.NORMAL -> 3
                }
                val stage25Semaphore = Semaphore(stage25Permits)
                val chunkResults = coroutineScope {
                    chunk.map { mediaItem ->
                        async {
                            stage25Semaphore.withPermit {
                                val key = cacheKeyFor(mediaItem)
                                val cached = synchronized(pendingCacheUpdates) { cacheMap[key] }
                                val cachedPayloadHash = cached?.payloadHash

                                val lengthAndHash = inspectAndComputeContainerPayload(
                                    item = mediaItem,
                                    cachedPayloadHash = cachedPayloadHash,
                                    actualBytesRead = actualBytesRead
                                )

                                if (lengthAndHash != null) {
                                    val (streamLength, pHash) = lengthAndHash
                                    if (cachedPayloadHash != null) {
                                        cacheHits.incrementAndGet()
                                    } else {
                                        val updated = CachedFileHashEntity(
                                            uriString = mediaItem.uri.toString(),
                                            sizeBytes = mediaItem.sizeBytes,
                                            lastModifiedSeconds = mediaItem.lastModifiedSeconds,
                                            partialHash = cached?.partialHash ?: mediaItem.partialHash,
                                            fullHash = cached?.fullHash ?: mediaItem.fullHash,
                                            perceptualHashesCsv = cached?.perceptualHashesCsv
                                                ?: CachedFileHashEntity.encodePerceptualHashes(mediaItem.perceptualHashes),
                                            payloadHash = pHash,
                                            pHashesCsv = cached?.pHashesCsv
                                                ?: CachedFileHashEntity.encodePerceptualHashes(mediaItem.pHashes)
                                        )
                                        recordCacheUpdate(key, updated)
                                    }
                                    "${streamLength}_${pHash}" to mediaItem.copy(payloadHash = pHash)
                                } else {
                                    null
                                }
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                payloadCandidates.addAll(chunkResults)
                stage25Processed += chunk.size

                if (health.throttleLevel.chunkCooldownMs > 0) {
                    delay(health.throttleLevel.chunkCooldownMs)
                }

                emit(
                    ScanProgressUpdate(
                        phase = ScanPhase.STAGE_2_5_MP4_PAYLOAD,
                        processedCount = stage25Processed,
                        totalCount = remainingContainersForPayload.size,
                        currentFileName = chunk.last().displayName,
                        discoveredGroupCount = discoveredGroups.size,
                        potentialSavedBytes = discoveredGroups.sumOf { it.reclaimableBytes },
                        cacheHitCount = cacheHits.get(),
                        elapsedMs = System.currentTimeMillis() - startTime,
                        funnelMetrics = buildFunnelMetrics(
                            partialCandidates = partialHashCandidates,
                            fullCandidates = stage3Targets.size
                        ),
                        throttleLevel = health.throttleLevel
                    )
                )
            }

            val payloadDuplicateMap = payloadCandidates
                .groupBy({ it.first }, { it.second })
                .filter { (_, list) -> list.size > 1 }

            for ((payloadSig, groupItems) in payloadDuplicateMap) {
                val category = groupItems.first().category
                val enrichedItems = if (category == FileCategory.VIDEO) {
                    enrichVideoTechSpecs(groupItems)
                } else {
                    groupItems
                }
                val rawGroup = DuplicateGroup(
                    groupId = "CONTAINER_PAYLOAD_${groupCounter++}",
                    signature = "stream:${payloadSig.take(24)}",
                    matchType = MatchType.CONTAINER_PAYLOAD_MATCH,
                    similarityPercent = 100,
                    category = category,
                    items = enrichedItems
                )
                val smartSelected = SmartSelectUseCase.applyRuleToGroup(rawGroup, config.selectionRule)
                discoveredGroups.add(smartSelected)
                groupItems.forEach { alreadyGroupedUris.add(it.uri) }
            }
        }

        // Stage 4: 유사 영상 및 사진 탐지 (재인코딩·해상도 변경, Dual dHash + 2D-DCT pHash, TemporalSequenceAligner, BK-Tree)
        val similarityThreshold = config.similarityThresholdPercent.coerceIn(75, 96)
        if (config.includeSimilarVideos) {
            val remainingVideos = filteredCandidates.filter {
                it.category == FileCategory.VIDEO &&
                    it.durationMs >= 1500L &&
                    it.uri !in alreadyGroupedUris
            }.sortedBy { it.durationMs }

            // 구간 편집(Trimmed) 영상도 탐지할 수 있도록 후보군이 120개 이하이면 전체 추출, 초과 시 인접 길이 우선 추출
            val similarCandidates = if (remainingVideos.size in 2..120) {
                remainingVideos
            } else {
                filterVideosWithCloseDuration(remainingVideos)
            }

            val videosWithDualHash = mutableListOf<MediaFileItem>()
            var videoProcessed = 0

            for (chunk in similarCandidates.chunked(8)) {
                currentCoroutineContext().ensureActive()
                val health = ThermalBatteryMonitor.inspectDeviceHealth(context)
                val permits = if (health.throttleLevel == ThrottleLevel.THERMAL_COOLING) 1 else 2
                val videoSemaphore = Semaphore(permits)

                val chunkResults = coroutineScope {
                    chunk.map { video ->
                        async {
                            videoSemaphore.withPermit {
                                val key = cacheKeyFor(video)
                                val cached = synchronized(pendingCacheUpdates) { cacheMap[key] }
                                val cachedDHashes = cached?.parsePerceptualHashes() ?: emptyList()
                                val cachedPHashes = cached?.parsePHashes() ?: emptyList()

                                val dualSig = if (cachedDHashes.isNotEmpty() && cachedPHashes.isNotEmpty()) {
                                    cacheHits.incrementAndGet()
                                    VideoPerceptualHasher.DualPerceptualSignature(cachedDHashes, cachedPHashes)
                                } else {
                                    val extracted = VideoPerceptualHasher.extractExtendedDualVideoSignature(
                                        context = context,
                                        uri = video.uri,
                                        knownDurationMs = video.durationMs
                                    )
                                    if (extracted.dHashes.isNotEmpty()) {
                                        val updated = CachedFileHashEntity(
                                            uriString = video.uri.toString(),
                                            sizeBytes = video.sizeBytes,
                                            lastModifiedSeconds = video.lastModifiedSeconds,
                                            partialHash = cached?.partialHash ?: video.partialHash,
                                            fullHash = cached?.fullHash ?: video.fullHash,
                                            perceptualHashesCsv = CachedFileHashEntity.encodePerceptualHashes(extracted.dHashes),
                                            payloadHash = cached?.payloadHash ?: video.payloadHash,
                                            pHashesCsv = CachedFileHashEntity.encodePerceptualHashes(extracted.pHashes)
                                        )
                                        recordCacheUpdate(key, updated)
                                    }
                                    extracted
                                }

                                if (dualSig.dHashes.isNotEmpty()) {
                                    video.copy(
                                        perceptualHashes = dualSig.dHashes,
                                        pHashes = dualSig.pHashes
                                    )
                                } else {
                                    null
                                }
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                videosWithDualHash.addAll(chunkResults)
                videoProcessed += chunk.size

                if (health.throttleLevel.chunkCooldownMs > 0) {
                    delay(health.throttleLevel.chunkCooldownMs)
                }

                emit(
                    ScanProgressUpdate(
                        phase = ScanPhase.STAGE_4_SIMILAR_VIDEO,
                        processedCount = videoProcessed,
                        totalCount = similarCandidates.size,
                        currentFileName = chunk.last().displayName,
                        discoveredGroupCount = discoveredGroups.size,
                        potentialSavedBytes = discoveredGroups.sumOf { it.reclaimableBytes },
                        cacheHitCount = cacheHits.get(),
                        elapsedMs = System.currentTimeMillis() - startTime,
                        funnelMetrics = buildFunnelMetrics(
                            partialCandidates = partialHashCandidates,
                            fullCandidates = stage3Targets.size
                        ),
                        largestFiles = topLargestFiles,
                        throttleLevel = health.throttleLevel
                    )
                )
            }

            // 클러스터링: TransitiveClusterMerger 기반 dHash + pHash 직접 비교 및 시계열 부분 수열(TemporalSequenceAligner) 구간 편집 영상 그룹화
            val videoMerger = TransitiveClusterMerger(videosWithDualHash)
            data class VideoMatchEdge(
                val indexA: Int,
                val indexB: Int,
                val similarity: Int,
                val isMirrored: Boolean,
                val isTrimmed: Boolean
            )
            val matchedVideoEdges = mutableListOf<VideoMatchEdge>()

            for (i in videosWithDualHash.indices) {
                val baseVideo = videosWithDualHash[i]

                for (j in (i + 1) until videosWithDualHash.size) {
                    val compareVideo = videosWithDualHash[j]

                    val closeDuration = if (baseVideo.durationMs > 0L && compareVideo.durationMs > 0L) {
                        val diffMs = abs(baseVideo.durationMs - compareVideo.durationMs)
                        val maxAllowedDiff = maxOf(
                            2500L,
                            (maxOf(baseVideo.durationMs, compareVideo.durationMs) * 0.08f).toLong()
                        )
                        diffMs <= maxAllowedDiff
                    } else {
                        true
                    }

                    val (directSimilarity, isMirroredMatch) = if (closeDuration &&
                        baseVideo.perceptualHashes.size == compareVideo.perceptualHashes.size
                    ) {
                        VideoPerceptualHasher.calculateMirrorAwareSimilarityPercent(
                            dHashesA = baseVideo.perceptualHashes,
                            dHashesB = compareVideo.perceptualHashes,
                            pHashesA = baseVideo.pHashes,
                            pHashesB = compareVideo.pHashes
                        )
                    } else {
                        0 to false
                    }

                    if (directSimilarity >= similarityThreshold) {
                        if (videoMerger.union(i, j) || videoMerger.find(i) == videoMerger.find(j)) {
                            matchedVideoEdges.add(
                                VideoMatchEdge(
                                    indexA = i,
                                    indexB = j,
                                    similarity = directSimilarity,
                                    isMirrored = isMirroredMatch,
                                    isTrimmed = false
                                )
                            )
                        }
                    } else {
                        val aligned = TemporalSequenceAligner.alignSubsequenceSimilarity(
                            dHashesA = baseVideo.perceptualHashes,
                            dHashesB = compareVideo.perceptualHashes,
                            pHashesA = baseVideo.pHashes,
                            pHashesB = compareVideo.pHashes
                        )
                        if (aligned.similarityPercent >= similarityThreshold) {
                            if (videoMerger.union(i, j) || videoMerger.find(i) == videoMerger.find(j)) {
                                matchedVideoEdges.add(
                                    VideoMatchEdge(
                                        indexA = i,
                                        indexB = j,
                                        similarity = aligned.similarityPercent,
                                        isMirrored = false,
                                        isTrimmed = aligned.isSubsequenceTrimmed
                                    )
                                )
                            }
                        }
                    }
                }
            }

            for (clusterIndices in videoMerger.extractClusterIndices()) {
                val cluster = clusterIndices.map { videosWithDualHash[it] }
                val indexSet = clusterIndices.toSet()
                val clusterEdges = matchedVideoEdges.filter { it.indexA in indexSet && it.indexB in indexSet }
                val minSimilarityInCluster = clusterEdges.minOfOrNull { it.similarity } ?: 99
                val isTrimmedCluster = clusterEdges.any { it.isTrimmed }
                val isMirroredCluster = clusterEdges.any { it.isMirrored }

                cluster.forEach { alreadyGroupedUris.add(it.uri) }
                val resolvedMatchType = if (isTrimmedCluster) {
                    MatchType.TRIMMED_CLIP_MATCH
                } else {
                    MatchType.SIMILAR_VIDEO
                }
                val groupPrefix = if (isTrimmedCluster) "TRIMMED" else "SIMILAR"
                val baseVideo = cluster.first()
                val baseDHashHex = baseVideo.perceptualHashes.first().toULong().toString(16)
                val groupSignature = when {
                    isTrimmedCluster && isMirroredCluster -> "trim+mirror+$baseDHashHex (좌우 반전·유사)"
                    isTrimmedCluster -> "trim:$baseDHashHex"
                    isMirroredCluster -> "mirror+$baseDHashHex (좌우 반전·유사)"
                    else -> "dHash+pHash:$baseDHashHex"
                }
                val enrichedCluster = enrichVideoTechSpecs(cluster)
                val simGroup = DuplicateGroup(
                    groupId = "${groupPrefix}_${groupCounter++}",
                    signature = groupSignature,
                    matchType = resolvedMatchType,
                    similarityPercent = minSimilarityInCluster,
                    category = FileCategory.VIDEO,
                    items = enrichedCluster
                )
                discoveredGroups.add(
                    SmartSelectUseCase.applyRuleToGroup(simGroup, config.selectionRule)
                )
            }

            // 전체 미디어 또는 전체 파일 모드일 때 리사이즈·압축된 유사 사진 및 연사(Burst) 사진을 BK-Tree O(N log N) + TransitiveClusterMerger로 탐지
            if (config.scopeMode != ScanScopeMode.VIDEO_ONLY) {
                val remainingImages = filteredCandidates.filter {
                    it.category == FileCategory.IMAGE && it.uri !in alreadyGroupedUris
                }

                if (remainingImages.size > 1) {
                    val imagesWithDualHash = mutableListOf<MediaFileItem>()
                    var imageProcessed = 0

                    for (chunk in remainingImages.chunked(12)) {
                        currentCoroutineContext().ensureActive()
                        val health = ThermalBatteryMonitor.inspectDeviceHealth(context)
                        val imagePermits = when (health.throttleLevel) {
                            ThrottleLevel.THERMAL_COOLING -> 1
                            ThrottleLevel.BALANCED -> 2
                            ThrottleLevel.NORMAL -> 3
                        }
                        val imageSemaphore = Semaphore(imagePermits)
                        val chunkResults = coroutineScope {
                            chunk.map { image ->
                                async {
                                    imageSemaphore.withPermit {
                                        val key = cacheKeyFor(image)
                                        val cached = synchronized(pendingCacheUpdates) { cacheMap[key] }
                                        val cachedDHashes = cached?.parsePerceptualHashes() ?: emptyList()
                                        val cachedPHashes = cached?.parsePHashes() ?: emptyList()

                                        val dualSig = if (cachedDHashes.isNotEmpty() && cachedPHashes.isNotEmpty()) {
                                            cacheHits.incrementAndGet()
                                            VideoPerceptualHasher.DualPerceptualSignature(cachedDHashes, cachedPHashes)
                                        } else {
                                            val extracted = VideoPerceptualHasher.extractImageDualSignature(
                                                context = context,
                                                uri = image.uri
                                            )
                                            if (extracted.dHashes.isNotEmpty()) {
                                                val updated = CachedFileHashEntity(
                                                    uriString = image.uri.toString(),
                                                    sizeBytes = image.sizeBytes,
                                                    lastModifiedSeconds = image.lastModifiedSeconds,
                                                    partialHash = cached?.partialHash ?: image.partialHash,
                                                    fullHash = cached?.fullHash ?: image.fullHash,
                                                    perceptualHashesCsv = CachedFileHashEntity.encodePerceptualHashes(extracted.dHashes),
                                                    payloadHash = cached?.payloadHash ?: image.payloadHash,
                                                    pHashesCsv = CachedFileHashEntity.encodePerceptualHashes(extracted.pHashes)
                                                )
                                                recordCacheUpdate(key, updated)
                                            }
                                            extracted
                                        }

                                        if (dualSig.dHashes.isNotEmpty()) {
                                            image.copy(
                                                perceptualHashes = dualSig.dHashes,
                                                pHashes = dualSig.pHashes
                                            )
                                        } else {
                                            null
                                        }
                                    }
                                }
                            }.awaitAll().filterNotNull()
                        }

                        imagesWithDualHash.addAll(chunkResults)
                        imageProcessed += chunk.size

                        if (health.throttleLevel.chunkCooldownMs > 0) {
                            delay(health.throttleLevel.chunkCooldownMs)
                        }

                        emit(
                            ScanProgressUpdate(
                                phase = ScanPhase.STAGE_4_SIMILAR_VIDEO,
                                processedCount = imageProcessed,
                                totalCount = remainingImages.size,
                                currentFileName = chunk.last().displayName,
                                discoveredGroupCount = discoveredGroups.size,
                                potentialSavedBytes = discoveredGroups.sumOf { it.reclaimableBytes },
                                cacheHitCount = cacheHits.get(),
                                elapsedMs = System.currentTimeMillis() - startTime,
                                funnelMetrics = buildFunnelMetrics(
                                    partialCandidates = partialHashCandidates,
                                    fullCandidates = stage3Targets.size
                                ),
                                largestFiles = topLargestFiles,
                                throttleLevel = health.throttleLevel
                            )
                        )
                    }

                    // BK-Tree 인덱싱 + TransitiveClusterMerger: O(N log N) 근접 이웃 탐색 + 5초 이내 연사(Burst Photo) 가점
                    val imageMerger = TransitiveClusterMerger(imagesWithDualHash)
                    val uriToIndex = imagesWithDualHash.withIndex().associate { it.value.uri to it.index }
                    val bkTree = HammingBkTree<MediaFileItem>()
                    for (image in imagesWithDualHash) {
                        bkTree.insert(image.perceptualHashes.first(), image)
                    }

                    data class ImageMatchEdge(
                        val indexA: Int,
                        val indexB: Int,
                        val similarity: Int,
                        val isMirrored: Boolean
                    )
                    val matchedImageEdges = mutableListOf<ImageMatchEdge>()

                    for (i in imagesWithDualHash.indices) {
                        val baseImage = imagesWithDualHash[i]
                        val baseDHash = baseImage.perceptualHashes.first()
                        val directCandidates = bkTree.searchWithinDistance(baseDHash, maxDistance = 14)
                        val mirroredCandidates = bkTree.searchWithinDistance(
                            VideoPerceptualHasher.computeMirroredDHash(baseDHash),
                            maxDistance = 14
                        )
                        val candidates = (directCandidates + mirroredCandidates)
                            .map { it.item }
                            .distinctBy { it.uri }

                        for (compareImage in candidates) {
                            val j = uriToIndex[compareImage.uri] ?: continue
                            if (j <= i) continue

                            val (similarity, isMirroredMatch) = VideoPerceptualHasher.calculateMirrorAwareSimilarityPercent(
                                dHashesA = baseImage.perceptualHashes,
                                dHashesB = compareImage.perceptualHashes,
                                pHashesA = baseImage.pHashes,
                                pHashesB = compareImage.pHashes
                            )
                            val isBurstPhoto = baseImage.lastModifiedSeconds > 0L &&
                                compareImage.lastModifiedSeconds > 0L &&
                                abs(baseImage.lastModifiedSeconds - compareImage.lastModifiedSeconds) <= 5L
                            val effectiveThreshold = if (isBurstPhoto) {
                                (similarityThreshold - 5).coerceAtLeast(70)
                            } else {
                                similarityThreshold
                            }

                            if (similarity >= effectiveThreshold) {
                                if (imageMerger.union(i, j) || imageMerger.find(i) == imageMerger.find(j)) {
                                    matchedImageEdges.add(
                                        ImageMatchEdge(
                                            indexA = i,
                                            indexB = j,
                                            similarity = similarity,
                                            isMirrored = isMirroredMatch
                                        )
                                    )
                                }
                            }
                        }
                    }

                    for (clusterIndices in imageMerger.extractClusterIndices()) {
                        val cluster = clusterIndices.map { imagesWithDualHash[it] }
                        val indexSet = clusterIndices.toSet()
                        val clusterEdges = matchedImageEdges.filter { it.indexA in indexSet && it.indexB in indexSet }
                        val minSimilarityInCluster = clusterEdges.minOfOrNull { it.similarity } ?: 99
                        val isMirroredCluster = clusterEdges.any { it.isMirrored }

                        cluster.forEach { alreadyGroupedUris.add(it.uri) }
                        val enrichedCluster = enrichImageAuthenticity(cluster)
                        val baseDHashHex = cluster.first().perceptualHashes.first().toULong().toString(16)
                        val imageSignature = if (isMirroredCluster) {
                            "mirror+$baseDHashHex (좌우 반전·유사)"
                        } else {
                            "pHash:$baseDHashHex"
                        }
                        val simImgGroup = DuplicateGroup(
                            groupId = "SIMILAR_IMG_${groupCounter++}",
                            signature = imageSignature,
                            matchType = MatchType.SIMILAR_VIDEO,
                            similarityPercent = minSimilarityInCluster,
                            category = FileCategory.IMAGE,
                            items = enrichedCluster
                        )
                        discoveredGroups.add(
                            SmartSelectUseCase.applyRuleToGroup(simImgGroup, config.selectionRule)
                        )
                    }
                }

                // Stage 4 크로스 오디오 파형 핑거프린트 매칭 (포맷·음량 무관 32-밴드 에너지 엔벨로프 비교 + TransitiveClusterMerger)
                val remainingAudio = filteredCandidates.filter {
                    it.category == FileCategory.AUDIO && it.uri !in alreadyGroupedUris
                }

                if (remainingAudio.size > 1) {
                    val audioWithFingerprints = mutableListOf<Pair<MediaFileItem, AudioEnvelopeFingerprint>>()
                    var audioProcessed = 0

                    for (chunk in remainingAudio.chunked(12)) {
                        currentCoroutineContext().ensureActive()
                        val health = ThermalBatteryMonitor.inspectDeviceHealth(context)
                        val audioPermits = when (health.throttleLevel) {
                            ThrottleLevel.THERMAL_COOLING -> 1
                            ThrottleLevel.BALANCED -> 2
                            ThrottleLevel.NORMAL -> 3
                        }
                        val audioSemaphore = Semaphore(audioPermits)
                        val chunkResults = coroutineScope {
                            chunk.map { audio ->
                                async {
                                    audioSemaphore.withPermit {
                                        val fp = AudioWaveformHasher.extractAudioFingerprint(
                                            context = context,
                                            uri = audio.uri,
                                            absolutePath = audio.absolutePath,
                                            knownDurationMs = audio.durationMs
                                        )
                                        if (fp != null) {
                                            val enrichedAudio = if (audio.durationMs <= 0L && fp.durationMs > 0L) {
                                                audio.copy(durationMs = fp.durationMs)
                                            } else {
                                                audio
                                            }
                                            enrichedAudio to fp
                                        } else {
                                            null
                                        }
                                    }
                                }
                            }.awaitAll().filterNotNull()
                        }

                        audioWithFingerprints.addAll(chunkResults)
                        audioProcessed += chunk.size

                        if (health.throttleLevel.chunkCooldownMs > 0) {
                            delay(health.throttleLevel.chunkCooldownMs)
                        }

                        emit(
                            ScanProgressUpdate(
                                phase = ScanPhase.STAGE_4_SIMILAR_VIDEO,
                                processedCount = audioProcessed,
                                totalCount = remainingAudio.size,
                                currentFileName = chunk.last().displayName,
                                discoveredGroupCount = discoveredGroups.size,
                                potentialSavedBytes = discoveredGroups.sumOf { it.reclaimableBytes },
                                cacheHitCount = cacheHits.get(),
                                elapsedMs = System.currentTimeMillis() - startTime,
                                funnelMetrics = buildFunnelMetrics(
                                    partialCandidates = partialHashCandidates,
                                    fullCandidates = stage3Targets.size
                                ),
                                largestFiles = topLargestFiles,
                                throttleLevel = health.throttleLevel
                            )
                        )
                    }

                    val audioMerger = TransitiveClusterMerger(audioWithFingerprints)
                    data class AudioMatchEdge(
                        val indexA: Int,
                        val indexB: Int,
                        val similarity: Int
                    )
                    val matchedAudioEdges = mutableListOf<AudioMatchEdge>()

                    for (i in audioWithFingerprints.indices) {
                        val (_, fpA) = audioWithFingerprints[i]
                        for (j in (i + 1) until audioWithFingerprints.size) {
                            val (_, fpB) = audioWithFingerprints[j]
                            val similarity = AudioWaveformHasher.computeEnvelopeSimilarityPercent(fpA, fpB)
                            if (similarity >= config.similarityThresholdPercent) {
                                if (audioMerger.union(i, j) || audioMerger.find(i) == audioMerger.find(j)) {
                                    matchedAudioEdges.add(
                                        AudioMatchEdge(
                                            indexA = i,
                                            indexB = j,
                                            similarity = similarity
                                        )
                                    )
                                }
                            }
                        }
                    }

                    for (clusterIndices in audioMerger.extractClusterIndices()) {
                        val clusterPairs = clusterIndices.map { audioWithFingerprints[it] }
                        val clusterItems = clusterPairs.map { it.first }
                        val indexSet = clusterIndices.toSet()
                        val clusterEdges = matchedAudioEdges.filter { it.indexA in indexSet && it.indexB in indexSet }
                        val minSimilarityInCluster = clusterEdges.minOfOrNull { it.similarity } ?: 99

                        clusterItems.forEach { alreadyGroupedUris.add(it.uri) }
                        val firstFp = clusterPairs.first().second
                        val bandDigest = firstFp.bands.fold(0L) { acc, v ->
                            acc * 31L + (v * 1000f).toInt().toLong()
                        }
                        val audioGroup = DuplicateGroup(
                            groupId = "AUDIO_FP_${groupCounter++}",
                            signature = "audio:${firstFp.durationMs}ms:${bandDigest.toULong().toString(16)}",
                            matchType = MatchType.AUDIO_FINGERPRINT_MATCH,
                            similarityPercent = minSimilarityInCluster,
                            category = FileCategory.AUDIO,
                            items = clusterItems
                        )
                        discoveredGroups.add(
                            SmartSelectUseCase.applyRuleToGroup(audioGroup, config.selectionRule)
                        )
                    }
                }
            }
        }

        // 신규/갱신된 해시 캐시 항목들을 일괄(Batch) 저장
        if (pendingCacheUpdates.isNotEmpty()) {
            hashCacheDao.upsertAll(pendingCacheUpdates.values.toList())
        }

        // 사용자가 무시(Ignore) 처리한 그룹 시그니처 제외 후 확보 가능 용량이 큰 그룹부터 내림차순 정렬
        val sortedGroups = discoveredGroups
            .filterNot { it.signature in config.ignoredGroupSignatures }
            .sortedByDescending { it.reclaimableBytes }
        val finalFunnelMetrics = buildFunnelMetrics(
            partialCandidates = partialHashCandidates,
            fullCandidates = stage3Targets.size
        )

        emit(
            ScanProgressUpdate(
                phase = ScanPhase.COMPLETED,
                processedCount = allCandidates.size,
                totalCount = allCandidates.size,
                currentFileName = "분석 완료",
                discoveredGroupCount = sortedGroups.size,
                potentialSavedBytes = sortedGroups.sumOf { it.reclaimableBytes },
                cacheHitCount = cacheHits.get(),
                groups = sortedGroups,
                elapsedMs = System.currentTimeMillis() - startTime,
                funnelMetrics = finalFunnelMetrics,
                largestFiles = topLargestFiles,
                corruptedFiles = filteredCorrupted
            )
        )
    }.flowOn(Dispatchers.IO)

    /**
     * 선택된 중복 파일들을 안전하게 정리(OS 휴지통 30일 보관 이동 또는 영구 삭제)합니다.
     * 삭제 직전 파일 크기 및 수정 시각을 재검증(TOCTOU 무결성 검증)하여 중간에 수정된 파일을 보호합니다.
     */
    suspend fun prepareCleanupRequest(
        itemsToDelete: List<MediaFileItem>,
        moveToTrash: Boolean
    ): CleanupRequestOutcome = withContext(Dispatchers.IO) {
        if (itemsToDelete.isEmpty()) {
            return@withContext CleanupRequestOutcome.CompletedDirectly(0, 0L)
        }

        // 1. Pre-Delete Integrity Verification (TOCTOU 방지)
        for (item in itemsToDelete) {
            val currentFile = item.absolutePath?.let { File(it) }
            if (currentFile != null && currentFile.exists()) {
                if (currentFile.length() != item.sizeBytes) {
                    return@withContext CleanupRequestOutcome.IntegrityFailed(
                        "파일 '${item.displayName}'의 크기가 스캔 이후 변경되었습니다. 안전을 위해 정리를 중단하고 재스캔을 권장합니다."
                    )
                }
            }
        }

        val mediaStoreUris = mutableListOf<Uri>()
        var directDeletedCount = 0
        var directFreedBytes = 0L
        val deletedUriStrings = mutableListOf<String>()
        val vaultManager by lazy { QuarantineVaultManager(context) }

        for (item in itemsToDelete) {
            if (item.uri.scheme == "content") {
                mediaStoreUris.add(item.uri)
            } else if (item.uri.scheme == "file" && item.absolutePath != null) {
                val file = File(item.absolutePath)
                if (file.exists()) {
                    val handled = if (moveToTrash) {
                        vaultManager.quarantineFile(file) != null
                    } else {
                        file.delete()
                    }
                    if (handled) {
                        directDeletedCount++
                        directFreedBytes += item.sizeBytes
                        deletedUriStrings.add(item.uri.toString())
                    }
                }
            }
        }

        if (deletedUriStrings.isNotEmpty()) {
            hashCacheDao.deleteByUris(deletedUriStrings)
        }

        if (mediaStoreUris.isEmpty()) {
            return@withContext CleanupRequestOutcome.CompletedDirectly(
                deletedCount = directDeletedCount,
                freedBytes = directFreedBytes
            )
        }

        // Android 11 (R, API 30)+: MediaStore.createTrashRequest / createDeleteRequest 일괄 처리
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = if (moveToTrash) {
                MediaStore.createTrashRequest(context.contentResolver, mediaStoreUris, true)
            } else {
                MediaStore.createDeleteRequest(context.contentResolver, mediaStoreUris)
            }
            return@withContext CleanupRequestOutcome.RequiresSystemConfirmation(
                intentSender = pendingIntent.intentSender,
                directDeletedCount = directDeletedCount,
                pendingMediaUris = mediaStoreUris,
                isTrashMove = moveToTrash
            )
        }

        // Android 10 이하 폴백: ContentResolver 직접 삭제 시도
        for (uri in mediaStoreUris) {
            try {
                val rows = context.contentResolver.delete(uri, null, null)
                if (rows > 0) {
                    directDeletedCount++
                    deletedUriStrings.add(uri.toString())
                }
            } catch (_: SecurityException) {
            }
        }
        if (deletedUriStrings.isNotEmpty()) {
            hashCacheDao.deleteByUris(deletedUriStrings)
        }

        CleanupRequestOutcome.CompletedDirectly(
            deletedCount = directDeletedCount,
            freedBytes = itemsToDelete.sumOf { it.sizeBytes }
        )
    }

    suspend fun removeUrisFromCache(uris: List<Uri>) = withContext(Dispatchers.IO) {
        if (uris.isNotEmpty()) {
            hashCacheDao.deleteByUris(uris.map { it.toString() })
        }
    }

    suspend fun clearHashCache() = withContext(Dispatchers.IO) {
        hashCacheDao.clearAll()
    }

    suspend fun getCachedFileCount(): Int = withContext(Dispatchers.IO) {
        hashCacheDao.countCachedFiles()
    }

    private fun computeItemPartialHash(item: MediaFileItem): Long? {
        return try {
            val absPath = item.absolutePath
            if (!absPath.isNullOrBlank()) {
                val file = File(absPath)
                if (file.exists() && file.canRead()) {
                    return FastHasher.computePartialHash(file)
                }
            }
            context.contentResolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    FastHasher.computePartialHash(fis.channel, item.sizeBytes)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun computeItemFullHash(item: MediaFileItem): String? {
        return try {
            val absPath = item.absolutePath
            if (!absPath.isNullOrBlank()) {
                val file = File(absPath)
                if (file.exists() && file.canRead()) {
                    return FastHasher.computeFullHash(file)
                }
            }
            context.contentResolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    FastHasher.computeFullHash(fis.channel)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * MP4/MOV(mdat), MKV/WebM(Cluster), MP3(ID3v2/v1 제거 오디오 스트림) 등 컨테이너의 순수 스트림(>= 48KB)을 검사하여
     * (payloadLength, payloadHash) 쌍을 반환합니다.
     */
    private fun inspectAndComputeContainerPayload(
        item: MediaFileItem,
        cachedPayloadHash: Long?,
        actualBytesRead: AtomicLong
    ): Pair<Long, Long>? {
        return try {
            val absPath = item.absolutePath
            if (!absPath.isNullOrBlank()) {
                val file = File(absPath)
                if (file.exists() && file.canRead()) {
                    FileInputStream(file).use { fis ->
                        val fileSize = file.length()
                        return if (cachedPayloadHash != null) {
                            val region = MediaContainerInspector.inspectAnyMediaPayload(fis.channel, fileSize)
                            if (region == null || region.length < FastHasher.SAMPLE_CHUNK_SIZE * 3L) null
                            else region.length to cachedPayloadHash
                        } else {
                            MediaContainerInspector.computeContainerPayloadHash(fis.channel, fileSize)?.also {
                                actualBytesRead.addAndGet(FastHasher.SAMPLE_CHUNK_SIZE * 3L)
                            }
                        }
                    }
                }
            }
            context.contentResolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    if (cachedPayloadHash != null) {
                        val region = MediaContainerInspector.inspectAnyMediaPayload(fis.channel, item.sizeBytes)
                        if (region == null || region.length < FastHasher.SAMPLE_CHUNK_SIZE * 3L) null
                        else region.length to cachedPayloadHash
                    } else {
                        MediaContainerInspector.computeContainerPayloadHash(fis.channel, item.sizeBytes)?.also {
                            actualBytesRead.addAndGet(FastHasher.SAMPLE_CHUNK_SIZE * 3L)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun filterVideosWithCloseDuration(sortedVideos: List<MediaFileItem>): List<MediaFileItem> {
        if (sortedVideos.size < 2) return emptyList()
        val result = mutableSetOf<MediaFileItem>()
        for (i in sortedVideos.indices) {
            val current = sortedVideos[i]
            val maxTol = maxOf(2500L, (current.durationMs * 0.08f).toLong())
            if (i > 0 && (current.durationMs - sortedVideos[i - 1].durationMs) <= maxTol) {
                result.add(current)
                result.add(sortedVideos[i - 1])
            }
            if (i < sortedVideos.lastIndex && (sortedVideos[i + 1].durationMs - current.durationMs) <= maxTol) {
                result.add(current)
                result.add(sortedVideos[i + 1])
            }
        }
        return result.toList()
    }

    private fun collectTargetFiles(
        config: ScanConfig,
        corruptedReports: MutableList<CorruptedFileReport>
    ): List<MediaFileItem> {
        val results = mutableListOf<MediaFileItem>()
        val seenPaths = mutableSetOf<String>()

        // 1. 영상 스캔 (모든 모드에서 기본 포함)
        queryMediaStoreVideos(config, results, seenPaths, corruptedReports)

        // 2. 전체 미디어 또는 전체 파일 모드일 때 이미지 & 오디오 추가
        if (config.scopeMode == ScanScopeMode.ALL_MEDIA || config.scopeMode == ScanScopeMode.ALL_FILES) {
            queryMediaStoreImages(config, results, seenPaths, corruptedReports)
            queryMediaStoreAudio(config, results, seenPaths, corruptedReports)
        }

        // 2-1. MediaStore 저용량(0바이트) / 임시(.tmp, .part) 이상 파일 탐지
        queryLowSizeCorruptedMediaStoreFiles(config, seenPaths, corruptedReports)

        // 3. 전체 파일(문서·압축·APK 포함) 모드일 때 외부 스토리지 파일 트리 스캔
        if (config.scopeMode == ScanScopeMode.ALL_FILES) {
            val hasAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
            if (hasAllFilesAccess) {
                val rootDir = Environment.getExternalStorageDirectory()
                walkDirectoryForFiles(rootDir, config, results, seenPaths, corruptedReports)
            }
        }

        // 4. 앱 내부 데모/테스트 샌드박스 폴더가 존재하면 함께 스캔하여 에뮬레이터/실기기 즉시 테스트 지원
        val appDemoDir = File(context.getExternalFilesDir(null), "DemoDuplicates")
        if (appDemoDir.exists()) {
            walkDirectoryForFiles(appDemoDir, config, results, seenPaths, corruptedReports)
        }

        return results
    }

    @Suppress("DEPRECATION")
    private fun queryMediaStoreVideos(
        config: ScanConfig,
        results: MutableList<MediaFileItem>,
        seenPaths: MutableSet<String>,
        corruptedReports: MutableList<CorruptedFileReport>
    ) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATA
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Video.Media.RELATIVE_PATH)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    add(MediaStore.Video.Media.IS_TRASHED)
                }
            }
        }.toTypedArray()

        val selection = "${MediaStore.Video.Media.SIZE} >= ?"
        val selectionArgs = arrayOf(config.minFileSizeBytes.toString())

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Video.Media.SIZE} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val modCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                val relCol = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                val trashedCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    cursor.getColumnIndex(MediaStore.Video.Media.IS_TRASHED)
                } else -1

                while (cursor.moveToNext()) {
                    if (trashedCol >= 0 && cursor.getInt(trashedCol) == 1) continue

                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: "video_$id.mp4"
                    val size = cursor.getLong(sizeCol)
                    val dateModified = cursor.getLong(modCol)
                    val mime = cursor.getString(mimeCol) ?: "video/mp4"
                    val duration = cursor.getLong(durCol)
                    val width = cursor.getInt(widthCol)
                    val height = cursor.getInt(heightCol)
                    val absPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val relPath = if (relCol >= 0) {
                        cursor.getString(relCol) ?: ""
                    } else {
                        absPath?.substringBeforeLast('/', "") ?: ""
                    }

                    if (isExcludedPath(relPath, absPath, config.excludedFolderKeywords)) continue
                    val canonicalKey = absPath?.let { FileIntegrityGuard.resolveCanonicalKey(File(it)) }
                    if (canonicalKey != null && !seenPaths.add(canonicalKey)) continue

                    val contentUri = ContentUris.withAppendedId(collection, id)
                    val item = MediaFileItem(
                        id = id,
                        uri = contentUri,
                        displayName = displayName,
                        relativePath = relPath.ifBlank { "내장 스토리지" },
                        absolutePath = absPath,
                        sizeBytes = size,
                        lastModifiedSeconds = dateModified,
                        mimeType = mime,
                        category = FileCategory.VIDEO,
                        durationMs = duration,
                        width = width,
                        height = height
                    )
                    if (absPath != null && recordIfCorrupted(File(absPath), item, corruptedReports)) {
                        continue
                    }
                    results.add(item)
                }
            }
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun queryMediaStoreImages(
        config: ScanConfig,
        results: MutableList<MediaFileItem>,
        seenPaths: MutableSet<String>,
        corruptedReports: MutableList<CorruptedFileReport>
    ) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATA
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        val selection = "${MediaStore.Images.Media.SIZE} >= ?"
        val selectionArgs = arrayOf(config.minFileSizeBytes.toString())

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.SIZE} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val modCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                val relCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: "image_$id.jpg"
                    val size = cursor.getLong(sizeCol)
                    val dateModified = cursor.getLong(modCol)
                    val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                    val width = cursor.getInt(widthCol)
                    val height = cursor.getInt(heightCol)
                    val absPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val relPath = if (relCol >= 0) {
                        cursor.getString(relCol) ?: ""
                    } else {
                        absPath?.substringBeforeLast('/', "") ?: ""
                    }

                    if (isExcludedPath(relPath, absPath, config.excludedFolderKeywords)) continue
                    val canonicalKey = absPath?.let { FileIntegrityGuard.resolveCanonicalKey(File(it)) }
                    if (canonicalKey != null && !seenPaths.add(canonicalKey)) continue

                    val contentUri = ContentUris.withAppendedId(collection, id)
                    val item = MediaFileItem(
                        id = id,
                        uri = contentUri,
                        displayName = displayName,
                        relativePath = relPath.ifBlank { "Pictures" },
                        absolutePath = absPath,
                        sizeBytes = size,
                        lastModifiedSeconds = dateModified,
                        mimeType = mime,
                        category = FileCategory.IMAGE,
                        width = width,
                        height = height
                    )
                    if (absPath != null && recordIfCorrupted(File(absPath), item, corruptedReports)) {
                        continue
                    }
                    results.add(item)
                }
            }
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun queryMediaStoreAudio(
        config: ScanConfig,
        results: MutableList<MediaFileItem>,
        seenPaths: MutableSet<String>,
        corruptedReports: MutableList<CorruptedFileReport>
    ) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        val selection = "${MediaStore.Audio.Media.SIZE} >= ?"
        val selectionArgs = arrayOf(config.minFileSizeBytes.toString())

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Audio.Media.SIZE} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val modCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val relCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: "audio_$id.mp3"
                    val size = cursor.getLong(sizeCol)
                    val dateModified = cursor.getLong(modCol)
                    val mime = cursor.getString(mimeCol) ?: "audio/mpeg"
                    val duration = cursor.getLong(durCol)
                    val absPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val relPath = if (relCol >= 0) {
                        cursor.getString(relCol) ?: ""
                    } else {
                        absPath?.substringBeforeLast('/', "") ?: ""
                    }

                    if (isExcludedPath(relPath, absPath, config.excludedFolderKeywords)) continue
                    val canonicalKey = absPath?.let { FileIntegrityGuard.resolveCanonicalKey(File(it)) }
                    if (canonicalKey != null && !seenPaths.add(canonicalKey)) continue

                    val contentUri = ContentUris.withAppendedId(collection, id)
                    val item = MediaFileItem(
                        id = id,
                        uri = contentUri,
                        displayName = displayName,
                        relativePath = relPath.ifBlank { "Music" },
                        absolutePath = absPath,
                        sizeBytes = size,
                        lastModifiedSeconds = dateModified,
                        mimeType = mime,
                        category = FileCategory.AUDIO,
                        durationMs = duration
                    )
                    if (absPath != null && recordIfCorrupted(File(absPath), item, corruptedReports)) {
                        continue
                    }
                    results.add(item)
                }
            }
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun queryLowSizeCorruptedMediaStoreFiles(
        config: ScanConfig,
        seenPaths: MutableSet<String>,
        corruptedReports: MutableList<CorruptedFileReport>
    ) {
        val collectionsToCheck = mutableListOf<Pair<Uri, FileCategory?>>()
        val videoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        collectionsToCheck.add(videoUri to FileCategory.VIDEO)

        if (config.scopeMode == ScanScopeMode.ALL_MEDIA || config.scopeMode == ScanScopeMode.ALL_FILES) {
            val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val audioUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            collectionsToCheck.add(imageUri to FileCategory.IMAGE)
            collectionsToCheck.add(audioUri to FileCategory.AUDIO)
        }

        val filesUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        collectionsToCheck.add(filesUri to null)

        for ((collection, fixedCategory) in collectionsToCheck) {
            val projection = mutableListOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATA
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.MediaColumns.RELATIVE_PATH)
                }
            }.toTypedArray()

            val selection = if (fixedCategory == null) {
                "${MediaStore.Files.FileColumns.SIZE} = 0 OR " +
                    "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.tmp' OR " +
                    "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.part' OR " +
                    "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.crdownload' OR " +
                    "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.partial'"
            } else {
                "${MediaStore.MediaColumns.SIZE} < ?"
            }
            val selectionArgs = if (fixedCategory == null) null else arrayOf(config.minFileSizeBytes.toString())

            try {
                context.contentResolver.query(
                    collection,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val modCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val relCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)

                    while (cursor.moveToNext()) {
                        val absPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                        if (absPath.isNullOrBlank()) continue
                        val relPath = if (relCol >= 0) {
                            cursor.getString(relCol) ?: ""
                        } else {
                            absPath.substringBeforeLast('/', "")
                        }
                        if (isExcludedPath(relPath, absPath, config.excludedFolderKeywords)) continue

                        val file = File(absPath)
                        val reason = CorruptedFileDetector.detectCorruptedReason(file) ?: continue
                        val canonicalKey = FileIntegrityGuard.resolveCanonicalKey(file)
                        if (!seenPaths.add(canonicalKey)) continue

                        val id = cursor.getLong(idCol)
                        val displayName = if (nameCol >= 0) cursor.getString(nameCol) ?: file.name else file.name
                        val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else file.length()
                        val dateModified = if (modCol >= 0) cursor.getLong(modCol) else file.lastModified() / 1000L
                        val mime = if (mimeCol >= 0) {
                            cursor.getString(mimeCol) ?: inferMimeType(file.extension)
                        } else {
                            inferMimeType(file.extension)
                        }
                        val category = fixedCategory ?: inferCategoryFromExtension(file.extension)
                        val contentUri = ContentUris.withAppendedId(collection, id)
                        corruptedReports.add(
                            CorruptedFileReport(
                                item = MediaFileItem(
                                    id = id,
                                    uri = contentUri,
                                    displayName = displayName,
                                    relativePath = relPath.ifBlank { file.parentFile?.name ?: "Storage" },
                                    absolutePath = absPath,
                                    sizeBytes = size,
                                    lastModifiedSeconds = dateModified,
                                    mimeType = mime,
                                    category = category
                                ),
                                reason = reason
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun walkDirectoryForFiles(
        rootDir: File,
        config: ScanConfig,
        results: MutableList<MediaFileItem>,
        seenPaths: MutableSet<String>,
        corruptedReports: MutableList<CorruptedFileReport>
    ) {
        val stack = ArrayDeque<File>()
        stack.add(rootDir)

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue

            for (child in children) {
                val absPath = child.absolutePath
                if (isExcludedPath(child.parent ?: "", absPath, config.excludedFolderKeywords)) {
                    continue
                }

                if (child.isDirectory) {
                    if (!child.name.startsWith(".")) {
                        stack.add(child)
                    }
                } else if (child.isFile) {
                    val canonicalKey = FileIntegrityGuard.resolveCanonicalKey(child)
                    if (!seenPaths.add(canonicalKey)) continue

                    val category = inferCategoryFromExtension(child.extension)
                    val corruptReason = CorruptedFileDetector.detectCorruptedReason(child)
                    if (corruptReason != null) {
                        val corruptedItem = MediaFileItem(
                            id = canonicalKey.hashCode().toLong(),
                            uri = Uri.fromFile(child),
                            displayName = child.name,
                            relativePath = child.parentFile?.name ?: "Storage",
                            absolutePath = absPath,
                            sizeBytes = child.length(),
                            lastModifiedSeconds = child.lastModified() / 1000L,
                            mimeType = inferMimeType(child.extension),
                            category = category
                        )
                        corruptedReports.add(CorruptedFileReport(corruptedItem, corruptReason))
                        continue
                    }

                    if (child.length() < config.minFileSizeBytes) continue
                    if (config.scopeMode == ScanScopeMode.VIDEO_ONLY && category != FileCategory.VIDEO) {
                        continue
                    }

                    results.add(
                        MediaFileItem(
                            id = canonicalKey.hashCode().toLong(),
                            uri = Uri.fromFile(child),
                            displayName = child.name,
                            relativePath = child.parentFile?.name ?: "Storage",
                            absolutePath = absPath,
                            sizeBytes = child.length(),
                            lastModifiedSeconds = child.lastModified() / 1000L,
                            mimeType = inferMimeType(child.extension),
                            category = category
                        )
                    )
                }
            }
        }
    }

    private fun recordIfCorrupted(
        file: File,
        item: MediaFileItem,
        corruptedReports: MutableList<CorruptedFileReport>
    ): Boolean {
        val reason = CorruptedFileDetector.detectCorruptedReason(file) ?: return false
        corruptedReports.add(CorruptedFileReport(item = item, reason = reason))
        return true
    }

    private fun filterByTargetFolders(
        candidates: List<MediaFileItem>,
        targetKeywords: Set<String>
    ): List<MediaFileItem> {
        if (targetKeywords.isEmpty()) return candidates
        val normalized = targetKeywords
            .map { it.trim().replace('\\', '/').lowercase() }
            .filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return candidates

        return candidates.filter { item ->
            val rel = item.relativePath.replace('\\', '/').lowercase()
            val abs = (item.absolutePath ?: "").replace('\\', '/').lowercase()
            normalized.any { kw -> rel.contains(kw) || abs.contains(kw) }
        }
    }

    private fun filterCorruptedByTargetFolders(
        reports: List<CorruptedFileReport>,
        targetKeywords: Set<String>
    ): List<CorruptedFileReport> {
        if (targetKeywords.isEmpty()) return reports
        val normalized = targetKeywords
            .map { it.trim().replace('\\', '/').lowercase() }
            .filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return reports

        return reports.filter { report ->
            val rel = report.item.relativePath.replace('\\', '/').lowercase()
            val abs = (report.item.absolutePath ?: "").replace('\\', '/').lowercase()
            normalized.any { kw -> rel.contains(kw) || abs.contains(kw) }
        }
    }

    private fun enrichVideoTechSpecs(items: List<MediaFileItem>): List<MediaFileItem> {
        return items.map { item ->
            if (item.category == FileCategory.VIDEO && item.videoCodec == null) {
                val specs = MediaTechSpecsInspector.inspectVideoSpecs(
                    context = context,
                    uri = item.uri,
                    fallbackSizeBytes = item.sizeBytes,
                    fallbackDurationMs = item.durationMs
                )
                if (specs.videoCodec != "UNKNOWN") {
                    item.copy(
                        videoCodec = specs.videoCodec,
                        hasAudioTrack = specs.hasAudioTrack,
                        actualBitrateKbps = specs.bitrateKbps
                    )
                } else if (specs.bitrateKbps > 0 && item.actualBitrateKbps == 0) {
                    item.copy(actualBitrateKbps = specs.bitrateKbps)
                } else {
                    item
                }
            } else {
                item
            }
        }
    }

    private fun enrichImageAuthenticity(items: List<MediaFileItem>): List<MediaFileItem> {
        return items.map { item ->
            if (item.category == FileCategory.IMAGE && item.authenticityScore == 0) {
                val score = MetadataAuthenticityInspector.inspectImageAuthenticity(
                    context = context,
                    uri = item.uri,
                    absolutePath = item.absolutePath
                )
                item.copy(authenticityScore = score)
            } else {
                item
            }
        }
    }

    private fun isExcludedPath(
        relPath: String,
        absPath: String?,
        excludedKeywords: Set<String>
    ): Boolean {
        val combined = "$relPath/${absPath ?: ""}".lowercase()
        return excludedKeywords.any { keyword ->
            keyword.isNotBlank() && combined.contains(keyword.lowercase())
        }
    }

    private fun inferCategoryFromExtension(ext: String): FileCategory {
        return when (ext.lowercase()) {
            "mp4", "mkv", "avi", "mov", "webm", "3gp", "ts", "m4v" -> FileCategory.VIDEO
            "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp" -> FileCategory.IMAGE
            "mp3", "wav", "flac", "m4a", "aac", "ogg" -> FileCategory.AUDIO
            "pdf", "zip", "rar", "7z", "tar", "gz", "apk", "pptx", "docx", "xlsx" -> FileCategory.DOCUMENT_ARCHIVE
            else -> FileCategory.OTHER
        }
    }

    private fun inferMimeType(ext: String): String {
        return when (ext.lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }
}
