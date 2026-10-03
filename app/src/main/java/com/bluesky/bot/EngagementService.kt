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
import okhttp3.OkHttpClient
import org.json.JSONObject

class EngagementService : Service() {

    private data class PendingVisibilityCheck(
        val accountHandle: String,
        val postUri: String,
        val targetHandle: String,
        val dueAtMillis: Long
    )

    companion object {
        const val ACTION_START = "com.bluesky.bot.action.START"
        const val ACTION_STOP = "com.bluesky.bot.action.STOP"

        const val EXTRA_ACCOUNT_HANDLES = "extra_account_handles"
        const val EXTRA_ACCOUNT_DIDS = "extra_account_dids"
        const val EXTRA_ACCOUNT_JWTS = "extra_account_jwts"
        const val EXTRA_ACCOUNT_REFRESH_JWTS = "extra_account_refresh_jwts"
        const val EXTRA_TARGET_HANDLES = "extra_target_handles"
        const val EXTRA_COMMENTS = "extra_comments"
        const val EXTRA_EXCLUDED_HANDLES = "extra_excluded_handles"
        const val EXTRA_ALLOWED_LANGS = "extra_allowed_langs"
        const val EXTRA_MIN_FOLLOWERS = "extra_min_followers"
        const val EXTRA_MAX_REPLIES = "extra_max_replies"
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
    }

    private val client = OkHttpClient()
    private val api = BskyApi(client) { msg -> broadcastLog(msg) }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var engagementJob: Job? = null

    private val hiddenStreakByAccount = mutableMapOf<String, Int>()

