package com.zhenshi.capture.media.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zhenshi.capture.MainActivity
import com.zhenshi.capture.R

class PushForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> startForeground(NOTIFICATION_ID, buildNotification())
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.push_notification_title))
            .setContentText(getString(R.string.push_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.push_title),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "zhenshi_push"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.zhenshi.capture.PUSH_STOP"

        fun start(context: Context) {
            val intent = Intent(context, PushForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PushForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
