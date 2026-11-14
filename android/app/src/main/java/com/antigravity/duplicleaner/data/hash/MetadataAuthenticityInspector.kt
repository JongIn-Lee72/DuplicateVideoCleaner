package com.antigravity.duplicleaner.data.hash

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * EXIF 메타데이터 분석을 통해 카메라 원본 촬영 사진인지 여부를 판별하는 인스펙터.
 * SNS(카카오톡, 인스타그램 등)를 거치며 재압축/스트립된 사진은 EXIF 태그가 소실되므로
 * 촬영일시, 카메라 기종, 노출값, GPS 좌표 여부를 분석하여 정품 원본 점수를 산출합니다.
 */
object MetadataAuthenticityInspector {

    /**
     * 감지된 EXIF 태그 여부를 바탕으로 정품 원본 점수(0..100)를 계산합니다.
     * - 촬영 일시 (TAG_DATETIME_ORIGINAL 등): +40
     * - 카메라 제조사/모델 (TAG_MAKE, TAG_MODEL): +35
     * - 조리개/셔터속도/ISO 감도 노출값: +15
     * - GPS 위치 정보: +10
     */
    fun computeScoreFromTags(
        hasOriginalDateTime: Boolean,
        hasCameraModel: Boolean,
        hasExposureParams: Boolean,
        hasGps: Boolean
    ): Int {
        var score = 0
        if (hasOriginalDateTime) score += 40
        if (hasCameraModel) score += 35
        if (hasExposureParams) score += 15
        if (hasGps) score += 10
        return score.coerceIn(0, 100)
    }

    /**
     * 이미지 파일 경로 또는 ContentResolver Uri로부터 EXIF 메타데이터를 파싱하여
     * 진본성 점수(0..100)를 반환합니다.
     * 파일이 없거나 예외 발생 시 안전하게 0을 반환합니다.
     */
    fun inspectImageAuthenticity(
        context: Context,
        uri: Uri,
        absolutePath: String? = null
    ): Int {
        return try {
            val exif: ExifInterface = (if (!absolutePath.isNullOrBlank()) {
                val file = File(absolutePath)
                if (file.exists() && file.canRead()) {
                    ExifInterface(file.absolutePath)
                } else {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        ExifInterface(stream)
                    }
                }
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ExifInterface(stream)
                }
            }) ?: return 0

            val dtOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            val dtDigitized = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
            val hasOriginalDateTime = !dtOriginal.isNullOrBlank() || !dtDigitized.isNullOrBlank()

            val make = exif.getAttribute(ExifInterface.TAG_MAKE)
            val model = exif.getAttribute(ExifInterface.TAG_MODEL)
            val hasCameraModel = !make.isNullOrBlank() || !model.isNullOrBlank()

            val fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
            val expTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
            val iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
                ?: exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
            val hasExposureParams = !fNumber.isNullOrBlank() || !expTime.isNullOrBlank() || !iso.isNullOrBlank()

            val lat = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
            val lon = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
            val hasGps = !lat.isNullOrBlank() || !lon.isNullOrBlank()

            computeScoreFromTags(
                hasOriginalDateTime = hasOriginalDateTime,
                hasCameraModel = hasCameraModel,
                hasExposureParams = hasExposureParams,
                hasGps = hasGps
            )
        } catch (_: Throwable) {
            0
        }
    }
}