    @Volatile
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AccountAlerts.ensureChannel(this)
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
                val accountRefreshJwts = intent.getStringArrayListExtra(EXTRA_ACCOUNT_REFRESH_JWTS) ?: arrayListOf()
                val targetHandles = intent.getStringArrayListExtra(EXTRA_TARGET_HANDLES) ?: arrayListOf()
                val comments = intent.getStringArrayListExtra(EXTRA_COMMENTS) ?: arrayListOf()
                val excludedHandles = intent.getStringArrayListExtra(EXTRA_EXCLUDED_HANDLES) ?: arrayListOf()
                val allowedLangs = (intent.getStringArrayListExtra(EXTRA_ALLOWED_LANGS) ?: arrayListOf())
                    .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
                val minFollowers = intent.getIntExtra(EXTRA_MIN_FOLLOWERS, 0).coerceAtLeast(0)
                val maxReplies = intent.getIntExtra(EXTRA_MAX_REPLIES, 0).coerceAtLeast(0)
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
                    BskyApi.AccountSession(
                        handle = accountHandles[it],
                        did = accountDids[it],
                        jwt = accountJwts[it],
                        refreshJwt = accountRefreshJwts.getOrElse(it) { "" }
                    )
                }
                // نتذكر الحساب (هاندل + refreshJwt) حتى يستطيع الفحص الدوري بالخلفية
                // التأكد من سلامته لاحقاً حتى بدون تشغيل أي جلسة.
                accounts.forEach { BotPrefs.rememberAccount(this, it.handle, it.refreshJwt) }

                startForeground(NOTIFICATION_ID, buildNotification("بدء التفاعل...", 0, targetHandles.size))
                startEngagement(
                    accounts, targetHandles, comments, delayMin, delayMax,
                    excludedHandles, allowedLangs, minFollowers, maxReplies
                )
            }
        }
        return START_NOT_STICKY
    }

    private fun startEngagement(
        accountsIn: List<BskyApi.AccountSession>,
        targetHandles: List<String>,
        comments: List<String>,
        delayMin: Int,
        delayMax: Int,
        excludedHandles: List<String>,
        allowedLangs: Set<String>,
        minFollowers: Int,
        maxReplies: Int
    ) {
        isRunning = true
        hiddenStreakByAccount.clear()
        engagementJob?.cancel()
        engagementJob = serviceScope.launch {
            val startTimeMillis = System.currentTimeMillis()
            var successCount = 0
            var failedCount = 0
            var skippedNoPostCount = 0
            var skippedQualityCount = 0

            val permanentlyDisabled = BotPrefs.getDisabledAccounts(this@EngagementService)
            val accounts = accountsIn.filterNot { it.handle in permanentlyDisabled }
            if (accounts.size < accountsIn.size) {
                broadcastLog("⏭️ تم تجاهل ${accountsIn.size - accounts.size} حساب بوت معطّل بشكل دائم.")
            }
            if (accounts.isEmpty()) {
                broadcastLog("🛑 لا يوجد أي حساب بوت نشط (الكل معطّل). أعد تفعيل حساب من شاشة السجل أولاً.")
                broadcastFinished()
                stopSelfSafely()
                return@launch
            }

            val excludedDids = excludedHandles.mapNotNull { api.resolveHandle(it) }.toSet()
            if (excludedHandles.isNotEmpty()) {
                broadcastLog("🛡️ تم تحميل ${excludedDids.size} من ${excludedHandles.size} حساب مستثنى (لن يُعلَّق على منشوراتهم حتى لو أُعيد نشرها).")
            }
            if (allowedLangs.isNotEmpty()) {
                broadcastLog("🌐 فلترة اللغة مفعّلة: سيُفضَّل التعليق فقط على منشورات بلغة (${allowedLangs.joinToString(", ")}).")
            }
            if (minFollowers > 0) {
                broadcastLog("👥 فلترة الجودة: سيتم تخطي الحسابات بأقل من $minFollowers متابع.")
            }
            if (maxReplies > 0) {
                broadcastLog("💬 فلترة الجودة: سيتم تخطي المنشورات بأكثر من $maxReplies رد.")
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
            var accountBag = mutableListOf<BskyApi.AccountSession>()
            val pendingChecks = mutableListOf<PendingVisibilityCheck>()
            var stoppedManually = false

            fun pickNextAccount(): BskyApi.AccountSession? {
                if (activeAccounts.isEmpty()) return null
                if (accountBag.isEmpty()) {
                    accountBag = activeAccounts.shuffled().toMutableList()
                }
                return accountBag.removeAt(0)
            }

            fun excludeAccount(handle: String, reason: String) {
                activeAccounts.removeAll { it.handle == handle }
                accountBag.removeAll { it.handle == handle }
                AccountAlerts.exclude(this@EngagementService, handle, reason)
            }

            suspend fun drainDueChecks() {
                val now = System.currentTimeMillis()
                val due = pendingChecks.filter { it.dueAtMillis <= now }
                if (due.isEmpty()) return
                pendingChecks.removeAll(due)
                for (item in due) {
                    val shouldExclude = verifyVisibilityAndWarn(item.accountHandle, item.postUri, item.targetHandle)
                    if (shouldExclude) {
                        excludeAccount(
                            item.accountHandle,
                            "آخر $VISIBILITY_HIDDEN_STREAK_ALERT تعليقات لم تظهر للعامة (يُحتمل shadowban من بلوسكاي)"
                        )
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
                    val targetDid = api.resolveHandle(cleanTarget)
                    if (targetDid == null) {
                        failedCount++
                        broadcastLog("تعذر العثور على $cleanTarget")
                        continue@targetLoop
                    }

                    if (targetDid in excludedDids) {
                        broadcastLog("⏭️ تم تخطي $cleanTarget (ضمن قائمة الاستثناء).")
                        continue@targetLoop
                    }

                    if (minFollowers > 0) {
                        val followers = api.fetchFollowersCount(targetDid)
                        if (followers != null && followers < minFollowers) {
                            skippedQualityCount++
                            broadcastLog("⏭️ تم تخطي $cleanTarget: عدد المتابعين ($followers) أقل من الحد الأدنى ($minFollowers).")
                            continue@targetLoop
                        }
                    }

                    when (val lookup = api.findTargetPost(account, targetDid, excludedDids, allowedLangs, maxReplies)) {
                        is BskyApi.PostLookup.RateLimited -> {
                            broadcastLog(
                                "⏳ تم تجاوز الحد المسموح (429) أثناء جلب منشورات $cleanTarget - " +
                                    "إيقاف مؤقت ${RATE_LIMIT_BACKOFF_MS / 1000} ثانية..."
                            )
                            delay(RATE_LIMIT_BACKOFF_MS)
                            continue@targetLoop
                        }

                        is BskyApi.PostLookup.Unavailable -> {
                            val reason = "الحساب أصبح غير صالح أثناء جلب المنشورات حتى بعد محاولة تجديد الجلسة (رمز ${lookup.code})"
                            broadcastLog("🚫 الحساب ${account.handle} $reason - يتم استبعاده فوراً من العملية.")
                            excludeAccount(account.handle, reason)
                            continue@targetLoop
                        }

                        is BskyApi.PostLookup.OtherError -> {
                            failedCount++
                            broadcastLog("فشل جلب منشورات $cleanTarget (رمز ${lookup.code})")
                        }

                        is BskyApi.PostLookup.NoOriginalPost -> {
                            skippedNoPostCount++
                            broadcastLog(
                                "⏭️ تم تخطي $cleanTarget: لا يوجد له منشور أو رد مناسب " +
                                    "(فقط إعادات نشر، أو محتوى مرتبط بحسابات مستثناة، أو ردود مغلقة، أو لغة/جودة غير مطابقة)."
                            )
                            continue@targetLoop
                        }

                        is BskyApi.PostLookup.Found -> {
                            if (lookup.isReply) {
                                broadcastLog(
                                    "↩️ آخر منشور أصلي لـ $cleanTarget قديم أو غير موجود، سيتم التعليق على آخر رد له."
                                )
                            }

                            val commentRes = api.postReply(
                                account, comment, lookup.rootUri, lookup.rootCid, lookup.uri, lookup.cid
                            )
                            val commentBodyStr = commentRes.body?.string() ?: ""

                            if (commentRes.code == 429) {
                                broadcastLog(
                                    "⏳ تم تجاوز الحد المسموح (429) أثناء التعليق على $cleanTarget - " +
                                        "إيقاف مؤقت ${RATE_LIMIT_BACKOFF_MS / 1000} ثانية..."
                                )
                                delay(RATE_LIMIT_BACKOFF_MS)
                                continue@targetLoop
                            }

                            if (api.isAccountUnavailable(commentRes.code)) {
                                val reason = "الحساب أصبح غير صالح أثناء التعليق حتى بعد محاولة تجديد الجلسة (رمز ${commentRes.code})"
                                broadcastLog("🚫 الحساب ${account.handle} $reason - يتم استبعاده فوراً من العملية.")
                                excludeAccount(account.handle, reason)
                                continue@targetLoop
                            }

                            if (commentRes.isSuccessful) {
                                successCount++
                                broadcastLog("تم التعليق من ${account.handle} بنجاح على $cleanTarget: \"$comment\"")
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

            BotPrefs.addSessionHistoryEntry(
                this@EngagementService,
                BotPrefs.SessionHistoryEntry(
                    timestampMillis = System.currentTimeMillis(),
                    totalTargets = filteredTargets.size,
                    success = successCount,
                    failed = failedCount,
                    skippedDuplicate = skippedDuplicateCount,
                    skippedNoPost = skippedNoPostCount + skippedQualityCount,
                    excludedAccounts = excludedAccountsCount,
                    elapsedMs = elapsedMs
                )
            )

            broadcastLog(
                "📊 ملخص الجلسة: نجح $successCount | فشل $failedCount | تم تخطيه (سابقاً) $skippedDuplicateCount | " +
                    "بدون منشور مناسب $skippedNoPostCount | تخطي جودة $skippedQualityCount | " +
                    "حسابات مستبعدة $excludedAccountsCount | الوقت الإجمالي ${elapsedMinutes} د ${elapsedSecondsRemainder} ث"
            )
            broadcastLog("اكتملت العملية بالكامل!")
            broadcastFinished()
            stopSelfSafely()
        }
    }

    private suspend fun verifyVisibilityAndWarn(accountHandle: String, postUri: String, targetHandle: String): Boolean {
        val visible = api.isPostPubliclyVisible(postUri)

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
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تفاعل Bluesky Bot",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار مستمر أثناء تشغيل عملية التفاعل بالخلفية (الوضع الرئيسي)"
            }
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
            .setContentTitle("Bluesky Bot Hub - الوضع الرئيسي")
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
