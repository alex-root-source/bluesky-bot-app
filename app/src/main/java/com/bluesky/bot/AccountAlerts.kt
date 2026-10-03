package com.bluesky.bot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * نقطة موحّدة لتعطيل حساب بوت بشكل دائم + إشعار المستخدم، يستخدمها كل من
 * EngagementService (الوضع الرئيسي) و WarmupEngagementService (وضع الإحماء)
 * و AccountHealthCheckWorker (الفحص الدوري) حتى لا يتكرر نفس المنطق في أكثر من مكان.
 */
object AccountAlerts {
    const val ALERT_CHANNEL_ID = "engagement_alerts_channel"
    private const val ALERT_NOTIFICATION_ID_BASE = 2000

    const val BROADCAST_ACCOUNT_EXCLUDED = "com.bluesky.bot.broadcast.ACCOUNT_EXCLUDED"
    const val EXTRA_EXCLUDED_ACCOUNT_HANDLE = "extra_excluded_account_handle"
    const val EXTRA_EXCLUDED_ACCOUNT_REASON = "extra_excluded_account_reason"

    @Volatile
    private var counter = 0

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "تنبيهات استبعاد الحسابات",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "إشعار فوري عند استبعاد حساب بوت من العملية"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    /** يعطّل الحساب بشكل دائم (عبر كل الأوضاع)، يبث Broadcast، ويطلق إشعاراً منبثقاً. */
    fun exclude(context: Context, handle: String, reason: String) {
        BotPrefs.disableAccount(context, handle, reason)
        ensureChannel(context)

        val intent = Intent(BROADCAST_ACCOUNT_EXCLUDED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_EXCLUDED_ACCOUNT_HANDLE, handle)
            putExtra(EXTRA_EXCLUDED_ACCOUNT_REASON, reason)
        }
        context.sendBroadcast(intent)

        val manager = context.getSystemService(NotificationManager::class.java)
        val notificationId = ALERT_NOTIFICATION_ID_BASE + (counter++)

        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setContentTitle("🚫 تم استبعاد حساب: $handle")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        manager.notify(notificationId, notification)
    }
}
