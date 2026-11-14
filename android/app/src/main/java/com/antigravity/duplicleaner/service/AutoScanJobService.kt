package com.antigravity.duplicleaner.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antigravity.duplicleaner.MainActivity
import com.antigravity.duplicleaner.R
import com.antigravity.duplicleaner.data.local.HashCacheDatabase
import com.antigravity.duplicleaner.data.repository.MediaScannerRepository
import com.antigravity.duplicleaner.data.repository.ScanPhase
import com.antigravity.duplicleaner.data.repository.ScanProgressUpdate
import com.antigravity.duplicleaner.domain.model.ScanConfig
import com.antigravity.duplicleaner.domain.model.ScanScopeMode
import com.antigravity.duplicleaner.util.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AutoScanJobService : JobService() {

    private var jobScope: CoroutineScope? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        jobScope = scope

        scope.launch {
            try {
                val repository = MediaScannerRepository(
                    applicationContext,
                    HashCacheDatabase.getInstance(applicationContext).hashCacheDao()
                )
                val config = ScanConfig(
                    scopeMode = ScanScopeMode.ALL_MEDIA,
                    includeSimilarVideos = false
                )

                var completedUpdate: ScanProgressUpdate? = null
                repository.scanDuplicates(config).collect { update ->
                    if (update.phase == ScanPhase.COMPLETED) {
                        completedUpdate = update
                    }
                }

                completedUpdate?.let { finalUpdate ->
                    if (finalUpdate.potentialSavedBytes >= 100L * 1024L * 1024L || finalUpdate.discoveredGroupCount >= 1) {
                        postScanCompletedNotification(applicationContext, finalUpdate)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto scan job failed or was cancelled", e)
            } finally {
                jobFinished(params, false)
            }
        }

        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        jobScope?.cancel()
        jobScope = null
        return false
    }

    private fun postScanCompletedNotification(context: Context, update: ScanProgressUpdate) {
        AutoScanScheduler.ensureNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, flags)

        val savedBytesStr = FormatUtils.formatBytes(update.potentialSavedBytes)
        val contentTitle = "중복 파일 자동 탐지 완료"
        val contentText = "${update.discoveredGroupCount}개 그룹에서 약 ${savedBytesStr}의 정리 가능한 중복 파일이 발견되었습니다."

        val notification = NotificationCompat.Builder(context, AutoScanScheduler.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            notificationManager?.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted", e)
        }
    }

    companion object {
        private const val TAG = "AutoScanJobService"
        const val NOTIFICATION_ID = 20261112
    }
}
