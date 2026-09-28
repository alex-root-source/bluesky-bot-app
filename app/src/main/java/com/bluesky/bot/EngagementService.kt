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

    private data class PendingVisibilityCheck(
        val accountHandle: String,
        val postUri: String,
        val targetHandle: String,
        val dueAtMillis: Long
    )

    private sealed class PostLookup {
        data class Found(
            val uri: String,
            val cid: String,
            val rootUri: String,
            val rootCid: String,
            val isReply: Boolean
        ) : PostLookup()
        object NoOriginalPost : PostLookup()
        object RateLimited : PostLookup()
        data class Unavailable(val code: Int) : PostLookup()
        data class OtherError(val code: Int) : PostLookup()
    }

    companion object {
        const val ACTION_START = "com.bluesky.bot.action.START"
        const val ACTION_STOP = "com.bluesky.bot.action.STOP"

        const val EXTRA_ACCOUNT_HANDLES = "extra_account_handles"
        const val EXTRA_ACCOUNT_DIDS = "extra_account_dids"
        const val EXTRA_ACCOUNT_JWTS = "extra_account_jwts"
        const val EXTRA_TARGET_HANDLES = "extra_target_handles"
        const val EXTRA_COMMENTS = "extra_comments"
        const val EXTRA_EXCLUDED_HANDLES = "extra_excluded_handles"
        const val EXTRA_DELAY_MIN_SECONDS = "extra_delay_min_seconds"
        const val EXTRA_DELAY_MAX_SECONDS = "extra_delay_max_seconds"

        const val BROADCAST_LOG = "com.bluesky.bot.broadcast.LOG"
        const val BROADCAST_FINISHED = "com.bluesky.bot.broadcast.FINISHED"
        const val EXTRA_LOG_MESSAGE = "extra_log_message"

        private const val CHANNEL_ID = "engagement_channel"
        private const val NOTIFICATION_ID = 1001
        private const val VISIBILITY_HIDDEN_STREAK_ALERT = 3
        private const val RATE_LIMIT_BACKOFF_MS = 90_000L
        private const val PENDING_CHECK_DELAY_MS = 45_000L
        private const val FEED_PAGE_SIZE = 50
        private const val MAX_FEED_PAGES = 3
        private const val MAX_POST_AGE_HOURS = 24L
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
                val excludedHandles = intent.getStringArrayListExtra(EXTRA_EXCLUDED_HANDLES) ?: arrayListOf()
                val delayMin = intent.getIntExtra(EXTRA_DELAY_MIN_SECONDS, 8).coerceAtLeast(1)
                val delayMax = intent.getIntExtra(EXTRA_DELAY_MAX_SECONDS, 15).coerceAtLeast(delayMin)

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
                startEngagement(accounts, targetHandles, comments, delayMin, delayMax, excludedHandles)
            }
        }
        return START_NOT_STICKY
    }

    private fun startEngagement(
        accounts: List<AccountSession>,
        targetHandles: List<String>,
        comments: List<String>,
        delayMin: Int,
        delayMax: Int,
        excludedHandles: List<String>
    ) {
        isRunning = true
        hiddenStreakByAccount.clear()
        engagementJob?.cancel()
        engagementJob = serviceScope.launch {
            val startTimeMillis = System.currentTimeMillis()
            var successCount = 0
            var failedCount = 0
            var skippedNoPostCount = 0

            val excludedDids = excludedHandles.mapNotNull { h ->
                try {
                    val r = client.newCall(
                        Request.Builder()
                            .url("https://bsky.social/xrpc/com.atproto.identity.resolveHandle?handle=$h")
                            .build()
                    ).execute()
                    val body = r.body?.string() ?: ""
                    if (r.isSuccessful) JSONObject(body).getString("did") else null
                } catch (e: Exception) {
                    null
                }
            }.toSet()
            if (excludedHandles.isNotEmpty()) {
                broadcastLog("🛡️ تم تحميل ${excludedDids.size} من ${excludedHandles.size} حساب مستثنى (لن يُعلَّق على منشوراتهم حتى لو أُعيد نشرها).")
            }

            val alreadyCommented = BotPrefs.getCommentedTargets(this@EngagementService)
            val filteredTargets = targetHandles.filter { it !in alreadyCommented }
            val skippedDuplicateCount = targetHandles.size - filteredTargets.size

            if (skippedDuplicateCount > 0) {
                broadcastLog("⏭️ تم تخطي $skippedDuplicateCount حساب تم التعليق عليه مسبقاً في جلسات سابقة.")
            }

            if (filteredTargets.isEmpty()) {
                broadcastLog("لا توجد أهداف جديدة للتعليق عليها (كلها معلّق عليها سابقاً).")
                broadcastFinished()
                stopSelfSafely()
                return@launch
            }

            val activeAccounts = accounts.toMutableList()
            var accountBag = mutableListOf<AccountSession>()
            val pendingChecks = mutableListOf<PendingVisibilityCheck>()
            var stoppedManually = false

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

            suspend fun drainDueChecks() {
                val now = System.currentTimeMillis()
                val due = pendingChecks.filter { it.dueAtMillis <= now }
                if (due.isEmpty()) return
                pendingChecks.removeAll(due)
                for (item in due) {
                    val shouldExclude = verifyVisibilityAndWarn(item.accountHandle, item.postUri, item.targetHandle)
                    if (shouldExclude) {
                        excludeAccount(item.accountHandle)
                    }
                }
            }

            broadcastLog(
                "بدء التفاعل مع ${filteredTargets.size} حساب فريد عبر ${accounts.size} حساب بوت " +
                    "(${accounts.joinToString(", ") { it.handle }})، باستخدام ${comments.size} " +
                    "نص تعليق مختلف... (التأخير: $delayMin-$delayMax ثانية)"
            )

            targetLoop@ for ((index, targetHandle) in filteredTargets.withIndex()) {
                drainDueChecks()

                if (!isRunning) {
                    broadcastLog("تم إيقاف التفاعل بواسطة المستخدم.")
                    stoppedManually = true
                    break@targetLoop
                }

                val account = pickNextAccount()
                if (account == null) {
                    broadcastLog("🛑 تم إيقاف العملية بالكامل: جميع الحسابات استُبعدت بسبب التقييد.")
                    break@targetLoop
                }

                val comment = comments[index % comments.size]
                val cleanTarget = targetHandle.replace("@", "")

                updateNotification(
                    "${account.handle} → $cleanTarget (${index + 1}/${filteredTargets.size})",
                    index,
                    filteredTargets.size
                )

                try {
                    val resolveReq = Request.Builder()
                        .url("https://bsky.social/xrpc/com.atproto.identity.resolveHandle?handle=$cleanTarget")
                        .build()
                    val resolveRes = client.newCall(resolveReq).execute()
                    val resolveBodyStr = resolveRes.body?.string() ?: ""

                    if (resolveRes.code == 429) {
                        broadcastLog(
                            "⏳ تم تجاوز الحد المسموح (429) أثناء البحث عن $cleanTarget - " +
                                "إيقاف مؤقت ${RATE_LIMIT_BACKOFF_MS / 1000} ثانية..."
                        )
                        delay(RATE_LIMIT_BACKOFF_MS)
                        continue@targetLoop
                    }

                    if (resolveRes.isSuccessful) {
                        val targetDid = JSONObject(resolveBodyStr).getString("did")

                        if (targetDid in excludedDids) {
                            broadcastLog("⏭️ تم تخطي $cleanTarget (ضمن قائمة الاستثناء).")
                            continue@targetLoop
                        }

                        when (val lookup = findTargetPost(account, targetDid, excludedDids)) {
                            is PostLookup.RateLimited -> {
                                broadcastLog(
                                    "⏳ تم تجاوز الحد المسموح (429) أثناء جلب منشورات $cleanTarget - " +
                                        "إيقاف مؤقت ${RATE_LIMIT_BACKOFF_MS / 1000} ثانية..."
                                )
                                delay(RATE_LIMIT_BACKOFF_MS)
                                continue@targetLoop
                            }

                            is PostLookup.Unavailable -> {
                                broadcastLog(
                                    "🚫 الحساب ${account.handle} أصبح غير صالح (معلّق أو الجلسة منتهية، رمز ${lookup.code}) " +
                                        "- يتم استبعاده فوراً من العملية."
                                )
                                excludeAccount(account.handle)
                                continue@targetLoop
                            }

                            is PostLookup.OtherError -> {
                                failedCount++
                                broadcastLog("فشل جلب منشورات $cleanTarget (رمز ${lookup.code})")
                            }

                            is PostLookup.NoOriginalPost -> {
                                skippedNoPostCount++
                                broadcastLog(
                                    "⏭️ تم تخطي $cleanTarget: لا يوجد له منشور أو رد مناسب " +
                                        "(فقط إعادات نشر أو محتوى مرتبط بحسابات مستثناة)."
                                )
                                continue@targetLoop
                            }

                            is PostLookup.Found -> {
                                val postUri = lookup.uri
                                val postCid = lookup.cid
                                if (lookup.isReply) {
                                    broadcastLog(
                                        "↩️ آخر منشور أصلي لـ $cleanTarget أقدم من $MAX_POST_AGE_HOURS ساعة " +
                                            "(أو غير موجود)، سيتم التعليق على آخر رد له."
                                    )
                                }

                                val replyRecord = JSONObject().apply {
                                    put("text", comment)
                                    put("createdAt", java.time.Instant.now().toString())
                                    put("reply", JSONObject().apply {
                                        put("root", JSONObject().apply {
                                            put("uri", lookup.rootUri)
                                            put("cid", lookup.rootCid)
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

                                if (commentRes.code == 429) {
                                    broadcastLog(
                                        "⏳ تم تجاوز الحد المسموح (429) أثناء التعليق على $cleanTarget - " +
                                            "إيقاف مؤقت ${RATE_LIMIT_BACKOFF_MS / 1000} ثانية..."
                                    )
                                    delay(RATE_LIMIT_BACKOFF_MS)
                                    continue@targetLoop
                                }

                                if (isAccountUnavailable(commentRes.code)) {
                                    broadcastLog(
                                        "🚫 الحساب ${account.handle} أصبح غير صالح (معلّق أو الجلسة منتهية، رمز ${commentRes.code}) " +
                                            "- يتم استبعاده فوراً من العملية."
                                    )
                                    excludeAccount(account.handle)
                                    continue@targetLoop
                                }

                                if (commentRes.isSuccessful) {
                                    successCount++
                                    broadcastLog(
                                        "تم التعليق من ${account.handle} بنجاح على $cleanTarget: \"$comment\""
                                    )
                                    BotPrefs.addCommentedTarget(this@EngagementService, cleanTarget)

                                    val newPostUri = JSONObject(commentBodyStr).optString("uri")
                                    if (newPostUri.isNotEmpty()) {
                                        pendingChecks.add(
                                            PendingVisibilityCheck(
                                                accountHandle = account.handle,
                                                postUri = newPostUri,
                                                targetHandle = cleanTarget,
                                                dueAtMillis = System.currentTimeMillis() + PENDING_CHECK_DELAY_MS
                                            )
                                        )
                                    }
                                } else {
                                    failedCount++
                                    broadcastLog("فشل التعليق من ${account.handle} على $cleanTarget")
                                }
                            }
                        }
                    } else {
                        failedCount++
                        broadcastLog("تعذر العثور على $cleanTarget")
                    }
                } catch (e: Exception) {
                    failedCount++
                    broadcastLog("خطأ مع $cleanTarget: ${e.message}")
                }

                val randomDelaySeconds = (delayMin..delayMax).random()
                for (i in 0 until randomDelaySeconds) {
                    if (!isRunning) break
                    delay(1000)
                }
            }

            isRunning = false

            if (pendingChecks.isNotEmpty() && !stoppedManually) {
                broadcastLog("🔍 جاري التحقق من ظهور آخر ${pendingChecks.size} تعليق قبل إنهاء الجلسة...")
                while (pendingChecks.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val nextDue = pendingChecks.minOf { it.dueAtMillis }
                    if (nextDue > now) delay(nextDue - now)
                    drainDueChecks()
                }
            }

            val elapsedMs = System.currentTimeMillis() - startTimeMillis
            val elapsedMinutes = elapsedMs / 60000
            val elapsedSecondsRemainder = (elapsedMs / 1000) % 60
            val excludedAccountsCount = accounts.size - activeAccounts.size

            broadcastLog(
                "📊 ملخص الجلسة: نجح $successCount | فشل $failedCount | تم تخطيه (سابقاً) $skippedDuplicateCount | بدون منشور أصلي $skippedNoPostCount | " +
                    "حسابات مستبعدة $excludedAccountsCount | الوقت الإجمالي ${elapsedMinutes} د ${elapsedSecondsRemainder} ث"
            )
            broadcastLog("اكتملت العملية بالكامل!")
            broadcastFinished()
            stopSelfSafely()
        }
    }

    /**
     * يختار المنشور المناسب للتعليق عليه:
     * 1) آخر منشور أصلي كتبه صاحب الحساب، إذا كان عمره أقل من MAX_POST_AGE_HOURS ساعة.
     * 2) وإلا آخر رد كتبه صاحب الحساب (إن وُجد ورد أحدث من المنشور الأصلي).
     * 3) وإلا آخر منشور أصلي مهما كان عمره.
     * يتخطى: إعادات النشر، ما كاتبه غير صاحب الحساب، والمحتوى المرتبط بالحسابات المستثناة
     * (منشور مستثنى، رد على مستثنى، أو اقتباس من مستثنى).
     */
    private fun findTargetPost(
        account: AccountSession,
        targetDid: String,
        excludedDids: Set<String>
    ): PostLookup {
        val maxAgeMs = MAX_POST_AGE_HOURS * 3_600_000L
        val now = System.currentTimeMillis()

        var firstReply: PostLookup.Found? = null
        var cursor: String? = null

        for (page in 0 until MAX_FEED_PAGES) {
            val url = StringBuilder(
                "https://bsky.social/xrpc/app.bsky.feed.getAuthorFeed" +
                    "?actor=$targetDid&limit=$FEED_PAGE_SIZE&filter=posts_with_replies"
            )
            val c = cursor
            if (c != null) url.append("&cursor=").append(URLEncoder.encode(c, "UTF-8"))

            val res = client.newCall(
                Request.Builder()
                    .url(url.toString())
                    .addHeader("Authorization", "Bearer ${account.jwt}")
                    .build()
            ).execute()
            val bodyStr = res.body?.string() ?: ""

            if (res.code == 429) return PostLookup.RateLimited
            if (isAccountUnavailable(res.code)) return PostLookup.Unavailable(res.code)
            if (!res.isSuccessful) return PostLookup.OtherError(res.code)

            val json = JSONObject(bodyStr)
            val feed = json.getJSONArray("feed")

            for (i in 0 until feed.length()) {
                val item = feed.getJSONObject(i)

                // تخطي إعادات النشر
                val reasonType = item.optJSONObject("reason")?.optString("\$type")
                if (reasonType == "app.bsky.feed.defs#reasonRepost") continue

                val post = item.getJSONObject("post")
                val authorDid = post.getJSONObject("author").getString("did")
                if (authorDid != targetDid || authorDid in excludedDids) continue

                // تخطي اقتباسات منشورات المستثنين
                val embed = post.optJSONObject("embed")
                val quotedDid = embed?.optJSONObject("record")
                    ?.optJSONObject("author")?.optString("did")
                    ?: embed?.optJSONObject("record")?.optJSONObject("record")
                        ?.optJSONObject("author")?.optString("did")
                if (!quotedDid.isNullOrEmpty() && quotedDid in excludedDids) continue

                val uri = post.getString("uri")
                val cid = post.getString("cid")
                val replyRef = post.optJSONObject("record")?.optJSONObject("reply")

                if (replyRef == null) {
                    // منشور أصلي: لأن الترتيب من الأحدث للأقدم، أي رد رأيناه قبله هو أحدث منه
                    val postAt = try {
                        java.time.Instant.parse(post.getString("indexedAt")).toEpochMilli()
                    } catch (e: Exception) {
                        now
                    }
                    val original = PostLookup.Found(uri, cid, uri, cid, false)
                    return if (now - postAt < maxAgeMs) original else (firstReply ?: original)
                }

                // رد: نتأكد أنه ليس ردّاً على حساب مستثنى (الأصل أو الأب)
                val rootRef = replyRef.getJSONObject("root")
                val parentRef = replyRef.getJSONObject("parent")
                val rootUri = rootRef.getString("uri")
                val parentUri = parentRef.getString("uri")
                val touchesExcluded = excludedDids.any {
                    rootUri.startsWith("at://$it/") || parentUri.startsWith("at://$it/")
                }
                if (touchesExcluded) continue

                if (firstReply == null) {
                    firstReply = PostLookup.Found(
                        uri = uri,
                        cid = cid,
                        rootUri = rootUri,
                        rootCid = rootRef.getString("cid"),
                        isReply = true
                    )
                }
            }

            val next = json.optString("cursor")
            if (next.isEmpty()) break
            cursor = next
        }

        // لا يوجد منشور أصلي مناسب: نستخدم آخر رد إن وُجد
        return firstReply ?: PostLookup.NoOriginalPost
    }

    private suspend fun verifyVisibilityAndWarn(accountHandle: String, postUri: String, targetHandle: String): Boolean {
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

    private fun isAccountUnavailable(code: Int): Boolean {
        return code == 400 || code == 401 || code == 403
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
