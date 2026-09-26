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
import java.net.URLEncoder

class EngagementService : Service() {

    private data class AccountSession(val handle: String, val did: String, val jwt: String)

    companion object {
        const val ACTION_START = "com.bluesky.bot.action.START"
        const val ACTION_STOP = "com.bluesky.bot.action.STOP"

        const val EXTRA_ACCOUNT_HANDLES = "extra_account_handles"
        const val EXTRA_ACCOUNT_DIDS = "extra_account_dids"
        const val EXTRA_ACCOUNT_JWTS = "extra_account_jwts"
        const val EXTRA_TARGET_HANDLES = "extra_target_handles"
        const val EXTRA_COMMENTS = "extra_comments"
        const val EXTRA_DELAY_SECONDS = "extra_delay_seconds"

        const val BROADCAST_LOG = "com.bluesky.bot.broadcast.LOG"
        const val BROADCAST_FINISHED = "com.bluesky.bot.broadcast.FINISHED"
        const val EXTRA_LOG_MESSAGE = "extra_log_message"

        private const val CHANNEL_ID = "engagement_channel"
        private const val NOTIFICATION_ID = 1001
        private const val VISIBILITY_HIDDEN_STREAK_ALERT = 3
    }

    private val client = OkHttpClient()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var engagementJob: Job? = null

    private val hiddenStreakByAccount = mutableMapOf<String, Int>()

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
                val accountHandles = intent.getStringArrayListExtra(EXTRA_ACCOUNT_HANDLES) ?: arrayListOf()
                val accountDids = intent.getStringArrayListExtra(EXTRA_ACCOUNT_DIDS) ?: arrayListOf()
                val accountJwts = intent.getStringArrayListExtra(EXTRA_ACCOUNT_JWTS) ?: arrayListOf()
                val targetHandles = intent.getStringArrayListExtra(EXTRA_TARGET_HANDLES) ?: arrayListOf()
                val comments = intent.getStringArrayListExtra(EXTRA_COMMENTS) ?: arrayListOf()
                val delaySeconds = intent.getIntExtra(EXTRA_DELAY_SECONDS, 10)

                if (accountHandles.isEmpty() ||
                    accountHandles.size != accountDids.size ||
                    accountHandles.size != accountJwts.size ||
                    targetHandles.isEmpty() || comments.isEmpty()
                ) {
                    broadcastLog("خطأ: بيانات ناقصة لبدء الخدمة.")
                    stopSelfSafely()
                    return START_NOT_STICKY
                }

                val accounts = accountHandles.indices.map {
                    AccountSession(accountHandles[it], accountDids[it], accountJwts[it])
                }

