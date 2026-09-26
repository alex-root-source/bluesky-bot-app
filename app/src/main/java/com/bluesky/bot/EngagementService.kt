package com.bluesky.bot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class EngagementService : Service() {

    companion object {
        const val ACTION_START = "com.bluesky.bot.action.START"
        const val ACTION_STOP = "com.bluesky.bot.action.STOP"

        const val EXTRA_ACCESS_JWT = "extra_access_jwt"
        const val EXTRA_USER_DID = "extra_user_did"
        const val EXTRA_HANDLES = "extra_handles"
        const val EXTRA_COMMENTS = "extra_comments"
        const val EXTRA_DELAY_SECONDS = "extra_delay_seconds"

        const val BROADCAST_LOG = "com.bluesky.bot.broadcast.LOG"
        const val BROADCAST_FINISHED = "com.bluesky.bot.broadcast.FINISHED"
        const val EXTRA_LOG_MESSAGE = "extra_log_message"

        private const val CHANNEL_ID = "engagement_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val client = OkHttpClient()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var engagementJob: Job? = null

    @Volatile
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                isRunning = false
                broadcastLog("جاري إيقاف العملية...")
                stopSelfSafely()
            }

            ACTION_START -> {
                val accessJwt = intent.getStringExtra(EXTRA_ACCESS_JWT)
                val userDid = intent.getStringExtra(EXTRA_USER_DID)
                val handles = intent.getStringArrayListExtra(EXTRA_HANDLES) ?: arrayListOf()
                val comments = intent.getStringArrayListExtra(EXTRA_COMMENTS) ?: arrayListOf()
                val delaySeconds = intent.getIntExtra(EXTRA_DELAY_SECONDS, 10)

                if (accessJwt == null || userDid == null || handles.isEmpty() || comments.isEmpty()) {
                    broadcastLog("خطأ: بيانات ناقصة لبدء الخدمة.")
                    stopSelfSafely()
                    return START_NOT_STICKY
                }

                startForeground(NOTIFICATION_ID, buildNotification("بدء التفاعل...", 0, handles.size))
                startEngagement(accessJwt, userDid, handles, comments, delaySeconds)
            }
        }
        return START_NOT_STICKY
    }

    private fun startEngagement(
        accessJwt: String,
        userDid: String,
        handles: List<String>,
        comments: List<String>,
        delaySeconds: Int
    ) {
        isRunning = true
        engagementJob?.cancel()
        engagementJob = serviceScope.launch {
            broadcastLog(
                "بدء التفاعل مع ${handles.size} حساب فريد، باستخدام ${comments.size} " +
                    "نص تعليق مختلف... (التأخير: $delaySeconds ثانية)"
            )

            for ((index, handle) in handles.withIndex()) {
                if (!isRunning) {
                    broadcastLog("تم إيقاف التفاعل بواسطة المستخدم.")
                    break
                }

                val comment = comments[index % comments.size]
                val cleanHandle = handle.replace("@", "")

                updateNotification("جاري التفاعل مع $cleanHandle (${index + 1}/${handles.size})", index, handles.size)

                try {
                    val resolveReq = Request.Builder()
                        .url("https://bsky.social/xrpc/com.atproto.identity.resolveHandle?handle=$cleanHandle")
                        .build()
                    val resolveRes = client.newCall(resolveReq).execute()

                    if (resolveRes.isSuccessful) {
                        val targetDid = JSONObject(resolveRes.body?.string() ?: "").getString("did")

                        val feedReq = Request.Builder()
                            .url("https://bsky.social/xrpc/app.bsky.feed.getAuthorFeed?actor=$targetDid&limit=1")
                            .addHeader("Authorization", "Bearer $accessJwt")
                            .build()
                        val feedRes = client.newCall(feedReq).execute()

                        if (feedRes.isSuccessful) {
                            val feedArray = JSONObject(feedRes.body?.string() ?: "").getJSONArray("feed")
                            if (feedArray.length() > 0) {
                                val post = feedArray.getJSONObject(0).getJSONObject("post")
                                val postUri = post.getString("uri")
                                val postCid = post.getString("cid")

                                val replyRecord = JSONObject().apply {
                                    put("text", comment)
                                    put("createdAt", java.time.Instant.now().toString())
                                    put("reply", JSONObject().apply {
                                        put("root", JSONObject().apply {
                                            put("uri", postUri)
                                            put("cid", postCid)
                                        })
                                        put("parent", JSONObject().apply {
                                            put("uri", postUri)
                                            put("cid", postCid)
                                        })
                                    })
                                }

                                val createRecordJson = JSONObject().apply {
                                    put("repo", userDid)
                                    put("collection", "app.bsky.feed.post")
                                    put("record", replyRecord)
                                }

                                val postBody = createRecordJson.toString()
                                    .toRequestBody("application/json".toMediaType())
                                val commentReq = Request.Builder()
                                    .url("https://bsky.social/xrpc/com.atproto.repo.createRecord")
                                    .addHeader("Authorization", "Bearer $accessJwt")
                                    .post(postBody)
                                    .build()

                                val commentRes = client.newCall(commentReq).execute()
                                if (commentRes.isSuccessful) {
                                    broadcastLog("تم التعليق بنجاح على $cleanHandle: \"$comment\"")
                                } else {
                                    broadcastLog("فشل التعليق على $cleanHandle")
                                }
                            } else {
                                broadcastLog("لا توجد منشورات للحساب $cleanHandle")
                            }
                        }
                    } else {
                        broadcastLog("تعذر العثور على $cleanHandle")
                    }
                } catch (e: Exception) {
                    broadcastLog("خطأ مع $cleanHandle: ${e.message}")
                }

                for (i in 0 until delaySeconds) {
                    if (!isRunning) break
                    delay(1000)
                }
            }

            isRunning = false
            broadcastLog("اكتملت العملية بالكامل!")
            broadcastFinished()
            stopSelfSafely()
        }
    }

    private fun stopSelfSafely() {
        engagementJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
    }

    private fun broadcastLog(message: String) {
        val intent = Intent(BROADCAST_LOG).apply {
            setPackage(packageName)
            putExtra(EXTRA_LOG_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    private fun broadcastFinished() {
        val intent = Intent(BROADCAST_FINISHED).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تفاعل Bluesky Bot",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار مستمر أثناء تشغيل عملية التفاعل بالخلفية"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int, max: Int): Notification {
        val stopIntent = Intent(this, EngagementService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bluesky Bot Hub يعمل بالخلفية")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setProgress(max, progress, false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPendingIntent)
            .build()
    }

    private fun updateNotification(text: String, progress: Int, max: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, progress, max))
    }
}
