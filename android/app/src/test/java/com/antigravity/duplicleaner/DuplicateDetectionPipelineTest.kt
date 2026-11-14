package com.antigravity.duplicleaner

import android.net.TestUriFactory
import com.antigravity.duplicleaner.data.hash.AudioWaveformHasher
import com.antigravity.duplicleaner.data.hash.CorruptedFileDetector
import com.antigravity.duplicleaner.data.hash.CorruptedFileReport
import com.antigravity.duplicleaner.data.hash.FastHasher
import com.antigravity.duplicleaner.data.hash.FileIntegrityGuard
import com.antigravity.duplicleaner.data.hash.HammingBkTree
import com.antigravity.duplicleaner.data.hash.MediaContainerInspector
import com.antigravity.duplicleaner.data.hash.MediaTechSpecsInspector
import com.antigravity.duplicleaner.data.hash.MetadataAuthenticityInspector
import com.antigravity.duplicleaner.data.hash.Mp4ContainerInspector
import com.antigravity.duplicleaner.data.hash.TemporalSequenceAligner
import com.antigravity.duplicleaner.data.hash.TransitiveClusterMerger
import com.antigravity.duplicleaner.data.hash.VideoPerceptualHasher
import com.antigravity.duplicleaner.data.hash.VisualQualityScorer
import com.antigravity.duplicleaner.data.local.EmptyFolderCandidate
import com.antigravity.duplicleaner.data.local.EmptyFolderCleaner
import com.antigravity.duplicleaner.data.local.QuarantineVaultManager
import com.antigravity.duplicleaner.data.repository.DemoSampleGenerator
import com.antigravity.duplicleaner.data.repository.ScanPhase
import com.antigravity.duplicleaner.data.repository.ScanProgressUpdate
import com.antigravity.duplicleaner.domain.model.AutoScanInterval
import com.antigravity.duplicleaner.domain.model.CleanerViewTab
import com.antigravity.duplicleaner.domain.model.DuplicateGroup
import com.antigravity.duplicleaner.domain.model.FileCategory
import com.antigravity.duplicleaner.domain.model.MatchType
import com.antigravity.duplicleaner.domain.model.MediaFileItem
import com.antigravity.duplicleaner.domain.model.ResultSortOption
import com.antigravity.duplicleaner.domain.model.ScanConfig
import com.antigravity.duplicleaner.domain.model.ScanFunnelMetrics
import com.antigravity.duplicleaner.domain.model.ScanScopeMode
import com.antigravity.duplicleaner.domain.model.SmartSelectionRule
import com.antigravity.duplicleaner.domain.usecase.SmartSelectUseCase
import com.antigravity.duplicleaner.service.ForegroundScanService
import com.antigravity.duplicleaner.ui.components.CleanupSafetyHelper
import com.antigravity.duplicleaner.ui.components.FunnelAnalyticsHelper
import com.antigravity.duplicleaner.ui.theme.AppThemeMode
import com.antigravity.duplicleaner.util.AuditReportExporter
import com.antigravity.duplicleaner.util.ShareUriHelper
import com.antigravity.duplicleaner.util.ThermalBatteryMonitor
import com.antigravity.duplicleaner.util.ThrottleLevel
import com.antigravity.duplicleaner.util.ThumbnailMemoryCache
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class DuplicateDetectionPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `different filenames with identical bytes produce identical partial and full hashes`() {
        val payload = ByteArray(250_000)
        Random(42L).nextBytes(payload)

        val fileOriginal = tempFolder.newFile("VID_20261111_Camera.mp4")
        val fileRenamedCopy = tempFolder.newFile("KakaoTalk_20261111_download (1).mp4")

        FileOutputStream(fileOriginal).use { it.write(payload) }
        FileOutputStream(fileRenamedCopy).use { it.write(payload) }

        val partial1 = FastHasher.computePartialHash(fileOriginal)
        val partial2 = FastHasher.computePartialHash(fileRenamedCopy)
        assertEquals("Partial hashes must match for identical content", partial1, partial2)

        val full1 = FastHasher.computeFullHash(fileOriginal)
        val full2 = FastHasher.computeFullHash(fileRenamedCopy)
        assertEquals("Full hashes must match for identical content", full1, full2)
    }

    @Test
    fun `same file size with single byte difference in middle is rejected by Stage 2 partial hash`() {
        val size = 300_000 // > 48KB so 3-point sampling is used
        val payloadA = ByteArray(size)
        Random(100L).nextBytes(payloadA)

        val payloadB = payloadA.clone()
        // Modify 1 byte right in the middle window
        val middleIndex = size / 2
        payloadB[middleIndex] = (payloadB[middleIndex].toInt() xor 0x5A).toByte()

        val fileA = tempFolder.newFile("video_a.mp4")
        val fileB = tempFolder.newFile("video_b.mp4")
        FileOutputStream(fileA).use { it.write(payloadA) }
        FileOutputStream(fileB).use { it.write(payloadB) }

        val partialA = FastHasher.computePartialHash(fileA)
        val partialB = FastHasher.computePartialHash(fileB)

        assertNotEquals(
            "3-point (Head/Mid/Tail) partial hash must catch difference in the middle of the file",
            partialA,
            partialB
        )
    }

    @Test
    fun `perceptual dHash matches slightly re-encoded video keyframe sequences`() {
        val random = Random(777L)
        val baseFrames = List(5) {
            IntArray(72) { random.nextInt(200) + 25 }
        }
        // Simulate slight compression/re-encoding noise (+-2 luma shift on a few pixels)
        val reEncodedFrames = baseFrames.map { grid ->
            grid.clone().also { noisy ->
                noisy[10] = (noisy[10] + 2).coerceIn(0, 255)
                noisy[45] = (noisy[45] - 2).coerceIn(0, 255)
            }
        }

        val hashesOriginal = baseFrames.map { VideoPerceptualHasher.computeDHashFromGrayscaleGrid(it) }
        val hashesReEncoded = reEncodedFrames.map { VideoPerceptualHasher.computeDHashFromGrayscaleGrid(it) }

        val similarity = VideoPerceptualHasher.calculateSimilarityPercent(
            hashesA = hashesOriginal,
            hashesB = hashesReEncoded,
            durationMsA = 60_000L,
            durationMsB = 60_400L
        )

        assertTrue("Expected similarity >= 85% but was $similarity%", similarity >= 85)
    }

    @Test
    fun `smart selection preserves camera original and selects messenger or numbered duplicates for deletion`() {
        val cameraOriginal = MediaFileItem(
            id = 1L,
            uri = TestUriFactory.create("content://media/external/video/media/1"),
            displayName = "VID_20261101_Trip.mp4",
            relativePath = "DCIM/Camera",
            absolutePath = "/storage/emulated/0/DCIM/Camera/VID_20261101_Trip.mp4",
            sizeBytes = 50_000_000L,
            lastModifiedSeconds = 1_700_000_000L,
            mimeType = "video/mp4",
            category = FileCategory.VIDEO,
            width = 1920,
            height = 1080
        )

        val numberedDownload = cameraOriginal.copy(
            id = 2L,
            uri = TestUriFactory.create("content://media/external/video/media/2"),
            displayName = "VID_20261101_Trip (1).mp4",
            relativePath = "Download",
            absolutePath = "/storage/emulated/0/Download/VID_20261101_Trip (1).mp4",
            lastModifiedSeconds = 1_700_050_000L
        )

        val kakaoDuplicate = cameraOriginal.copy(
            id = 3L,
            uri = TestUriFactory.create("content://media/external/video/media/3"),
            displayName = "KakaoTalk_Video_20261102.mp4",
            relativePath = "Download/KakaoTalkDownload",
            absolutePath = "/storage/emulated/0/Download/KakaoTalkDownload/KakaoTalk_Video_20261102.mp4",
            lastModifiedSeconds = 1_700_090_000L
        )

        val group = DuplicateGroup(
            groupId = "G1",
            signature = "hash123",
            matchType = MatchType.EXACT_HASH,
            similarityPercent = 100,
            category = FileCategory.VIDEO,
            items = listOf(numberedDownload, kakaoDuplicate, cameraOriginal)
        )

        val evaluated = SmartSelectUseCase.applyRuleToGroup(group, SmartSelectionRule.SMART_ORIGINAL)

        val keeper = evaluated.items.first { !it.isSelectedForDelete }
        val toDelete = evaluated.items.filter { it.isSelectedForDelete }

        assertEquals("Camera original must be chosen as the keeper", 1L, keeper.id)
        assertEquals(2, toDelete.size)
        assertFalse(evaluated.isAllSelectedWarning)
        assertEquals(100_000_000L, evaluated.reclaimableBytes)
    }

    @Test
    fun `mp4 container inspector parses ftyp moov mdat boxes and computePayloadHash matches identical mdat streams`() {
        val mdatStream = ByteArray(72_000) // > 48KB threshold
        Random(2026L).nextBytes(mdatStream)

        val ftypPayload = "isomiso2avc1mp41".toByteArray(Charsets.US_ASCII)
        val moovPayloadA = ByteArray(600) { 0x11 }
        val moovPayloadB = ByteArray(1200) { 0x7E }
        val freePayloadB = ByteArray(256) { 0x00 }

        // File A: [ftyp][moov A (front)][mdat (64-bit largesize)]
        val bytesA = ByteArrayOutputStream().apply {
            write(buildMp4Box("ftyp", ftypPayload))
            write(buildMp4Box("moov", moovPayloadA))
            write(buildMp4Box64("mdat", mdatStream))
        }.toByteArray()

        // File B: [ftyp][free][mdat (standard 32-bit)][moov B (back, modified tags)]
        val bytesB = ByteArrayOutputStream().apply {
            write(buildMp4Box("ftyp", ftypPayload))
            write(buildMp4Box("free", freePayloadB))
            write(buildMp4Box("mdat", mdatStream))
            write(buildMp4Box("moov", moovPayloadB))
        }.toByteArray()

        val fileA = tempFolder.newFile("faststart_front_moov.mp4")
        val fileB = tempFolder.newFile("remuxed_back_moov.mp4")
        FileOutputStream(fileA).use { it.write(bytesA) }
        FileOutputStream(fileB).use { it.write(bytesB) }

        val regionA = Mp4ContainerInspector.inspectMdatPayload(fileA)
        val regionB = Mp4ContainerInspector.inspectMdatPayload(fileB)

        assertNotNull("File A mdat region must be detected", regionA)
        assertNotNull("File B mdat region must be detected", regionB)
        assertTrue(regionA!!.hasFtyp && regionA.hasMoov)
        assertTrue(regionB!!.hasFtyp && regionB.hasMoov)
        assertEquals(mdatStream.size.toLong(), regionA.length)
        assertEquals(mdatStream.size.toLong(), regionB.length)
        assertNotEquals("mdat offsets differ due to header layout", regionA.offset, regionB.offset)

        val payloadHashA = FastHasher.computePayloadHash(fileA)
        val payloadHashB = FastHasher.computePayloadHash(fileB)
        assertEquals(
            "computePayloadHash must match identical mdat payloads despite different moov/header layout",
            payloadHashA,
            payloadHashB
        )
    }

    @Test
    fun `dct perceptual pHash and combined dHash+pHash similarity accurately distinguish matching vs different keyframes`() {
        val baseFrames16x16 = List(5) { frameIdx ->
            IntArray(256) { pixelIdx ->
                val x = pixelIdx % 16
                val y = pixelIdx / 16
                ((x * 11 + y * 7 + frameIdx * 19) % 220) + 16
            }
        }

        // Simulate re-encoded frames with slight luma compression shift
        val noisyFrames16x16 = baseFrames16x16.map { grid ->
            IntArray(256) { idx ->
                val delta = if (idx % 5 == 0) 3 else if (idx % 7 == 0) -2 else 0
                (grid[idx] + delta).coerceIn(0, 255)
            }
        }

        // Completely different visual scene
        val unrelatedFrames16x16 = List(5) { frameIdx ->
            IntArray(256) { pixelIdx ->
                val x = pixelIdx % 16
                val y = pixelIdx / 16
                if ((x / 4 + y / 4 + frameIdx) % 2 == 0) 240 else 15
            }
        }

        val pHashesBase = baseFrames16x16.map { VideoPerceptualHasher.computePHashFromGrayscaleGrid(it) }
        val pHashesNoisy = noisyFrames16x16.map { VideoPerceptualHasher.computePHashFromGrayscaleGrid(it) }
        val pHashesUnrelated = unrelatedFrames16x16.map { VideoPerceptualHasher.computePHashFromGrayscaleGrid(it) }

        // Each matching frame should have low pHash Hamming distance
        for (i in pHashesBase.indices) {
            val dist = VideoPerceptualHasher.hammingDistance(pHashesBase[i], pHashesNoisy[i])
            assertTrue("Expected pHash Hamming distance <= 8 for frame $i, got $dist", dist <= 8)
        }

        val dHashesBase = List(5) { idx ->
            val grid9x8 = IntArray(72) { k -> baseFrames16x16[idx][k] }
            VideoPerceptualHasher.computeDHashFromGrayscaleGrid(grid9x8)
        }
        val dHashesNoisy = List(5) { idx ->
            val grid9x8 = IntArray(72) { k -> noisyFrames16x16[idx][k] }
            VideoPerceptualHasher.computeDHashFromGrayscaleGrid(grid9x8)
        }
        val dHashesUnrelated = List(5) { idx ->
            val grid9x8 = IntArray(72) { k -> unrelatedFrames16x16[idx][255 - k] }
            VideoPerceptualHasher.computeDHashFromGrayscaleGrid(grid9x8)
        }

        val combinedSimilar = VideoPerceptualHasher.combineDHashAndPHashSimilarity(
            dHashesA = dHashesBase,
            dHashesB = dHashesNoisy,
            pHashesA = pHashesBase,
            pHashesB = pHashesNoisy
        )
        assertTrue("Expected combined dHash+pHash similarity >= 85%, got $combinedSimilar%", combinedSimilar >= 85)

        val combinedUnrelated = VideoPerceptualHasher.combineDHashAndPHashSimilarity(
            dHashesA = dHashesBase,
            dHashesB = dHashesUnrelated,
            pHashesA = pHashesBase,
            pHashesB = pHashesUnrelated
        )
        assertEquals("Unrelated scenes must return 0% similarity", 0, combinedUnrelated)
    }

    @Test
    fun `smart selection protectedFolderKeywords preserves all copies in protected folders when outside copy exists`() {
        val olderDownloadCopy = MediaFileItem(
            id = 10L,
            uri = TestUriFactory.create("content://media/external/video/media/10"),
            displayName = "Clip_2026.mp4",
            relativePath = "Download",
            absolutePath = "/storage/emulated/0/Download/Clip_2026.mp4",
            sizeBytes = 40_000_000L,
            lastModifiedSeconds = 1_600_000_000L, // Older than protected copies
            mimeType = "video/mp4",
            category = FileCategory.VIDEO,
            width = 1920,
            height = 1080
        )

        val protectedImportantCopy = olderDownloadCopy.copy(
            id = 11L,
            uri = TestUriFactory.create("content://media/external/video/media/11"),
            displayName = "Clip_2026 (1).mp4",
            relativePath = "Movies/Important",
            absolutePath = "/storage/emulated/0/Movies/Important/Clip_2026 (1).mp4",
            lastModifiedSeconds = 1_700_000_000L
        )

        val protectedVaultCopy = olderDownloadCopy.copy(
            id = 12L,
            uri = TestUriFactory.create("content://media/external/video/media/12"),
            displayName = "Clip_2026_backup.mp4",
            relativePath = "FamilyVault/2026",
            absolutePath = "/storage/emulated/0/FamilyVault/2026/Clip_2026_backup.mp4",
            lastModifiedSeconds = 1_710_000_000L
        )

        val group = DuplicateGroup(
            groupId = "G_PROTECTED",
            signature = "hash_prot",
            matchType = MatchType.EXACT_HASH,
            similarityPercent = 100,
            category = FileCategory.VIDEO,
            items = listOf(olderDownloadCopy, protectedImportantCopy, protectedVaultCopy)
        )

        val protectedKeywords = setOf("Important", "FamilyVault")
        val evaluated = SmartSelectUseCase.applyRuleToGroup(
            group = group,
            rule = SmartSelectionRule.SMART_ORIGINAL,
            protectedFolderKeywords = protectedKeywords
        )

        val keptItems = evaluated.items.filter { !it.isSelectedForDelete }
        val deletedItems = evaluated.items.filter { it.isSelectedForDelete }

        assertEquals("Both protected folder items must be kept", 2, keptItems.size)
        assertTrue(keptItems.all { it.id == 11L || it.id == 12L })
        assertTrue(
            "Protected items must carry the protected folder badge reason",
            keptItems.all { it.keepBadgeReason == SmartSelectUseCase.PROTECTED_FOLDER_BADGE_REASON }
        )

        assertEquals("Only the non-protected Download copy should be selected for deletion", 1, deletedItems.size)
        assertEquals(10L, deletedItems.first().id)
    }

    @Test
    fun `mp4 files with different moov sizes and different total file sizes but identical mdat stream match payload length and hash`() {
        val mdatStream = ByteArray(120_000)
        Random(8888L).nextBytes(mdatStream)

        val ftypPayload = "isomiso2avc1mp41".toByteArray(Charsets.US_ASCII)
        val moovSmall = ByteArray(4 * 1024) { 0x22 }
        val moovLarge = ByteArray(12 * 1024) { 0x55 }

        val bytesFastStart = ByteArrayOutputStream().apply {
            write(buildMp4Box("ftyp", ftypPayload))
            write(buildMp4Box("moov", moovSmall))
            write(buildMp4Box("mdat", mdatStream))
        }.toByteArray()

        val bytesRemuxed = ByteArrayOutputStream().apply {
            write(buildMp4Box("ftyp", ftypPayload))
            write(buildMp4Box("mdat", mdatStream))
            write(buildMp4Box("moov", moovLarge))
        }.toByteArray()

        val fileFastStart = tempFolder.newFile("Clip_2026_Original_FastStart.mp4")
        val fileRemuxed = tempFolder.newFile("Clip_2026_ReMuxed_Meta.mp4")
        FileOutputStream(fileFastStart).use { it.write(bytesFastStart) }
        FileOutputStream(fileRemuxed).use { it.write(bytesRemuxed) }

        assertNotEquals(
            "Total file sizes must differ due to different moov metadata sizes",
            fileFastStart.length(),
            fileRemuxed.length()
        )

        val regionFastStart = Mp4ContainerInspector.inspectMdatPayload(fileFastStart)
        val regionRemuxed = Mp4ContainerInspector.inspectMdatPayload(fileRemuxed)

        assertNotNull(regionFastStart)
        assertNotNull(regionRemuxed)
        assertEquals(regionFastStart!!.length, regionRemuxed!!.length)
        assertEquals(
            FastHasher.computePayloadHash(fileFastStart),
            FastHasher.computePayloadHash(fileRemuxed)
        )
    }

    @Test
    fun `selectFolderDuplicatesSafely selects items in target folder while preserving at least one keeper when all items are in target folder`() {
        val baseItem = MediaFileItem(
            id = 101L,
            uri = TestUriFactory.create("content://media/external/video/media/101"),
            displayName = "KakaoTalk_2026_A.mp4",
            relativePath = "Download/KakaoTalkDownload",
            absolutePath = "/storage/emulated/0/Download/KakaoTalkDownload/KakaoTalk_2026_A.mp4",
            sizeBytes = 25_000_000L,
            lastModifiedSeconds = 1_700_000_000L,
            mimeType = "video/mp4",
            category = FileCategory.VIDEO,
            width = 1920,
            height = 1080,
            isSelectedForDelete = false
        )

        val secondInSameFolder = baseItem.copy(
            id = 102L,
            uri = TestUriFactory.create("content://media/external/video/media/102"),
            displayName = "KakaoTalk_2026_A (1).mp4",
            lastModifiedSeconds = 1_700_010_000L,
            isSelectedForDelete = true
        )

        val cameraItem = baseItem.copy(
            id = 201L,
            uri = TestUriFactory.create("content://media/external/video/media/201"),
            displayName = "VID_2026_Camera.mp4",
            relativePath = "DCIM/Camera",
            absolutePath = "/storage/emulated/0/DCIM/Camera/VID_2026_Camera.mp4",
            isSelectedForDelete = false
        )

        val kakaoCopyOfCamera = baseItem.copy(
            id = 202L,
            uri = TestUriFactory.create("content://media/external/video/media/202"),
            displayName = "KakaoTalk_2026_B.mp4",
            isSelectedForDelete = false
        )

        // Group 1: ALL items are inside KakaoTalkDownload
        val groupAllInTarget = DuplicateGroup(
            groupId = "G_ALL_TARGET",
            signature = "sig_all",
            matchType = MatchType.EXACT_HASH,
            similarityPercent = 100,
            category = FileCategory.VIDEO,
            items = listOf(baseItem, secondInSameFolder)
        )

        // Group 2: Mixed folders (DCIM/Camera + KakaoTalkDownload)
        val groupMixed = DuplicateGroup(
            groupId = "G_MIXED",
            signature = "sig_mixed",
            matchType = MatchType.EXACT_HASH,
            similarityPercent = 100,
            category = FileCategory.VIDEO,
            items = listOf(cameraItem, kakaoCopyOfCamera)
        )

        val result = SmartSelectUseCase.selectFolderDuplicatesSafely(
            groups = listOf(groupAllInTarget, groupMixed),
            targetFolderKeyword = "KakaoTalkDownload",
            protectedFolderKeywords = setOf("DCIM/Camera")
        )

        val updatedAllTarget = result[0]
        val keepersInAllTarget = updatedAllTarget.items.filter { !it.isSelectedForDelete }
        assertEquals(
            "Must strictly preserve at least 1 keeper even when all items are in target folder",
            1,
            keepersInAllTarget.size
        )
        assertEquals(101L, keepersInAllTarget.first().id)
        assertEquals(
            SmartSelectUseCase.FOLDER_AUTO_PROTECT_BADGE_REASON,
            keepersInAllTarget.first().keepBadgeReason
        )
        assertFalse(updatedAllTarget.isAllSelectedWarning)

        val updatedMixed = result[1]
        assertFalse(updatedMixed.items.first { it.id == 201L }.isSelectedForDelete)
        assertTrue(updatedMixed.items.first { it.id == 202L }.isSelectedForDelete)
    }

    @Test
    fun `ScanFunnelMetrics calculateSavedIo accurately computes saved disk IO bytes`() {
        val totalBytes = 10L * 1024L * 1024L * 1024L // 10 GB
        val actualRead = 120L * 1024L * 1024L        // 120 MB
        val saved = ScanFunnelMetrics.calculateSavedIo(totalBytes, actualRead)
        assertEquals(totalBytes - actualRead, saved)

        // Edge case: when actualRead >= totalBytes, saved I/O is clamped to 0L
        assertEquals(0L, ScanFunnelMetrics.calculateSavedIo(1_000L, 1_500L))
    }

    @Test
    fun `HammingBkTree search within distance 8 across 300 hashes matches brute force 100 percent`() {
        val rng = Random(20261111L)
        val bkTree = HammingBkTree<Int>()
        val entries = mutableListOf<Pair<Int, Long>>()

        val anchorHash = rng.nextLong()
        for (id in 0 until 300) {
            val hash = when {
                id == 0 -> anchorHash
                id == 1 -> anchorHash // Duplicate hash (distance 0)
                id < 25 -> {
                    // Inject hashes within Hamming distance 1..8 of anchorHash
                    var mutated = anchorHash
                    val flips = (id % 8) + 1
                    for (bit in 0 until flips) {
                        val pos = (id * 7 + bit * 13) and 63
                        mutated = mutated xor (1L shl pos)
                    }
                    mutated
                }
                else -> rng.nextLong()
            }
            entries.add(id to hash)
            bkTree.insert(hash, id)
        }

        assertEquals(300, bkTree.size)

        val queryHashes = listOf(anchorHash, entries[50].second, entries[150].second)
        for (query in queryHashes) {
            val expectedIds = entries
                .filter { (_, h) -> VideoPerceptualHasher.hammingDistance(query, h) <= 8 }
                .map { it.first }
                .sorted()

            val bkIds = bkTree.searchWithinDistance(query, maxDistance = 8)
                .map { it.item }
                .sorted()

            assertEquals(
                "BK-Tree search results must match brute-force search 100%",
                expectedIds,
                bkIds
            )
        }
    }

    @Test
    fun `TemporalSequenceAligner alignSubsequenceSimilarity matches 8-frame original with 5-frame trimmed subsequence`() {
        val rng = Random(9090L)
        val originalDHashes = List(8) { rng.nextLong() }
        val originalPHashes = List(8) { rng.nextLong() }

        // Trimmed subsequence: frames 2..6 (5 frames) with slight 2-bit perceptual noise per frame
        val trimmedDHashes = (2..6).map { idx ->
            originalDHashes[idx] xor (1L shl (idx * 3)) xor (1L shl (idx * 3 + 1))
        }
        val trimmedPHashes = (2..6).map { idx ->
            originalPHashes[idx] xor (1L shl (idx * 5))
        }

        val result = TemporalSequenceAligner.alignSubsequenceSimilarity(
            dHashesA = originalDHashes,
            dHashesB = trimmedDHashes,
            pHashesA = originalPHashes,
            pHashesB = trimmedPHashes
        )

        assertTrue("Trimmed clip must be flagged as subsequence trimmed", result.isSubsequenceTrimmed)
        assertTrue(
            "Expected similarityPercent >= 85%, got ${result.similarityPercent}%",
            result.similarityPercent >= 85
        )
        assertEquals(5, result.matchedFrames)
        assertEquals(2, result.bestOffset)
    }

    @Test
    fun `AuditReportExporter buildMarkdownAuditReport contains group signatures match types and saved IO bytes`() {
        val keptItem = MediaFileItem(
            id = 501L,
            uri = TestUriFactory.create("content://media/external/video/media/501"),
            displayName = "VID_Original_4K.mp4",
            relativePath = "DCIM/Camera",
            absolutePath = "/storage/emulated/0/DCIM/Camera/VID_Original_4K.mp4",
            sizeBytes = 120_000_000L,
            lastModifiedSeconds = 1_700_000_000L,
            mimeType = "video/mp4",
            category = FileCategory.VIDEO,
            durationMs = 45_000L,
            width = 3840,
            height = 2160,
            isSelectedForDelete = false,
            keepBadgeReason = "카메라 원본"
        )
        val trimmedCopy = keptItem.copy(
            id = 502L,
            uri = TestUriFactory.create("content://media/external/video/media/502"),
            displayName = "VID_Original_4K_Trimmed.mp4",
            relativePath = "Movies/Edited",
            absolutePath = "/storage/emulated/0/Movies/Edited/VID_Original_4K_Trimmed.mp4",
            sizeBytes = 65_000_000L,
            durationMs = 24_000L,
            isSelectedForDelete = true,
            keepBadgeReason = null
        )

        val group = DuplicateGroup(
            groupId = "TRIMMED_1",
            signature = "trim:a1b2c3d4e5f6",
            matchType = MatchType.TRIMMED_CLIP_MATCH,
            similarityPercent = 94,
            category = FileCategory.VIDEO,
            items = listOf(keptItem, trimmedCopy)
        )

        val funnel = ScanFunnelMetrics(
            totalIndexedFiles = 200,
            totalIndexedBytes = 5_000_000_000L,
            sizeFilteredCandidates = 40,
            partialHashCandidates = 12,
            fullHashCandidates = 8,
            actualBytesRead = 100_000_000L,
            savedIoBytes = 4_900_000_000L
        )

        val markdown = AuditReportExporter.buildMarkdownAuditReport(
            groups = listOf(group),
            largestFiles = listOf(keptItem, trimmedCopy),
            funnelMetrics = funnel,
            cumulativeFreedBytes = 250_000_000L
        )

        assertTrue("Report must contain group signature", markdown.contains("trim:a1b2c3d4e5f6"))
        assertTrue("Report must contain match type badge label", markdown.contains(MatchType.TRIMMED_CLIP_MATCH.badgeLabel))
        assertTrue("Report must contain match type enum name", markdown.contains("TRIMMED_CLIP_MATCH"))
        assertTrue("Report must contain savedIoBytes value", markdown.contains("4900000000"))
        assertTrue("Report must contain top large file name", markdown.contains("VID_Original_4K.mp4"))
    }

    @Test
    fun `MatchType TRIMMED_CLIP_MATCH and ScanConfig similarity threshold and ignored signatures behave properly`() {
        val defaultConfig = ScanConfig()
        assertEquals(85, defaultConfig.similarityThresholdPercent)
        assertTrue(defaultConfig.ignoredGroupSignatures.isEmpty())
        assertEquals("구간 편집·클립 일치", MatchType.TRIMMED_CLIP_MATCH.badgeLabel)
        assertEquals(3, CleanerViewTab.entries.size)

        val customConfig = defaultConfig.copy(
            similarityThresholdPercent = 90,
            ignoredGroupSignatures = setOf("sig_ignore_1", "sig_ignore_2")
        )

        val dummyItem1 = MediaFileItem(
            id = 1L,
            uri = TestUriFactory.create("content://media/external/video/media/1"),
            displayName = "A.mp4",
            relativePath = "Movies",
            absolutePath = null,
            sizeBytes = 10_000_000L,
            lastModifiedSeconds = 1_700_000_000L,
            mimeType = "video/mp4",
            category = FileCategory.VIDEO
        )
        val dummyItem2 = dummyItem1.copy(
            id = 2L,
            uri = TestUriFactory.create("content://media/external/video/media/2"),
            displayName = "B.mp4",
            isSelectedForDelete = true
        )

        val groups = listOf(
            DuplicateGroup(
                groupId = "G1",
                signature = "sig_ignore_1",
                matchType = MatchType.EXACT_HASH,
                similarityPercent = 100,
                category = FileCategory.VIDEO,
                items = listOf(dummyItem1, dummyItem2)
            ),
            DuplicateGroup(
                groupId = "G2",
                signature = "sig_keep_active",
                matchType = MatchType.TRIMMED_CLIP_MATCH,
                similarityPercent = 92,
                category = FileCategory.VIDEO,
                items = listOf(dummyItem1, dummyItem2)
            )
        )

        val activeGroups = groups.filterNot { it.signature in customConfig.ignoredGroupSignatures }
        assertEquals(1, activeGroups.size)
        assertEquals("sig_keep_active", activeGroups.first().signature)
        assertEquals(MatchType.TRIMMED_CLIP_MATCH, activeGroups.first().matchType)
        assertTrue(activeGroups.first().similarityPercent >= customConfig.similarityThresholdPercent)
    }

    @Test
    fun `MediaContainerInspector matches two MP3 files with different ID3v2 tag sizes and identical 100KB audio payload`() {
        val audioPayload = ByteArray(100 * 1024).also { Random(554433L).nextBytes(it) }
        // Place MPEG-1 Layer 3 sync word (0xFF, 0xFB) at frame boundary
        audioPayload[0] = 0xFF.toByte()
        audioPayload[1] = 0xFB.toByte()

        val bytesMp3SmallTag = buildMp3WithId3v2(totalTagBytes = 2 * 1024, tagFill = 0x1A, audioPayload = audioPayload)
        val bytesMp3LargeTag = buildMp3WithId3v2(totalTagBytes = 10 * 1024, tagFill = 0x5C, audioPayload = audioPayload)

        val mp3A = tempFolder.newFile("Podcast_Original_2KB_Tag.mp3")
        val mp3B = tempFolder.newFile("Podcast_Retagged_10KB_Tag.mp3")
        FileOutputStream(mp3A).use { it.write(bytesMp3SmallTag) }
        FileOutputStream(mp3B).use { it.write(bytesMp3LargeTag) }

        assertNotEquals(
            "Total file sizes must differ due to different ID3v2 tag sizes",
            mp3A.length(),
            mp3B.length()
        )

        val regionA = MediaContainerInspector.inspectMp3AudioPayload(mp3A)
        val regionB = MediaContainerInspector.inspectMp3AudioPayload(mp3B)
        assertNotNull("MP3 A audio payload region must be detected", regionA)
        assertNotNull("MP3 B audio payload region must be detected", regionB)
        assertEquals(2L * 1024L, regionA!!.offset)
        assertEquals(10L * 1024L, regionB!!.offset)
        assertEquals(audioPayload.size.toLong(), regionA.length)
        assertEquals(audioPayload.size.toLong(), regionB.length)

        val hashA = MediaContainerInspector.computeContainerPayloadHash(mp3A)
        val hashB = MediaContainerInspector.computeContainerPayloadHash(mp3B)
        assertNotNull(hashA)
        assertNotNull(hashB)
        assertEquals(
            "computeContainerPayloadHash must match identical MP3 MPEG streams despite different ID3v2 headers",
            hashA,
            hashB
        )
    }

    @Test
    fun `VisualQualityScorer assigns significantly higher sharpness score to sharp high-contrast edge grid than flat blurred grid`() {
        val width = 32
        val height = 32

        // Flat / soft blurred grayscale grid (near-zero 2D Laplacian response)
        val blurredGrid = IntArray(width * height) { idx ->
            val x = idx % width
            120 + (x / 8)
        }

        // High-contrast sharp edge grid (strong alternating checkerboard edges)
        val sharpGrid = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            if ((x + y) % 2 == 0) 245 else 10
        }

        val blurredScore = VisualQualityScorer.computeSharpnessScoreFromGrayscaleGrid(blurredGrid, width, height)
        val sharpScore = VisualQualityScorer.computeSharpnessScoreFromGrayscaleGrid(sharpGrid, width, height)

        assertTrue("Expected blurred grid sharpness score < 25, got $blurredScore", blurredScore < 25)
        assertTrue("Expected sharp grid sharpness score >= 70, got $sharpScore", sharpScore >= 70)
        assertTrue(
            "Sharp score ($sharpScore) must be significantly higher than blurred score ($blurredScore)",
            sharpScore - blurredScore >= 50
        )
    }

    @Test
    fun `SmartSelectUseCase prefers item with higher sharpnessScore when resolution and size are equal`() {
        val blurryItem = MediaFileItem(
            id = 701L,
            uri = TestUriFactory.create("content://media/external/images/media/701"),
            displayName = "IMG_20261111_A.jpg",
            relativePath = "Pictures",
            absolutePath = "/storage/emulated/0/Pictures/IMG_20261111_A.jpg",
            sizeBytes = 4_200_000L,
            lastModifiedSeconds = 1_700_000_000L,
            mimeType = "image/jpeg",
            category = FileCategory.IMAGE,
            width = 1920,
            height = 1080,
            sharpnessScore = 18
        )

        val sharpItem = blurryItem.copy(
            id = 702L,
            uri = TestUriFactory.create("content://media/external/images/media/702"),
            displayName = "IMG_20261111_B.jpg",
            sharpnessScore = 88
        )

        val group = DuplicateGroup(
            groupId = "G_SHARP",
            signature = "pHash:sharpness",
            matchType = MatchType.EXACT_HASH,
            similarityPercent = 100,
            category = FileCategory.IMAGE,
            items = listOf(blurryItem, sharpItem)
        )

        val evaluatedQuality = SmartSelectUseCase.applyRuleToGroup(
            group = group,
            rule = SmartSelectionRule.KEEP_HIGHEST_QUALITY
        )
        val qualityKeeper = evaluatedQuality.items.first { !it.isSelectedForDelete }
        assertEquals("KEEP_HIGHEST_QUALITY must keep the sharper item", 702L, qualityKeeper.id)
        assertTrue(evaluatedQuality.items.first { it.id == 701L }.isSelectedForDelete)

        val evaluatedSmart = SmartSelectUseCase.applyRuleToGroup(
            group = group,
            rule = SmartSelectionRule.SMART_ORIGINAL
        )
        val smartKeeper = evaluatedSmart.items.first { !it.isSelectedForDelete }
        assertEquals("SMART_ORIGINAL tie-breaking must keep the sharper item", 702L, smartKeeper.id)
    }

    @Test
    fun `QuarantineVaultManager quarantines file restores it to original path and empties vault cleanly`() {
        val vaultDir = tempFolder.newFolder("quarantine_vault_test")
        val sourceDir = tempFolder.newFolder("media_source_test")
        val vaultManager = QuarantineVaultManager(vaultDir)

        val originalContent = ByteArray(4096) { idx -> (idx % 251).toByte() }
        val sourceFile = File(sourceDir, "duplicate_clip.mp4")
        FileOutputStream(sourceFile).use { it.write(originalContent) }
        assertTrue(sourceFile.exists())

        // 1. Quarantine file
        val record = vaultManager.quarantineFile(sourceFile)
        assertNotNull("Quarantine record must be returned", record)
        assertFalse("Original source file must be moved out", sourceFile.exists())
        val vaultedFile = File(record!!.vaultFilePath)
        assertTrue("Quarantined file must exist inside vault", vaultedFile.exists())
        assertEquals(1, vaultManager.getQuarantinedItems().size)
        assertEquals(4096L, vaultManager.getTotalQuarantinedBytes())

        // 2. Restore file back to original path
        val restoredCount = vaultManager.restoreAllQuarantinedFiles()
        assertEquals(1, restoredCount)
        assertTrue("File must be restored to its original path", sourceFile.exists())
        assertEquals(4096L, sourceFile.length())
        assertFalse("Vault file must be moved out upon restore", vaultedFile.exists())
        assertTrue(vaultManager.getQuarantinedItems().isEmpty())
        assertEquals(0L, vaultManager.getTotalQuarantinedBytes())

        // 3. Re-quarantine and empty vault cleanly
        val recordAgain = vaultManager.quarantineFile(sourceFile)
        assertNotNull(recordAgain)
        assertEquals(1, vaultManager.getQuarantinedItems().size)
        val freedBytes = vaultManager.emptyQuarantineVault()
        assertEquals(4096L, freedBytes)
        assertTrue(vaultManager.getQuarantinedItems().isEmpty())
        assertEquals(0L, vaultManager.getTotalQuarantinedBytes())
        assertFalse(File(recordAgain!!.vaultFilePath).exists())
    }

    @Test
    fun `MetadataAuthenticityInspector computeScoreFromTags scores full EXIF tags as 100 and stripped tags as 0`() {
        val fullExifScore = MetadataAuthenticityInspector.computeScoreFromTags(
            hasOriginalDateTime = true,
            hasCameraModel = true,
            hasExposureParams = true,
            hasGps = true
        )
        assertEquals(100, fullExifScore)

        val strippedScore = MetadataAuthenticityInspector.computeScoreFromTags(
            hasOriginalDateTime = false,
            hasCameraModel = false,
            hasExposureParams = false,
            hasGps = false
        )
        assertEquals(0, strippedScore)

        val cameraWithoutGpsScore = MetadataAuthenticityInspector.computeScoreFromTags(
            hasOriginalDateTime = true,
            hasCameraModel = true,
            hasExposureParams = true,
            hasGps = false
        )
        assertEquals(90, cameraWithoutGpsScore)

        val partialDateOnlyScore = MetadataAuthenticityInspector.computeScoreFromTags(
            hasOriginalDateTime = true,
            hasCameraModel = false,
            hasExposureParams = false,
            hasGps = false
        )
        assertEquals(40, partialDateOnlyScore)
    }

    @Test
    fun `SmartSelectUseCase preserves higher authenticityScore EXIF camera original even when newer than stripped copy`() {
        val olderStrippedCopy = MediaFileItem(
            id = 801L,
            uri = TestUriFactory.create("content://media/external/images/media/801"),
            displayName = "IMG_Trip_2026.jpg",
            relativePath = "Pictures",
            absolutePath = "/storage/emulated/0/Pictures/IMG_Trip_2026.jpg",
            sizeBytes = 3_500_000L,
            lastModifiedSeconds = 1_650_000_000L, // Older timestamp
            mimeType = "image/jpeg",
            category = FileCategory.IMAGE,
            width = 1920,
            height = 1080,
            authenticityScore = 0 // Stripped EXIF tags
        )

        val newerExifOriginal = olderStrippedCopy.copy(
            id = 802L,
            uri = TestUriFactory.create("content://media/external/images/media/802"),
            displayName = "IMG_Trip_2026_Camera.jpg",
            lastModifiedSeconds = 1_750_000_000L, // Newer timestamp
            authenticityScore = 100 // Full EXIF tags preserved
        )

        val group = DuplicateGroup(
            groupId = "G_EXIF_AUTH",
            signature = "pHash:exif_auth",
            matchType = MatchType.SIMILAR_VIDEO,
            similarityPercent = 96,
            category = FileCategory.IMAGE,
            items = listOf(olderStrippedCopy, newerExifOriginal)
        )

        val evaluatedSmart = SmartSelectUseCase.applyRuleToGroup(
            group = group,
            rule = SmartSelectionRule.SMART_ORIGINAL
        )
        val smartKeeper = evaluatedSmart.items.first { !it.isSelectedForDelete }
        assertEquals(
            "SMART_ORIGINAL must keep EXIF authentic original even when newer than stripped copy",
            802L,
            smartKeeper.id
        )
        assertEquals(SmartSelectUseCase.EXIF_AUTHENTIC_BADGE_REASON, smartKeeper.keepBadgeReason)
        assertTrue(evaluatedSmart.items.first { it.id == 801L }.isSelectedForDelete)

        val evaluatedQuality = SmartSelectUseCase.applyRuleToGroup(
            group = group,
            rule = SmartSelectionRule.KEEP_HIGHEST_QUALITY
        )
        val qualityKeeper = evaluatedQuality.items.first { !it.isSelectedForDelete }
        assertEquals(
            "KEEP_HIGHEST_QUALITY tie-breaker must keep EXIF authentic original over older stripped copy",
            802L,
            qualityKeeper.id
        )
    }

    @Test
    fun `QuarantineVaultManager restoreSingleItem and deleteSingleVaultItem independently restore or permanently delete items`() {
        val vaultDir = tempFolder.newFolder("quarantine_single_vault")
        val sourceDir = tempFolder.newFolder("quarantine_single_source")
        val vaultManager = QuarantineVaultManager(vaultDir)

        val fileA = File(sourceDir, "video_a.mp4")
        val fileB = File(sourceDir, "video_b.mp4")
        FileOutputStream(fileA).use { it.write(ByteArray(2048) { 0x11 }) }
        FileOutputStream(fileB).use { it.write(ByteArray(3072) { 0x22 }) }

        val recA = vaultManager.quarantineFile(fileA)
        val recB = vaultManager.quarantineFile(fileB)
        assertNotNull(recA)
        assertNotNull(recB)
        assertFalse(fileA.exists())
        assertFalse(fileB.exists())
        assertEquals(2, vaultManager.getQuarantinedItems().size)
        assertEquals(5120L, vaultManager.getTotalQuarantinedBytes())

        // 1. Restore only item A
        val restoredA = vaultManager.restoreSingleItem(recA!!.id)
        assertTrue("restoreSingleItem must return true for existing item A", restoredA)
        assertTrue("fileA must be restored to originalPath", fileA.exists())
        assertEquals(2048L, fileA.length())
        assertFalse("Vault file for A must be removed", File(recA.vaultFilePath).exists())
        assertFalse("fileB must remain absent from sourceDir", fileB.exists())
        assertEquals(1, vaultManager.getQuarantinedItems().size)
        assertEquals(recB!!.id, vaultManager.getQuarantinedItems().first().id)
        assertEquals(3072L, vaultManager.getTotalQuarantinedBytes())

        // Non-existent item restore returns false safely
        assertFalse(vaultManager.restoreSingleItem("non-existent-id"))

        // 2. Permanently delete only item B from vault
        val deletedBytesB = vaultManager.deleteSingleVaultItem(recB.id)
        assertEquals(3072L, deletedBytesB)
        assertFalse("Vault file for B must be permanently deleted", File(recB.vaultFilePath).exists())
        assertFalse("fileB must not be restored on permanent delete", fileB.exists())
        assertTrue(vaultManager.getQuarantinedItems().isEmpty())
        assertEquals(0L, vaultManager.getTotalQuarantinedBytes())

        // Non-existent item delete returns 0L safely
        assertEquals(0L, vaultManager.deleteSingleVaultItem("non-existent-id"))
    }

    @Test
    fun `AutoScanInterval values and ScanConfig targetFolderKeywords defaults behave properly`() {
        val defaultConfig = ScanConfig()
        assertTrue(
            "Default targetFolderKeywords must be empty (full scan)",
            defaultConfig.targetFolderKeywords.isEmpty()
        )

        val customConfig = defaultConfig.copy(
            targetFolderKeywords = setOf("Download", "KakaoTalk")
        )
        assertEquals(setOf("Download", "KakaoTalk"), customConfig.targetFolderKeywords)

        assertEquals(4, AutoScanInterval.entries.size)
        assertEquals(0L, AutoScanInterval.OFF.intervalHours)
        assertEquals(24L, AutoScanInterval.DAILY.intervalHours)
        assertEquals(72L, AutoScanInterval.EVERY_3_DAYS.intervalHours)
        assertEquals(168L, AutoScanInterval.WEEKLY.intervalHours)
    }

    @Test
    fun `FileIntegrityGuard deduplicateByCanonicalPath removes sdcard and storage emulated mount aliases cleanly`() {
        val rawPaths = listOf(
            "/sdcard/DCIM/Camera/VID_Trip.mp4",
            "/storage/emulated/0/DCIM/Camera/VID_Trip.mp4",
            "/mnt/sdcard/DCIM/Camera/VID_Trip.mp4",
            "/storage/emulated/0/Movies/OtherClip.mp4",
            "/sdcard/Movies/OtherClip.mp4"
        )

        val deduped = FileIntegrityGuard.deduplicateByCanonicalPath(rawPaths)

        assertEquals(2, deduped.size)
        assertEquals("/sdcard/DCIM/Camera/VID_Trip.mp4", deduped[0])
        assertEquals("/storage/emulated/0/Movies/OtherClip.mp4", deduped[1])
        assertEquals(
            "/storage/emulated/0/DCIM/Camera/VID_Trip.mp4",
            FileIntegrityGuard.normalizeMountAlias("/sdcard/DCIM/Camera/VID_Trip.mp4")
        )
    }

    @Test
    fun `CorruptedFileDetector detectCorruptedReason catches 0-byte part and zero-filled mp4 files while ignoring valid mp4`() {
        val zeroByteFile = tempFolder.newFile("empty_video.mp4")
        val zeroReason = CorruptedFileDetector.detectCorruptedReason(zeroByteFile)
        assertNotNull("0-byte file must be detected as corrupted", zeroReason)
        assertEquals("0바이트 빈 파일", zeroReason)

        val partFile = tempFolder.newFile("unfinished_download.part")
        FileOutputStream(partFile).use { it.write(ByteArray(1024) { 0x41 }) }
        val partReason = CorruptedFileDetector.detectCorruptedReason(partFile)
        assertNotNull(".part temp file must be detected as corrupted", partReason)
        assertTrue(partReason!!.contains(".part"))

        val zeroHeaderMp4 = tempFolder.newFile("broken_zero_header.mp4")
        FileOutputStream(zeroHeaderMp4).use { it.write(ByteArray(128) { 0x00 }) }
        val zeroHeaderReason = CorruptedFileDetector.detectCorruptedReason(zeroHeaderMp4)
        assertNotNull("Zero-filled 64+ byte MP4 header must be detected as corrupted", zeroHeaderReason)
        assertTrue(zeroHeaderReason!!.contains("0x00"))

        val validMp4 = tempFolder.newFile("valid_clip.mp4")
        val validBytes = buildMp4Box("ftyp", "mp42isom".toByteArray(Charsets.US_ASCII)) + ByteArray(64) { 0x2A }
        FileOutputStream(validMp4).use { it.write(validBytes) }
        val validReason = CorruptedFileDetector.detectCorruptedReason(validMp4)
        assertNull("Valid MP4 header must return null", validReason)
    }

    @Test
    fun `MediaTechSpecsInspector computeCodecQualityRank orders AV1 gt HEVC gt VP9 gt AVC`() {
        val rankAv1 = MediaTechSpecsInspector.computeCodecQualityRank("video/av01")
        val rankHevc = MediaTechSpecsInspector.computeCodecQualityRank("video/hevc")
        val rankVp9 = MediaTechSpecsInspector.computeCodecQualityRank("video/x-vnd.on2.vp9")
        val rankAvc = MediaTechSpecsInspector.computeCodecQualityRank("video/avc")
        val rankUnknown = MediaTechSpecsInspector.computeCodecQualityRank("video/unknown")

        assertEquals(4, rankAv1)
        assertEquals(3, rankHevc)
        assertEquals(2, rankVp9)
        assertEquals(1, rankAvc)
        assertEquals(0, rankUnknown)
        assertTrue(rankAv1 > rankHevc && rankHevc > rankVp9 && rankVp9 > rankAvc && rankAvc > rankUnknown)
    }

    @Test
    fun `SmartSelectUseCase preserves audio-intact video over silent copy with AUDIO_INTACT_BADGE_REASON`() {
        val silentCameraCopy = MediaFileItem(
            id = 901L,
            uri = TestUriFactory.create("content://media/external/video/media/901"),
            displayName = "VID_20261111_Clip.mp4",
            relativePath = "Movies",
            absolutePath = "/storage/emulated/0/Movies/VID_20261111_Clip.mp4",
            sizeBytes = 35_000_000L,
            lastModifiedSeconds = 1_700_000_000L,
            mimeType = "video/mp4",
            category = FileCategory.VIDEO,
            durationMs = 20_000L,
            width = 1920,
            height = 1080,
            hasAudioTrack = false,
            videoCodec = "AVC/H.264",
            actualBitrateKbps = 14_000
        )

        val audioIntactCopy = silentCameraCopy.copy(
            id = 902L,
            uri = TestUriFactory.create("content://media/external/video/media/902"),
            displayName = "VID_20261111_Clip_Audio.mp4",
            lastModifiedSeconds = 1_700_010_000L,
            hasAudioTrack = true,
            videoCodec = "HEVC/H.265",
            actualBitrateKbps = 14_000
        )

        val group = DuplicateGroup(
            groupId = "G_AUDIO_CHECK",
            signature = "sig_audio_check",
            matchType = MatchType.EXACT_HASH,
            similarityPercent = 100,
            category = FileCategory.VIDEO,
            items = listOf(silentCameraCopy, audioIntactCopy)
        )

        val evaluatedSmart = SmartSelectUseCase.applyRuleToGroup(
            group = group,
            rule = SmartSelectionRule.SMART_ORIGINAL
        )
        val smartKeeper = evaluatedSmart.items.first { !it.isSelectedForDelete }
        assertEquals("SMART_ORIGINAL must preserve audio-intact video", 902L, smartKeeper.id)
        assertEquals(SmartSelectUseCase.AUDIO_INTACT_BADGE_REASON, smartKeeper.keepBadgeReason)
        assertTrue(evaluatedSmart.items.first { it.id == 901L }.isSelectedForDelete)

        val evaluatedQuality = SmartSelectUseCase.applyRuleToGroup(
            group = group,
            rule = SmartSelectionRule.KEEP_HIGHEST_QUALITY
        )
        val qualityKeeper = evaluatedQuality.items.first { !it.isSelectedForDelete }
        assertEquals("KEEP_HIGHEST_QUALITY must preserve audio-intact video", 902L, qualityKeeper.id)
        assertEquals(SmartSelectUseCase.AUDIO_INTACT_BADGE_REASON, qualityKeeper.keepBadgeReason)
        assertTrue(evaluatedQuality.items.first { it.id == 901L }.isSelectedForDelete)
    }

    @Test
    fun `CleanerViewTab CORRUPTED_FILES and CorruptedFileReport model behave properly`() {
        assertEquals(3, CleanerViewTab.entries.size)
        assertEquals("손상·0바이트", CleanerViewTab.CORRUPTED_FILES.title)
        assertTrue(CleanerViewTab.entries.contains(CleanerViewTab.CORRUPTED_FILES))

        val brokenItem = MediaFileItem(
            id = 999L,
            uri = TestUriFactory.create("file:///storage/emulated/0/Download/broken.part"),
            displayName = "broken.part",
            relativePath = "Download",
            absolutePath = "/storage/emulated/0/Download/broken.part",
            sizeBytes = 0L,
            lastModifiedSeconds = 1_700_000_000L,
            mimeType = "application/octet-stream",
            category = FileCategory.OTHER,
            actualBitrateKbps = 0
        )

        val report = CorruptedFileReport(
            item = brokenItem,
            reason = "0바이트 빈 파일"
        )
        assertEquals("broken.part", report.item.displayName)
        assertEquals(0L, report.item.sizeBytes)
        assertEquals("0바이트 빈 파일", report.reason)
        assertEquals(0, report.item.effectiveBitrateKbps)
    }

    @Test
    fun `VideoPerceptualHasher computeMirroredDHash matches horizontally flipped 9x8 grid and calculateMirrorAwareSimilarityPercent detects mirror`() {
        // Asymmetric 9x8 grid with guaranteed no horizontal adjacent ties (even vs odd parity per column)
        val asymmetricGrid = IntArray(72) { idx ->
            val x = idx % 9
            val y = idx / 9
            ((8 - x) * 12 + ((y * 7) % 15)) * 2 + (x and 1)
        }
        val flippedGrid = VideoPerceptualHasher.flipGrayscaleGrid9x8Horizontally(asymmetricGrid)

        val dHashOriginal = VideoPerceptualHasher.computeDHashFromGrayscaleGrid(asymmetricGrid)
        val dHashFlipped = VideoPerceptualHasher.computeDHashFromGrayscaleGrid(flippedGrid)
        val dHashMirroredBitwise = VideoPerceptualHasher.computeMirroredDHash(dHashOriginal)

        assertNotEquals("Asymmetric grid must produce distinct flipped dHash", dHashOriginal, dHashFlipped)
        assertEquals(
            "Bitwise computeMirroredDHash must match dHash computed from horizontally flipped 9x8 grid",
            dHashFlipped,
            dHashMirroredBitwise
        )

        val (similarity, isMirroredMatch) = VideoPerceptualHasher.calculateMirrorAwareSimilarityPercent(
            dHashesA = listOf(dHashOriginal),
            dHashesB = listOf(dHashFlipped)
        )

        assertTrue("Expected mirror-aware similarity >= 90%, got $similarity%", similarity >= 90)
        assertTrue("Expected isMirroredMatch == true for flipped image dHash", isMirroredMatch)
    }

    @Test
    fun `AudioWaveformHasher computePcmEnvelopeFingerprint and computeEnvelopeSimilarityPercent match gain-scaled copy and reject different waveform`() {
        val sampleCount = 16_000
        // Waveform A: strong energy front-loaded in first half, quiet tail
        val originalSamples = ShortArray(sampleCount) { i ->
            val envelope = if (i < sampleCount / 2) {
                24_000.0 * (1.0 - (i.toDouble() / (sampleCount / 2)))
            } else {
                400.0
            }
            (sin(2.0 * PI * 440.0 * i / 8000.0) * envelope).roundToInt().toShort()
        }
        // 0.6x gain-reduced copy of Waveform A
        val quietCopySamples = ShortArray(sampleCount) { i ->
            (originalSamples[i] * 0.6f).roundToInt().toShort()
        }
        // Waveform B: completely different back-loaded crescendo envelope
        val differentSamples = ShortArray(sampleCount) { i ->
            val envelope = if (i < sampleCount / 2) {
                400.0
            } else {
                24_000.0 * ((i - sampleCount / 2).toDouble() / (sampleCount / 2))
            }
            (sin(2.0 * PI * 660.0 * i / 8000.0) * envelope).roundToInt().toShort()
        }

        val fpOrig = AudioWaveformHasher.computePcmEnvelopeFingerprint(originalSamples, durationMs = 2000L)
        val fpQuiet = AudioWaveformHasher.computePcmEnvelopeFingerprint(quietCopySamples, durationMs = 2000L)
        val fpDifferent = AudioWaveformHasher.computePcmEnvelopeFingerprint(differentSamples, durationMs = 2000L)

        assertNotNull(fpOrig)
        assertNotNull(fpQuiet)
        assertNotNull(fpDifferent)

        val sameGainScaledSim = AudioWaveformHasher.computeEnvelopeSimilarityPercent(fpOrig!!, fpQuiet!!)
        assertTrue(
            "Expected 0.6x gain-reduced waveform similarity >= 95%, got $sameGainScaledSim%",
            sameGainScaledSim >= 95
        )

        val unrelatedSim = AudioWaveformHasher.computeEnvelopeSimilarityPercent(fpOrig, fpDifferent!!)
        assertTrue(
            "Expected unrelated waveform similarity < 60%, got $unrelatedSim%",
            unrelatedSim < 60
        )
    }

    @Test
    fun `DemoSampleGenerator encodeMinimalRgbPng and encodeMinimalPcmWav produce valid PNG and parseable WAV headers`() {
        val pngBytes = DemoSampleGenerator.encodeMinimalRgbPng(32, 32) { x, y ->
            Triple((x * 7) and 0xFF, (y * 7) and 0xFF, ((x xor y) * 3) and 0xFF)
        }
        val expectedPngMagic = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        assertTrue("PNG binary must be larger than signature + chunks", pngBytes.size > 32)
        assertArrayEquals(
            "PNG magic signature must match \\x89PNG\\r\\n\\x1a\\n",
            expectedPngMagic,
            pngBytes.copyOfRange(0, 8)
        )
        val pngAscii = String(pngBytes, Charsets.ISO_8859_1)
        assertTrue("PNG must contain IHDR chunk", pngAscii.contains("IHDR"))
        assertTrue("PNG must contain IDAT chunk", pngAscii.contains("IDAT"))
        assertTrue("PNG must contain IEND chunk", pngAscii.contains("IEND"))

        val wavOrigBytes = DemoSampleGenerator.encodeMinimalPcmWav(
            durationSeconds = 2,
            sampleRate = 8000,
            amplitudeScale = 1.0f
        )
        val wavQuietBytes = DemoSampleGenerator.encodeMinimalPcmWav(
            durationSeconds = 2,
            sampleRate = 8000,
            amplitudeScale = 0.65f
        )
        assertEquals("RIFF", String(wavOrigBytes.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WAVE", String(wavOrigBytes.copyOfRange(8, 12), Charsets.US_ASCII))

        val wavOrigFile = tempFolder.newFile("sample_orig.wav")
        val wavQuietFile = tempFolder.newFile("sample_quiet.wav")
        FileOutputStream(wavOrigFile).use { it.write(wavOrigBytes) }
        FileOutputStream(wavQuietFile).use { it.write(wavQuietBytes) }

        val parsedOrig = AudioWaveformHasher.parseWavPcmSamples(wavOrigFile)
        val parsedQuiet = AudioWaveformHasher.parseWavPcmSamples(wavQuietFile)
        assertNotNull("WAV parser must parse generated RIFF/WAVE file", parsedOrig)
        assertNotNull("WAV parser must parse quiet RIFF/WAVE file", parsedQuiet)
        assertEquals(2000L, parsedOrig!!.second)
        assertEquals(16_000, parsedOrig.first.size)

        val fpOrig = AudioWaveformHasher.computePcmEnvelopeFingerprint(parsedOrig.first, parsedOrig.second)!!
        val fpQuiet = AudioWaveformHasher.computePcmEnvelopeFingerprint(parsedQuiet!!.first, parsedQuiet.second)!!
        val sim = AudioWaveformHasher.computeEnvelopeSimilarityPercent(fpOrig, fpQuiet)
        assertTrue("Generated WAV 1.0x vs 0.65x similarity must be >= 95%, got $sim%", sim >= 95)
    }

    @Test
    fun `FunnelAnalyticsHelper buildStageStats computes accurate funnel drop percentages and surviving counts`() {
        val metrics = ScanFunnelMetrics(
            totalIndexedFiles = 1000,
            totalIndexedBytes = 10_000_000_000L,
            sizeFilteredCandidates = 200,
            partialHashCandidates = 50,
            fullHashCandidates = 30,
            actualBytesRead = 120_000_000L,
            savedIoBytes = 9_880_000_000L
        )
        val sampleItem = MediaFileItem(
            id = 1L,
            uri = TestUriFactory.create("content://media/external/video/media/1"),
            displayName = "sample.mp4",
            relativePath = "Movies",
            absolutePath = "/storage/emulated/0/Movies/sample.mp4",
            sizeBytes = 10_000_000L,
            lastModifiedSeconds = 1_700_000_000L,
            mimeType = "video/mp4",
            category = FileCategory.VIDEO
        )
        val groups = List(5) { idx ->
            DuplicateGroup(
                groupId = "G_$idx",
                signature = "sig_$idx",
                matchType = MatchType.EXACT_HASH,
                similarityPercent = 100,
                category = FileCategory.VIDEO,
                items = listOf(sampleItem, sampleItem.copy(id = idx + 10L, isSelectedForDelete = true))
            )
        } // 5 groups * 2 items = 10 duplicate files

        val stats = FunnelAnalyticsHelper.buildStageStats(metrics, groups)

        assertEquals(4, stats.size)
        assertEquals(1000, stats[0].survivingCount)
        assertEquals(0, stats[0].dropPercent)

        assertEquals(200, stats[1].survivingCount)
        assertEquals(80, stats[1].dropPercent)

        assertEquals(50, stats[2].survivingCount)
        assertEquals(75, stats[2].dropPercent)

        assertEquals(10, stats[3].survivingCount)
        assertEquals(99, stats[3].dropPercent)
    }

    @Test
    fun `MatchType AUDIO_FINGERPRINT_MATCH metadata and ScanConfig regression check`() {
        assertEquals(5, MatchType.entries.size)
        assertTrue(MatchType.entries.contains(MatchType.AUDIO_FINGERPRINT_MATCH))
        assertEquals("크로스 오디오 일치", MatchType.AUDIO_FINGERPRINT_MATCH.badgeLabel)
        assertEquals(
            "포맷(MP3·WAV·M4A)이나 음량이 달라도 파형 에너지 패턴이 동일한 오디오입니다.",
            MatchType.AUDIO_FINGERPRINT_MATCH.description
        )

        val defaultConfig = ScanConfig()
        assertEquals(ScanScopeMode.VIDEO_ONLY, defaultConfig.scopeMode)
        assertTrue(defaultConfig.includeSimilarVideos)
        assertEquals(85, defaultConfig.similarityThresholdPercent)
        assertTrue(defaultConfig.ignoredGroupSignatures.isEmpty())
        assertTrue(defaultConfig.targetFolderKeywords.isEmpty())

        val wavOrig = MediaFileItem(
            id = 1001L,
            uri = TestUriFactory.create("file:///storage/emulated/0/Music/VoiceMemo_Original.wav"),
            displayName = "VoiceMemo_Original.wav",
            relativePath = "Music",
            absolutePath = "/storage/emulated/0/Music/VoiceMemo_Original.wav",
            sizeBytes = 32_044L,
            lastModifiedSeconds = 1_700_000_000L,
            mimeType = "audio/wav",
            category = FileCategory.AUDIO,
            durationMs = 2000L
        )
        val wavQuiet = wavOrig.copy(
            id = 1002L,
            uri = TestUriFactory.create("file:///storage/emulated/0/Download/VoiceMemo_QuietCopy.wav"),
            displayName = "VoiceMemo_QuietCopy.wav",
            relativePath = "Download",
            absolutePath = "/storage/emulated/0/Download/VoiceMemo_QuietCopy.wav",
            lastModifiedSeconds = 1_700_050_000L
        )
        val group = DuplicateGroup(
            groupId = "AUDIO_FP_1",
            signature = "audio:2000ms:abcd1234",
            matchType = MatchType.AUDIO_FINGERPRINT_MATCH,
            similarityPercent = 99,
            category = FileCategory.AUDIO,
            items = listOf(wavQuiet, wavOrig)
        )
        val evaluated = SmartSelectUseCase.applyRuleToGroup(group, SmartSelectionRule.SMART_ORIGINAL)
        val keeper = evaluated.items.first { !it.isSelectedForDelete }
        assertEquals(1001L, keeper.id)
        assertTrue(evaluated.items.first { it.id == 1002L }.isSelectedForDelete)
    }

    @Test
    fun `ThumbnailMemoryCache computeSafeInSampleSize computes power-of-two downsampling for 4K images`() {
        val sample4k = ThumbnailMemoryCache.computeSafeInSampleSize(
            rawWidth = 4096,
            rawHeight = 4096,
            reqWidth = 256,
            reqHeight = 256
        )
        assertTrue("Expected inSampleSize >= 8 for 4096x4096 -> 256x256, got $sample4k", sample4k >= 8)
        assertEquals("inSampleSize must be a power of two", 0, sample4k and (sample4k - 1))

        val sampleExact = ThumbnailMemoryCache.computeSafeInSampleSize(
            rawWidth = 256,
            rawHeight = 256,
            reqWidth = 256,
            reqHeight = 256
        )
        assertEquals(1, sampleExact)

        val sampleRect = ThumbnailMemoryCache.computeSafeInSampleSize(
            rawWidth = 2048,
            rawHeight = 1024,
            reqWidth = 256,
            reqHeight = 256
        )
        assertTrue("Expected inSampleSize >= 4 for 2048x1024 -> 256x256, got $sampleRect", sampleRect >= 4)
        assertEquals("inSampleSize must be a power of two", 0, sampleRect and (sampleRect - 1))
    }

    @Test
    fun `ShareUriHelper isFileScheme distinguishes file and storage paths from content URIs`() {
        assertTrue(ShareUriHelper.isFileScheme("file:///storage/emulated/0/DCIM/Camera/clip.mp4"))
        assertTrue(ShareUriHelper.isFileScheme("  FILE:///sdcard/Download/photo.jpg  "))
        assertTrue(ShareUriHelper.isFileScheme("/storage/emulated/0/Movies/sample.mp4"))

        assertFalse(ShareUriHelper.isFileScheme("content://media/external/video/media/123"))
        assertFalse(ShareUriHelper.isFileScheme("CONTENT://media/external/images/media/456"))
        assertFalse(ShareUriHelper.isFileScheme(""))
    }

    @Test
    fun `CleanupSafetyHelper buildImpactSummary accurately aggregates delete count reclaimable bytes and preserved originals`() {
        val baseItem = MediaFileItem(
            id = 2001L,
            uri = TestUriFactory.create("content://media/external/video/media/2001"),
            displayName = "Original_1.mp4",
            relativePath = "DCIM/Camera",
            absolutePath = "/storage/emulated/0/DCIM/Camera/Original_1.mp4",
            sizeBytes = 10_000_000L,
            lastModifiedSeconds = 1_700_000_000L,
            mimeType = "video/mp4",
            category = FileCategory.VIDEO,
            isSelectedForDelete = false
        )
        val group1 = DuplicateGroup(
            groupId = "G_SAFE_1",
            signature = "sig_safe_1",
            matchType = MatchType.EXACT_HASH,
            similarityPercent = 100,
            category = FileCategory.VIDEO,
            items = listOf(
                baseItem,
                baseItem.copy(id = 2002L, sizeBytes = 5_000_000L, isSelectedForDelete = true),
                baseItem.copy(id = 2003L, sizeBytes = 5_000_000L, isSelectedForDelete = true)
            )
        )
        val group2 = DuplicateGroup(
            groupId = "G_SAFE_2",
            signature = "sig_safe_2",
            matchType = MatchType.EXACT_HASH,
            similarityPercent = 100,
            category = FileCategory.IMAGE,
            items = listOf(
                baseItem.copy(id = 2004L, sizeBytes = 20_000_000L, isSelectedForDelete = false),
                baseItem.copy(id = 2005L, sizeBytes = 20_000_000L, isSelectedForDelete = true)
            )
        )
        val largeSelected = baseItem.copy(
            id = 3001L,
            displayName = "Huge_Movie.mp4",
            sizeBytes = 500_000_000L,
            isSelectedForDelete = true
        )
        val corruptedSelected = CorruptedFileReport(
            item = baseItem.copy(
                id = 4001L,
                displayName = "broken.part",
                sizeBytes = 1024L,
                isSelectedForDelete = true
            ),
            reason = "미완료 임시 파일 (.part)"
        )

        val groupSummary = CleanupSafetyHelper.buildImpactSummary(
            groups = listOf(group1, group2),
            selectedLargeFiles = listOf(largeSelected),
            selectedCorruptedFiles = listOf(corruptedSelected),
            isPermanentDelete = false,
            includeGroups = true,
            includeLargeFiles = false,
            includeCorruptedFiles = false
        )
        assertEquals(3, groupSummary.selectedDeleteCount)
        assertEquals(30_000_000L, groupSummary.reclaimableBytes)
        assertEquals(2, groupSummary.preservedOriginalCount)
        assertFalse(groupSummary.isPermanentDelete)

        val largeSummary = CleanupSafetyHelper.buildImpactSummary(
            groups = listOf(group1, group2),
            selectedLargeFiles = listOf(largeSelected),
            selectedCorruptedFiles = listOf(corruptedSelected),
            isPermanentDelete = true,
            includeGroups = false,
            includeLargeFiles = true,
            includeCorruptedFiles = false
        )
        assertEquals(1, largeSummary.selectedDeleteCount)
        assertEquals(500_000_000L, largeSummary.reclaimableBytes)
        assertEquals(0, largeSummary.preservedOriginalCount)
        assertTrue(largeSummary.isPermanentDelete)

        val corruptedSummary = CleanupSafetyHelper.buildImpactSummary(
            groups = listOf(group1, group2),
            selectedLargeFiles = listOf(largeSelected),
            selectedCorruptedFiles = listOf(corruptedSelected),
            isPermanentDelete = false,
            includeGroups = false,
            includeLargeFiles = false,
            includeCorruptedFiles = true
        )
        assertEquals(1, corruptedSummary.selectedDeleteCount)
        assertEquals(1024L, corruptedSummary.reclaimableBytes)
        assertEquals(0, corruptedSummary.preservedOriginalCount)
        assertFalse(corruptedSummary.isPermanentDelete)
    }

    @Test
    fun `FileProvider manifest authority naming convention and zero-network safety invariants hold`() {
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).firstOrNull { it.exists() }
        assertNotNull("AndroidManifest.xml must exist in module source root", manifestFile)

        val manifestText = manifestFile!!.readText(Charsets.UTF_8)
        assertFalse(
            "Zero-Network safety invariant: INTERNET permission must NEVER be declared",
            manifestText.contains("android.permission.INTERNET")
        )
        assertTrue(
            "FileProvider must be registered in AndroidManifest.xml",
            manifestText.contains("androidx.core.content.FileProvider")
        )
        assertTrue(
            "FileProvider must use \${applicationId}.fileprovider authority convention",
            manifestText.contains("android:authorities=\"\${applicationId}.fileprovider\"")
        )
        assertTrue(
            "FileProvider must not be exported",
            manifestText.contains("android:exported=\"false\"")
        )
        assertTrue(
            "FileProvider must grant temporary URI read permissions",
            manifestText.contains("android:grantUriPermissions=\"true\"")
        )
        assertTrue(
            "FileProvider must reference @xml/file_paths",
            manifestText.contains("@xml/file_paths")
        )

        val filePathsXml = listOf(
            File("src/main/res/xml/file_paths.xml"),
            File("app/src/main/res/xml/file_paths.xml")
        ).firstOrNull { it.exists() }
        assertNotNull("res/xml/file_paths.xml must exist", filePathsXml)

        val pathsText = filePathsXml!!.readText(Charsets.UTF_8)
        assertTrue(pathsText.contains("<paths"))
        assertTrue(pathsText.contains("external-files-path"))
        assertTrue(pathsText.contains("external-path"))
        assertTrue(pathsText.contains("files-path"))
        assertTrue(pathsText.contains("cache-path"))

        val expectedPackageId = "com.antigravity.duplicleaner"
        assertEquals("com.antigravity.duplicleaner.fileprovider", "$expectedPackageId.fileprovider")
    }

    @Test
    fun `EmptyFolderCleaner findEmptyFolders detects nested empty subdirectories bottom-up while skipping non-empty and protected folders`() {
        val storageRoot = tempFolder.newFolder("empty_scan_root")
        val downloadDir = File(storageRoot, "Download").apply { mkdirs() }
        val nestedA = File(downloadDir, "A").apply { mkdirs() }
        val nestedEmptyB = File(nestedA, "EmptyB").apply { mkdirs() }
        val emptyTemp = File(downloadDir, "EmptyTemp").apply { mkdirs() }

        val nonEmptyDir = File(downloadDir, "NonEmpty").apply { mkdirs() }
        FileOutputStream(File(nonEmptyDir, "keep_me.mp4")).use { it.write(byteArrayOf(1, 2, 3, 4)) }

        val dcimCameraDir = File(storageRoot, "DCIM/Camera").apply { mkdirs() }
        val hiddenEmptyDir = File(storageRoot, ".hidden_cache").apply { mkdirs() }
        val protectedSub = File(downloadDir, "MyProtectedWork").apply { mkdirs() }
        val insideProtectedEmpty = File(protectedSub, "EmptyInsideProtected").apply { mkdirs() }

        val candidates = EmptyFolderCleaner.findEmptyFolders(
            rootDirs = listOf(storageRoot),
            excludedKeywords = setOf("excluded_keyword"),
            protectedFolders = setOf("MyProtectedWork")
        )

        val candidatePaths = candidates.map { it.path }
        assertTrue("Expected EmptyB to be detected", candidatePaths.contains(nestedEmptyB.absolutePath))
        assertTrue("Expected A (containing only EmptyB) to be detected", candidatePaths.contains(nestedA.absolutePath))
        assertTrue("Expected EmptyTemp to be detected", candidatePaths.contains(emptyTemp.absolutePath))
        assertEquals("Only EmptyB, A, and EmptyTemp should be detected", 3, candidates.size)

        val indexEmptyB = candidatePaths.indexOf(nestedEmptyB.absolutePath)
        val indexA = candidatePaths.indexOf(nestedA.absolutePath)
        assertTrue("Bottom-up order must list EmptyB before parent A", indexEmptyB < indexA)

        assertFalse("Root dir must never be listed", candidatePaths.contains(storageRoot.absolutePath))
        assertFalse("Download (always protected) must never be listed", candidatePaths.contains(downloadDir.absolutePath))
        assertFalse("NonEmpty dir must be skipped", candidatePaths.contains(nonEmptyDir.absolutePath))
        assertFalse("DCIM/Camera must never be listed", candidatePaths.contains(dcimCameraDir.absolutePath))
        assertFalse("Hidden '.' folder must be skipped", candidatePaths.contains(hiddenEmptyDir.absolutePath))
        assertFalse("Protected folder must be skipped", candidatePaths.contains(protectedSub.absolutePath))
        assertFalse("Subfolder inside protected folder must be skipped", candidatePaths.contains(insideProtectedEmpty.absolutePath))
    }

    @Test
    fun `EmptyFolderCleaner deleteEmptyFolders deletes nested empty directories cleanly while keeping non-empty directories intact`() {
        val storageRoot = tempFolder.newFolder("empty_delete_root")
        val downloadDir = File(storageRoot, "Download").apply { mkdirs() }
        val nestedA = File(downloadDir, "A").apply { mkdirs() }
        val nestedEmptyB = File(nestedA, "EmptyB").apply { mkdirs() }
        val emptyTemp = File(downloadDir, "EmptyTemp").apply { mkdirs() }

        val keepDir = File(downloadDir, "KeepDir").apply { mkdirs() }
        val keepFile = File(keepDir, "important.txt")
        FileOutputStream(keepFile).use { it.write("keep".toByteArray(Charsets.UTF_8)) }

        val candidates = EmptyFolderCleaner.findEmptyFolders(rootDirs = listOf(storageRoot))
        assertEquals(3, candidates.size)

        // Include KeepDir intentionally to verify deleteEmptyFolders refuses to delete non-empty dirs
        val deletedCount = EmptyFolderCleaner.deleteEmptyFolders(
            candidates.map { it.path } + keepDir.absolutePath
        )

        assertEquals(3, deletedCount)
        assertFalse("EmptyB must be deleted", nestedEmptyB.exists())
        assertFalse("Parent A must be deleted once EmptyB is removed", nestedA.exists())
        assertFalse("EmptyTemp must be deleted", emptyTemp.exists())
        assertTrue("Always-protected Download must remain", downloadDir.exists())
        assertTrue("Non-empty KeepDir must remain untouched", keepDir.exists())
        assertTrue("Important file must remain untouched", keepFile.exists())
    }

    @Test
    fun `QuarantineVaultManager batch undo subset restoreSingleItem restores selected batch while keeping earlier quarantined item`() {
        val vaultDir = tempFolder.newFolder("batch_undo_vault")
        val sourceDir = tempFolder.newFolder("batch_undo_source")
        val vaultManager = QuarantineVaultManager(vaultDir)

        val file1 = File(sourceDir, "earlier_dup.mp4")
        val file2 = File(sourceDir, "recent_dup_1.mp4")
        val file3 = File(sourceDir, "recent_dup_2.mp4")
        FileOutputStream(file1).use { it.write(ByteArray(1024) { 0x01 }) }
        FileOutputStream(file2).use { it.write(ByteArray(2048) { 0x02 }) }
        FileOutputStream(file3).use { it.write(ByteArray(3072) { 0x03 }) }

        val rec1 = vaultManager.quarantineFile(file1)!!
        val beforeIds = vaultManager.getQuarantinedItems().map { it.id }.toSet()
        assertEquals(setOf(rec1.id), beforeIds)

        val rec2 = vaultManager.quarantineFile(file2)!!
        val rec3 = vaultManager.quarantineFile(file3)!!
        assertFalse(file1.exists())
        assertFalse(file2.exists())
        assertFalse(file3.exists())
        assertEquals(3, vaultManager.getQuarantinedItems().size)

        val lastUndoIds = vaultManager.getQuarantinedItems()
            .filter { it.id !in beforeIds }
            .map { it.id }
        assertEquals(2, lastUndoIds.size)
        assertTrue(lastUndoIds.containsAll(listOf(rec2.id, rec3.id)))

        var restoredCount = 0
        for (id in lastUndoIds) {
            if (vaultManager.restoreSingleItem(id)) {
                restoredCount++
            }
        }
        assertEquals(2, restoredCount)
        assertTrue("file2 must be restored to original path", file2.exists())
        assertEquals(2048L, file2.length())
        assertTrue("file3 must be restored to original path", file3.exists())
        assertEquals(3072L, file3.length())

        assertFalse("Earlier quarantined file1 must remain in quarantine", file1.exists())
        val remaining = vaultManager.getQuarantinedItems()
        assertEquals(1, remaining.size)
        assertEquals(rec1.id, remaining.first().id)
        assertEquals(1024L, vaultManager.getTotalQuarantinedBytes())
    }

    @Test
    fun `EmptyFolderCandidate and ForegroundScanService constants and release manifest rules regression check`() {
        val candidate = EmptyFolderCandidate(
            path = "/storage/emulated/0/Download/EmptyTemp",
            name = "EmptyTemp",
            parentPath = "/storage/emulated/0/Download"
        )
        assertEquals("/storage/emulated/0/Download/EmptyTemp", candidate.path)
        assertEquals("EmptyTemp", candidate.name)
        assertEquals("/storage/emulated/0/Download", candidate.parentPath)

        assertEquals("duplicleaner_foreground_scan", ForegroundScanService.CHANNEL_ID)
        assertEquals(20261113, ForegroundScanService.NOTIFICATION_ID)
        assertEquals("com.antigravity.duplicleaner.action.START_OR_UPDATE", ForegroundScanService.ACTION_START_OR_UPDATE)
        assertEquals("com.antigravity.duplicleaner.action.STOP", ForegroundScanService.ACTION_STOP)
        assertEquals("extra_title", ForegroundScanService.EXTRA_TITLE)
        assertEquals("extra_subtitle", ForegroundScanService.EXTRA_SUBTITLE)
        assertEquals("extra_progress", ForegroundScanService.EXTRA_PROGRESS)
        assertEquals("extra_max", ForegroundScanService.EXTRA_MAX)

        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).firstOrNull { it.exists() }
        assertNotNull("AndroidManifest.xml must exist", manifestFile)
        val manifestText = manifestFile!!.readText(Charsets.UTF_8)
        assertTrue(manifestText.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(manifestText.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(manifestText.contains(".service.ForegroundScanService"))
        assertTrue(manifestText.contains("android:foregroundServiceType=\"dataSync\""))
        assertFalse(manifestText.contains("android.permission.INTERNET"))

        val proguardFile = listOf(
            File("proguard-rules.pro"),
            File("app/proguard-rules.pro")
        ).firstOrNull { it.exists() }
        assertNotNull("proguard-rules.pro must exist", proguardFile)
        val proguardText = proguardFile!!.readText(Charsets.UTF_8)
        assertTrue(proguardText.contains("com.antigravity.duplicleaner.data.local.**"))
        assertTrue(proguardText.contains("com.antigravity.duplicleaner.domain.model.**"))
        assertTrue(proguardText.contains("com.antigravity.duplicleaner.service.**"))
    }

    @Test
    fun `TransitiveClusterMerger merges chained pairs into one cluster while keeping unconnected item separate`() {
        val items = listOf("item_0", "item_1", "item_2", "item_3")
        val merger = TransitiveClusterMerger(items)

        assertTrue(merger.union(0, 1))
        assertTrue(merger.union(1, 2))
        assertFalse("Already connected pair should return false", merger.union(0, 2))

        assertEquals(merger.find(0), merger.find(1))
        assertEquals(merger.find(1), merger.find(2))
        assertNotEquals(merger.find(0), merger.find(3))
        assertEquals(3, merger.clusterSizeOf(0))
        assertEquals(1, merger.clusterSizeOf(3))

        val clusters = merger.extractClusters()
        assertEquals(1, clusters.size)
        assertEquals(listOf("item_0", "item_1", "item_2"), clusters.first())
    }

    @Test
    fun `TransitiveClusterMerger enforces maxClusterSize guard when cluster capacity is reached`() {
        val items = listOf("a", "b", "c", "d", "e")
        val merger = TransitiveClusterMerger(items, maxClusterSize = 3)

        assertTrue(merger.union(0, 1))
        assertTrue(merger.union(1, 2))
        assertEquals(3, merger.clusterSizeOf(0))

        // Merging item 3 into full size-3 cluster must be rejected
        assertFalse("Should reject merge exceeding maxClusterSize", merger.union(2, 3))
        assertEquals(3, merger.clusterSizeOf(0))
        assertEquals(1, merger.clusterSizeOf(3))

        // Separate cluster {3, 4} of size 2 can still form, but cannot merge into {0, 1, 2}
        assertTrue(merger.union(3, 4))
        assertFalse("Combined size 5 > maxClusterSize 3 must be rejected", merger.union(0, 3))

        val clusters = merger.extractClusters()
        assertEquals(2, clusters.size)
        assertEquals(listOf("a", "b", "c"), clusters[0])
        assertEquals(listOf("d", "e"), clusters[1])
    }

    @Test
    fun `ThermalBatteryMonitor resolveThrottleLevel adapts cleanly across thermal and battery states`() {
        assertEquals(
            ThrottleLevel.THERMAL_COOLING,
            ThermalBatteryMonitor.resolveThrottleLevel(batteryPercent = 85, isCharging = false, thermalStatusCode = 3)
        )
        assertEquals(
            ThrottleLevel.THERMAL_COOLING,
            ThermalBatteryMonitor.resolveThrottleLevel(batteryPercent = 100, isCharging = true, thermalStatusCode = 4)
        )
        assertEquals(
            ThrottleLevel.BALANCED,
            ThermalBatteryMonitor.resolveThrottleLevel(batteryPercent = 15, isCharging = false, thermalStatusCode = 0)
        )
        assertEquals(
            ThrottleLevel.BALANCED,
            ThermalBatteryMonitor.resolveThrottleLevel(batteryPercent = 90, isCharging = true, thermalStatusCode = 2)
        )
        assertEquals(
            ThrottleLevel.NORMAL,
            ThermalBatteryMonitor.resolveThrottleLevel(batteryPercent = 15, isCharging = true, thermalStatusCode = 0)
        )
        assertEquals(
            ThrottleLevel.NORMAL,
            ThermalBatteryMonitor.resolveThrottleLevel(batteryPercent = 75, isCharging = false, thermalStatusCode = 1)
        )
    }

    @Test
    fun `AppThemeMode and ThrottleLevel enum configurations and ScanProgressUpdate defaults behave properly`() {
        assertEquals(3, AppThemeMode.entries.size)
        assertEquals("시스템 설정", AppThemeMode.SYSTEM.label)
        assertEquals("라이트 모드", AppThemeMode.LIGHT.label)
        assertEquals("다크 모드", AppThemeMode.DARK.label)

        assertEquals(3, ThrottleLevel.entries.size)
        assertEquals("최고 속도", ThrottleLevel.NORMAL.label)
        assertEquals(4, ThrottleLevel.NORMAL.maxParallelWorkers)
        assertEquals(0L, ThrottleLevel.NORMAL.chunkCooldownMs)

        assertEquals("전력 절약", ThrottleLevel.BALANCED.label)
        assertEquals(2, ThrottleLevel.BALANCED.maxParallelWorkers)
        assertEquals(15L, ThrottleLevel.BALANCED.chunkCooldownMs)

        assertEquals("발열 보호 감속", ThrottleLevel.THERMAL_COOLING.label)
        assertEquals(1, ThrottleLevel.THERMAL_COOLING.maxParallelWorkers)
        assertEquals(45L, ThrottleLevel.THERMAL_COOLING.chunkCooldownMs)

        val defaultProgress = ScanProgressUpdate(ScanPhase.STAGE_4_SIMILAR_VIDEO, 5, 20)
        assertEquals(ThrottleLevel.NORMAL, defaultProgress.throttleLevel)
        val throttledProgress = defaultProgress.copy(throttleLevel = ThrottleLevel.THERMAL_COOLING)
        assertEquals(ThrottleLevel.THERMAL_COOLING, throttledProgress.throttleLevel)
    }

    @Test
    fun `Full pipeline sanity regression check verifies all MatchType and CleanerViewTab entries`() {
        assertEquals(5, MatchType.entries.size)
        assertTrue(
            MatchType.entries.containsAll(
                listOf(
                    MatchType.EXACT_HASH,
                    MatchType.CONTAINER_PAYLOAD_MATCH,
                    MatchType.TRIMMED_CLIP_MATCH,
                    MatchType.AUDIO_FINGERPRINT_MATCH,
                    MatchType.SIMILAR_VIDEO
                )
            )
        )
        assertTrue(MatchType.entries.all { it.badgeLabel.isNotBlank() && it.description.isNotBlank() })

        assertEquals(3, CleanerViewTab.entries.size)
        assertEquals(
            listOf(
                CleanerViewTab.DUPLICATE_GROUPS,
                CleanerViewTab.LARGE_FILES_HUNTER,
                CleanerViewTab.CORRUPTED_FILES
            ),
            CleanerViewTab.entries
        )

        assertEquals(3, ScanScopeMode.entries.size)
        assertEquals(4, SmartSelectionRule.entries.size)
        assertEquals(4, ResultSortOption.entries.size)
        assertEquals(5, FileCategory.entries.size)

        val config = ScanConfig()
        assertEquals(85, config.similarityThresholdPercent)
        assertTrue(config.includeSimilarVideos)
    }

    private fun buildMp3WithId3v2(totalTagBytes: Int, tagFill: Int, audioPayload: ByteArray): ByteArray {
        val tagPayloadSize = (totalTagBytes - 10).coerceAtLeast(0)
        val out = ByteArrayOutputStream(10 + tagPayloadSize + audioPayload.size)
        out.write("ID3".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x03, 0x00, 0x00)) // ID3v2.3, no flags
        // 4 synchsafe bytes (7 bits per byte)
        out.write((tagPayloadSize shr 21) and 0x7F)
        out.write((tagPayloadSize shr 14) and 0x7F)
        out.write((tagPayloadSize shr 7) and 0x7F)
        out.write(tagPayloadSize and 0x7F)
        if (tagPayloadSize > 0) {
            out.write(ByteArray(tagPayloadSize) { tagFill.toByte() })
        }
        out.write(audioPayload)
        return out.toByteArray()
    }

    private fun buildMp4Box(type: String, payload: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        require(typeBytes.size == 4)
        val totalSize = 8 + payload.size
        return ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(totalSize)
            put(typeBytes)
            put(payload)
        }.array()
    }

    private fun buildMp4Box64(type: String, payload: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        require(typeBytes.size == 4)
        val totalSize = 16 + payload.size
        return ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(1) // size == 1 indicates 64-bit largesize follows
            put(typeBytes)
            putLong(totalSize.toLong())
            put(payload)
        }.array()
    }
}
