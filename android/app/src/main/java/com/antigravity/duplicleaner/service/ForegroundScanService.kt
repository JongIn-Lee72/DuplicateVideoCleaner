package com.antigravity.duplicleaner.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.antigravity.duplicleaner.MainActivity

class ForegroundScanService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_OR_UPDATE
        if (action == ACTION_STOP) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping foreground", e)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "중복 파일 정밀 검사 중..."
        val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: "잠시만 기다려주세요..."
        val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0) ?: 0
        val max = intent?.getIntExtra(EXTRA_MAX, 0) ?: 0

        ensureNotificationChannel(this)
        val notification = buildNotification(this, title, subtitle, progress, max)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground with type failed, falling back", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "startForeground fallback failed", e2)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in stopForeground during onDestroy", e)
        }
    }

    companion object {
        private const val TAG = "ForegroundScanService"

        const val CHANNEL_ID = "duplicleaner_foreground_scan"
        const val CHANNEL_NAME = "중복 파일 정밀 검사"
        const val NOTIFICATION_ID = 20261113

        const val ACTION_START_OR_UPDATE = "com.antigravity.duplicleaner.action.START_OR_UPDATE"
        const val ACTION_STOP = "com.antigravity.duplicleaner.action.STOP"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SUBTITLE = "extra_subtitle"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_MAX = "extra_max"

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val notificationManager =
                        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "중복 파일 정밀 검사 진행 상태 알림"
                        setShowBadge(false)
                    }
                    notificationManager?.createNotificationChannel(channel)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create notification channel", e)
                }
            }
        }

        fun buildNotification(
            context: Context,
            title: String,
            subtitle: String,
            progress: Int,
            max: Int
        ): Notification {
            val notifyIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(context, 0, notifyIntent, flags)

            val isIndeterminate = max <= 0
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(if (isIndeterminate) 0 else max, progress, isIndeterminate)
                .build()
        }

        fun start(
            context: Context,
            title: String = "중복 파일 정밀 검사 중...",
            subtitle: String = "잠시만 기다려주세요..."
        ) {
            try {
                ensureNotificationChannel(context)
                val intent = Intent(context, ForegroundScanService::class.java).apply {
                    action = ACTION_START_OR_UPDATE
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_SUBTITLE, subtitle)
                    putExtra(EXTRA_PROGRESS, 0)
                    putExtra(EXTRA_MAX, 0)
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ForegroundScanService", e)
            }
        }

        fun updateProgress(
            context: Context,
            processed: Int,
            total: Int,
            phaseTitle: String
        ) {
            try {
                ensureNotificationChannel(context)
                val subtitle = if (total > 0) "$phaseTitle ($processed / $total)" else phaseTitle
                val notification = buildNotification(
                    context,
                    title = "중복 파일 정밀 검사 중...",
                    subtitle = subtitle,
                    progress = processed,
                    max = total
                )
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.notify(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update notification progress", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, ForegroundScanService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(NOTIFICATION_ID)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop ForegroundScanService", e)
            }
        }
    }
}
