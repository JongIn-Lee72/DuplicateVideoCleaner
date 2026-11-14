package com.antigravity.duplicleaner.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.antigravity.duplicleaner.domain.model.AutoScanInterval

object AutoScanScheduler {

    const val AUTO_SCAN_JOB_ID = 20261111
    const val NOTIFICATION_CHANNEL_ID = "duplicleaner_auto_scan"

    fun schedulePeriodicScan(
        context: Context,
        interval: AutoScanInterval,
        requireCharging: Boolean = true
    ): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        if (interval == AutoScanInterval.OFF || interval.intervalHours <= 0L) {
            scheduler.cancel(AUTO_SCAN_JOB_ID)
            return false
        }

        ensureNotificationChannel(context)

        val intervalMillis = interval.intervalHours * 3600_000L
        val serviceComponent = ComponentName(context, AutoScanJobService::class.java)
        val jobInfo = JobInfo.Builder(AUTO_SCAN_JOB_ID, serviceComponent)
            .setPeriodic(intervalMillis)
            .setRequiresCharging(requireCharging)
            .setRequiresBatteryNotLow(true)
            .build()

        return scheduler.schedule(jobInfo) == JobScheduler.RESULT_SUCCESS
    }

    fun isScheduled(context: Context): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            scheduler.getPendingJob(AUTO_SCAN_JOB_ID) != null
        } else {
            scheduler.allPendingJobs.any { it.id == AUTO_SCAN_JOB_ID }
        }
    }

    fun cancelJob(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(AUTO_SCAN_JOB_ID)
    }

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "자동 백그라운드 스캔",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "충전 중 자동 중복 파일 탐지 및 정리 권장 알림"
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
