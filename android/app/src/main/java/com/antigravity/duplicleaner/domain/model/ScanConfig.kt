package com.antigravity.duplicleaner.domain.model

enum class FileCategory(val label: String) {
    VIDEO("영상"),
    IMAGE("이미지"),
    AUDIO("오디오"),
    DOCUMENT_ARCHIVE("문서·압축·APK"),
    OTHER("기타 파일")
}

enum class MatchType(val badgeLabel: String, val description: String) {
    EXACT_HASH(
        badgeLabel = "100% 바이트 일치",
        description = "파일 이름이 달라도 첫 바이트부터 끝 바이트까지 완전히 동일한 파일입니다."
    ),
    CONTAINER_PAYLOAD_MATCH(
        badgeLabel = "MP4 스트림 일치",
        description = "메타데이터·태그는 다르지만 실제 영상 스트림(mdat)이 동일한 파일입니다."
    ),
    TRIMMED_CLIP_MATCH(
        badgeLabel = "구간 편집·클립 일치",
        description = "원본 영상의 일부 구간을 잘라냈거나 인트로·아웃트로만 다른 동일 영상입니다."
    ),
    AUDIO_FINGERPRINT_MATCH(
        badgeLabel = "크로스 오디오 일치",
        description = "포맷(MP3·WAV·M4A)이나 음량이 달라도 파형 에너지 패턴이 동일한 오디오입니다."
    ),
    SIMILAR_VIDEO(
        badgeLabel = "유사 영상 (키프레임)",
        description = "재인코딩·압축되었거나 해상도가 변경되었지만 시각적 장면이 동일한 영상입니다."
    )
}

enum class CleanerViewTab(val title: String) {
    DUPLICATE_GROUPS("중복 그룹"),
    LARGE_FILES_HUNTER("대용량 파일 Top 30"),
    CORRUPTED_FILES("손상·0바이트")
}

enum class ResultSortOption(val label: String) {
    RECLAIMABLE_DESC("확보 용량 큰 순"),
    FILE_SIZE_DESC("파일 크기 큰 순"),
    ITEM_COUNT_DESC("중복 개수 많은 순"),
    NEWEST_DESC("최근 수정 순")
}

data class ScanFunnelMetrics(
    val totalIndexedFiles: Int = 0,
    val totalIndexedBytes: Long = 0L,
    val sizeFilteredCandidates: Int = 0,
    val partialHashCandidates: Int = 0,
    val fullHashCandidates: Int = 0,
    val actualBytesRead: Long = 0L,
    val savedIoBytes: Long = 0L
) {
    companion object {
        fun calculateSavedIo(totalBytes: Long, actualRead: Long): Long =
            (totalBytes - actualRead).coerceAtLeast(0L)
    }
}

enum class ScanScopeMode(val title: String, val subtitle: String) {
    VIDEO_ONLY(
        title = "영상 집중 스캔",
        subtitle = "용량을 가장 많이 차지하는 중복 동영상 파일을 우선 탐지합니다."
    ),
    ALL_MEDIA(
        title = "전체 미디어 (영상·사진·오디오)",
        subtitle = "MediaStore 기반으로 기기 내 모든 미디어 중복을 탐지합니다."
    ),
    ALL_FILES(
        title = "모든 파일 (문서·ZIP·APK 포함)",
        subtitle = "전체 파일 접근 권한을 통해 비미디어 대용량 파일까지 모두 스캔합니다."
    )
}

enum class SmartSelectionRule(val title: String, val description: String) {
    SMART_ORIGINAL(
        title = "스마트 원본 보존 (권장)",
        description = "카메라/원본 폴더 우선 + (1)·복사본 파일명 자동 감지 + 최초 생성 파일 보존"
    ),
    KEEP_OLDEST(
        title = "가장 오래된 파일 보존",
        description = "가장 먼저 다운로드하거나 촬영한 원본을 남기고 이후 중복본을 선택합니다."
    ),
    KEEP_NEWEST(
        title = "가장 최근 파일 보존",
        description = "가장 최근에 저장된 파일을 남기고 이전 파일들을 선택합니다."
    ),
    KEEP_HIGHEST_QUALITY(
        title = "최고 화질·용량 원본 보존",
        description = "유사 영상 중 해상도와 비트레이트(파일 크기)가 가장 높은 파일을 남깁니다."
    )
}

enum class AutoScanInterval(val label: String, val intervalHours: Long) {
    OFF("사용 안 함", 0L),
    DAILY("매일 (24시간)", 24L),
    EVERY_3_DAYS("3일마다", 72L),
    WEEKLY("매주 (7일)", 168L)
}

data class ScanConfig(
    val scopeMode: ScanScopeMode = ScanScopeMode.VIDEO_ONLY,
    val includeSimilarVideos: Boolean = true,
    val minFileSizeBytes: Long = 512 * 1024L, // 기본 512KB 이상
    val selectionRule: SmartSelectionRule = SmartSelectionRule.SMART_ORIGINAL,
    val excludedFolderKeywords: Set<String> = setOf(".thumbnails", "Android/data", "Trash", ".trash"),
    val similarityThresholdPercent: Int = 85,
    val ignoredGroupSignatures: Set<String> = emptySet(),
    val targetFolderKeywords: Set<String> = emptySet()
)