                startForeground(NOTIFICATION_ID, buildNotification("بدء التفاعل...", 0, targetHandles.size))
                startEngagement(accounts, targetHandles, comments, delaySeconds)
            }
        }
        return START_NOT_STICKY
    }

    private fun startEngagement(
        accounts: List<AccountSession>,
        targetHandles: List<String>,
        comments: List<String>,
        delaySeconds: Int
    ) {
        isRunning = true
        hiddenStreakByAccount.clear()
        engagementJob?.cancel()
        engagementJob = serviceScope.launch {
            val activeAccounts = accounts.toMutableList()
            var accountBag = mutableListOf<AccountSession>()

            fun pickNextAccount(): AccountSession? {
                if (activeAccounts.isEmpty()) return null
                if (accountBag.isEmpty()) {
                    accountBag = activeAccounts.shuffled().toMutableList()
                }
                return accountBag.removeAt(0)
            }

            fun excludeAccount(handle: String) {
                activeAccounts.removeAll { it.handle == handle }
                accountBag.removeAll { it.handle == handle }
            }

            broadcastLog(
                "بدء التفاعل مع ${targetHandles.size} حساب فريد عبر ${accounts.size} حساب بوت " +
                    "(${accounts.joinToString(", ") { it.handle }})، باستخدام ${comments.size} " +
                    "نص تعليق مختلف... (التأخير: $delaySeconds ثانية)"
            )

            for ((index, targetHandle) in targetHandles.withIndex()) {
                if (!isRunning) {
                    broadcastLog("تم إيقاف التفاعل بواسطة المستخدم.")
                    break
                }

                val account = pickNextAccount()
                if (account == null) {
                    broadcastLog("🛑 تم إيقاف العملية بالكامل: جميع الحسابات استُبعدت بسبب التقييد.")
                    break
                }

                val comment = comments[index % comments.size]
                val cleanTarget = targetHandle.replace("@", "")

                updateNotification(
                    "${account.handle} → $cleanTarget (${index + 1}/${targetHandles.size})",
                    index,
                    targetHandles.size
                )

                try {
                    val resolveReq = Request.Builder()
                        .url("https://bsky.social/xrpc/com.atproto.identity.resolveHandle?handle=$cleanTarget")
                        .build()
                    val resolveRes = client.newCall(resolveReq).execute()

                    if (resolveRes.isSuccessful) {
                        val targetDid = JSONObject(resolveRes.body?.string() ?: "").getString("did")

                        val feedReq = Request.Builder()
                            .url("https://bsky.social/xrpc/app.bsky.feed.getAuthorFeed?actor=$targetDid&limit=1")
                            .addHeader("Authorization", "Bearer ${account.jwt}")
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
                                    put("repo", account.did)
                                    put("collection", "app.bsky.feed.post")
                                    put("record", replyRecord)
                                }

                                val postBody = createRecordJson.toString()
                                    .toRequestBody("application/json".toMediaType())
                                val commentReq = Request.Builder()
                                    .url("https://bsky.social/xrpc/com.atproto.repo.createRecord")
                                    .addHeader("Authorization", "Bearer ${account.jwt}")
                                    .post(postBody)
                                    .build()

                                val commentRes = client.newCall(commentReq).execute()
                                val commentBodyStr = commentRes.body?.string() ?: ""

                                if (commentRes.isSuccessful) {
                                    broadcastLog(
                                        "تم التعليق من ${account.handle} بنجاح على $cleanTarget: \"$comment\""
                                    )
                                    val newPostUri = JSONObject(commentBodyStr).optString("uri")
                                    if (newPostUri.isNotEmpty()) {
                                        val shouldExclude = verifyVisibilityAndWarn(account.handle, newPostUri, cleanTarget)
                                        if (shouldExclude) {
                                            excludeAccount(account.handle)
                                        }
                                    }
                                } else {
                                    broadcastLog("فشل التعليق من ${account.handle} على $cleanTarget")
                                }
                            } else {
                                broadcastLog("لا توجد منشورات للحساب $cleanTarget")
                            }
                        }
                    } else {
                        broadcastLog("تعذر العثور على $cleanTarget")
                    }
                } catch (e: Exception) {
                    broadcastLog("خطأ مع $cleanTarget: ${e.message}")
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

    private suspend fun verifyVisibilityAndWarn(accountHandle: String, postUri: String, targetHandle: String): Boolean {
        delay(4000)
        val visible = isPostPubliclyVisible(postUri)

        if (visible) {
            hiddenStreakByAccount[accountHandle] = 0
            return false
        }

        val streak = (hiddenStreakByAccount[accountHandle] ?: 0) + 1
        hiddenStreakByAccount[accountHandle] = streak

        broadcastLog(
            "⚠️ تنبيه: تعليق $accountHandle على $targetHandle قد لا يكون ظاهراً للعامة. " +
                "($streak من $VISIBILITY_HIDDEN_STREAK_ALERT)"
        )

        if (streak >= VISIBILITY_HIDDEN_STREAK_ALERT) {
            broadcastLog(
                "🚨 تم استبعاد حساب $accountHandle من العملية الحالية: آخر $streak تعليقات لم تظهر " +
                    "للعامة (يُحتمل أن الحساب مقيّد من بلوسكاي). سيتم توزيع الأهداف المتبقية على بقية الحسابات."
            )
            return true
        }
        return false
    }

    private fun isPostPubliclyVisible(postUri: String): Boolean {
        return try {
            val encodedUri = URLEncoder.encode(postUri, "UTF-8")
            val req = Request.Builder()
                .url("https://public.api.bsky.app/xrpc/app.bsky.feed.getPostThread?uri=$encodedUri&depth=0")
                .build()
            val res = client.newCall(req).execute()
            if (!res.isSuccessful) return false

            val bodyStr = res.body?.string() ?: return false
            val thread = JSONObject(bodyStr).optJSONObject("thread") ?: return false
            thread.optString("\$type") == "app.bsky.feed.defs#threadViewPost"
        } catch (e: Exception) {
            false
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
