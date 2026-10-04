package com.qaqh.harness.connection

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 常驻前台服务：维持与电脑端 QAQ-Harness backend 的局域网连接。
 * 后续在这里实现实时状态同步与授权请求推送（Android 16 实况通知）。
 */
class ConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            "连接服务",
            android.app.NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("QAQ-Harness")
            .setContentText("正在等待与电脑端建立连接")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "connection"
        const val NOTIFICATION_ID = 1
    }
}
