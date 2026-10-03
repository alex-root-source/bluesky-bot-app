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

/**
 * وضع الإحماء: نفس آلية الوضع الرئيسي، لكن بسقف تعليقات يومي تدريجي لكل حساب على حدة
 * (يزيد كل بضعة أيام بدل أن يبدأ الحساب الجديد بنفس معدّل حساب قديم من أول يوم).
 * يعمل كخدمة مستقلة تماماً عن EngagementService، فيمكن تشغيل الوضعين معاً أو كل واحد لحاله.
 */
class WarmupEngagementService : Service() {

    companion object {
        const val ACTION_START = "com.bluesky.bot.action.WARMUP_START"
        const val ACTION_STOP = "com.bluesky.bot.action.WARMUP_STOP"

        const val EXTRA_ACCOUNT_HANDLES = "extra_account_handles"
        const val EXTRA_ACCOUNT_DIDS = "extra_account_dids"
        const val EXTRA_ACCOUNT_JWTS = "extra_account_jwts"
        const val EXTRA_ACCOUNT_REFRESH_JWTS = "extra_account_refresh_jwts"
        const val EXTRA_TARGET_HANDLES = "extra_target_handles"
        const val EXTRA_COMMENTS = "extra_comments"
        const val EXTRA_EXCLUDED_HANDLES = "extra_excluded_handles"
        const val EXTRA_DELAY_MIN_SECONDS = "extra_delay_min_seconds"
        const val EXTRA_DELAY_MAX_SECONDS = "extra_delay_max_seconds"

        const val BROADCAST_LOG = "com.bluesky.bot.broadcast.WARMUP_LOG"
        const val BROADCAST_FINISHED = "com.bluesky.bot.broadcast.WARMUP_FINISHED"
        const val EXTRA_LOG_MESSAGE = "extra_log_message"

        private const val CHANNEL_ID = "warmup_channel"
        private const val NOTIFICATION_ID = 1002
        private const val RATE_LIMIT_BACKOFF_MS = 90_000L

        /** سقف التعليقات اليومي حسب رقم يوم الإحماء (اليوم الأول = 1). يزيد تدريجياً. */
        fun dailyCapForDay(day: Int): Int = when {
            day <= 2 -> 2
            day <= 4 -> 4
            day <= 7 -> 6
            day <= 10 -> 10
            else -> 15
        }
    }

    private val client = OkHttpClient()
    private val api = BskyApi(client) { msg -> broadcastLog(msg) }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var engagementJob: Job? = null

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
                broadcastLog("🔥 جاري إيقاف وضع الإحماء...")
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
                val delayMin = intent.getIntExtra(EXTRA_DELAY_MIN_SECONDS, 12).coerceAtLeast(1)
                val delayMax = intent.getIntExtra(EXTRA_DELAY_MAX_SECONDS, 25).coerceAtLeast(delayMin)

                if (accountHandles.isEmpty() ||
                    accountHandles.size != accountDids.size ||
                    accountHandles.size != accountJwts.size ||
                    targetHandles.isEmpty() || comments.isEmpty()
                ) {
                    broadcastLog("🔥 خطأ: بيانات ناقصة لبدء وضع الإحماء (تأكد من اختيار حسابات وأهداف وتعليق).")
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
                accounts.forEach { BotPrefs.rememberAccount(this, it.handle, it.refreshJwt) }

                startForeground(NOTIFICATION_ID, buildNotification("بدء الإحماء...", 0, targetHandles.size))
                startWarmup(accounts, targetHandles, comments, delayMin, delayMax, excludedHandles)
            }
        }
        return START_NOT_STICKY
    }

    private fun startWarmup(
        accountsIn: List<BskyApi.AccountSession>,
        targetHandles: List<String>,
        comments: List<String>,
        delayMin: Int,
        delayMax: Int,
        excludedHandles: List<String>
    ) {
        isRunning = true
        engagementJob?.cancel()
        engagementJob = serviceScope.launch {
            val startTimeMillis = System.currentTimeMillis()
            var successCount = 0
            var failedCount = 0
            var skippedNoPostCount = 0
            var skippedCapCount = 0

            val permanentlyDisabled = BotPrefs.getDisabledAccounts(this@WarmupEngagementService)
            val accounts = accountsIn.filterNot { it.handle in permanentlyDisabled }
            if (accounts.isEmpty()) {
                broadcastLog("🔥🛑 لا يوجد أي حساب إحماء نشط (الكل معطّل).")
                broadcastFinished()
                stopSelfSafely()
                return@launch
            }

            val excludedDids = excludedHandles.mapNotNull { api.resolveHandle(it) }.toSet()

            val alreadyCommented = BotPrefs.getCommentedTargets(this@WarmupEngagementService)
            val filteredTargets = targetHandles.filter { it !in alreadyCommented }
            val skippedDuplicateCount = targetHandles.size - filteredTargets.size

            if (filteredTargets.isEmpty()) {
                broadcastLog("🔥 لا توجد أهداف جديدة (كلها معلّق عليها سابقاً من أي وضع).")
                broadcastFinished()
                stopSelfSafely()
                return@launch
            }

            // سقف كل حساب لهذا اليوم = سقف يومه - ما عُلّق فعلاً اليوم من قبل
            val remainingToday = mutableMapOf<String, Int>()
            accounts.forEach { acc ->
                val day = BotPrefs.warmupDayNumber(this@WarmupEngagementService, acc.handle)
                val cap = dailyCapForDay(day)
                val used = BotPrefs.getWarmupState(this@WarmupEngagementService, acc.handle).todayCount
                remainingToday[acc.handle] = (cap - used).coerceAtLeast(0)
                broadcastLog("🔥 ${acc.handle}: يوم الإحماء #$day، سقف اليوم $cap، متبقٍ ${remainingToday[acc.handle]}")
            }

            val activeAccounts = accounts.filter { (remainingToday[it.handle] ?: 0) > 0 }.toMutableList()
            if (activeAccounts.isEmpty()) {
                broadcastLog("🔥 كل حسابات الإحماء وصلت لسقفها اليومي بالفعل. حاول لاحقاً أو غداً.")
                broadcastFinished()
                stopSelfSafely()
                return@launch
            }

            var accountBag = mutableListOf<BskyApi.AccountSession>()
            fun pickNextAccount(): BskyApi.AccountSession? {
                activeAccounts.removeAll { (remainingToday[it.handle] ?: 0) <= 0 }
                accountBag.removeAll { (remainingToday[it.handle] ?: 0) <= 0 }
                if (activeAccounts.isEmpty()) return null
                if (accountBag.isEmpty()) {
                    accountBag = activeAccounts.shuffled().toMutableList()
                }
                return accountBag.removeAt(0)
            }

            fun excludeAccount(handle: String, reason: String) {
                activeAccounts.removeAll { it.handle == handle }
                accountBag.removeAll { it.handle == handle }
                remainingToday[handle] = 0
                AccountAlerts.exclude(this@WarmupEngagementService, handle, reason)
            }

            broadcastLog(
                "🔥 بدء وضع الإحماء مع ${filteredTargets.size} هدف عبر ${activeAccounts.size} حساب " +
                    "(${activeAccounts.joinToString(", ") { it.handle }})..."
            )

            targetLoop@ for ((index, targetHandle) in filteredTargets.withIndex()) {
                if (!isRunning) {
                    broadcastLog("🔥 تم إيقاف وضع الإحماء بواسطة المستخدم.")
                    break@targetLoop
                }

                val account = pickNextAccount()
                if (account == null) {
                    broadcastLog("🔥 توقف: كل الحسابات إما استُبعدت أو وصلت لسقف اليوم.")
                    break@targetLoop
                }

                val comment = comments[index % comments.size]
                val cleanTarget = targetHandle.replace("@", "")

                updateNotification(
                    "🔥 ${account.handle} → $cleanTarget (${index + 1}/${filteredTargets.size})",
                    index,
                    filteredTargets.size
                )

                try {
                    val targetDid = api.resolveHandle(cleanTarget)
                    if (targetDid == null) {
                        failedCount++
                        broadcastLog("🔥 تعذر العثور على $cleanTarget")
                        continue@targetLoop
                    }
                    if (targetDid in excludedDids) {
                        broadcastLog("🔥⏭️ تم تخطي $cleanTarget (ضمن قائمة الاستثناء).")
                        continue@targetLoop
                    }

                    when (val lookup = api.findTargetPost(account, targetDid, excludedDids, emptySet(), 0)) {
                        is BskyApi.PostLookup.RateLimited -> {
                            broadcastLog("🔥⏳ تجاوز الحد المسموح (429) - إيقاف مؤقت ${RATE_LIMIT_BACKOFF_MS / 1000} ثانية...")
                            delay(RATE_LIMIT_BACKOFF_MS)
                            continue@targetLoop
                        }
                        is BskyApi.PostLookup.Unavailable -> {
                            val reason = "الحساب أصبح غير صالح أثناء جلب المنشورات (وضع الإحماء، رمز ${lookup.code})"
                            broadcastLog("🔥🚫 الحساب ${account.handle} $reason - يتم استبعاده.")
                            excludeAccount(account.handle, reason)
                            continue@targetLoop
                        }
                        is BskyApi.PostLookup.OtherError -> {
                            failedCount++
                            broadcastLog("🔥 فشل جلب منشورات $cleanTarget (رمز ${lookup.code})")
                        }
                        is BskyApi.PostLookup.NoOriginalPost -> {
                            skippedNoPostCount++
                            broadcastLog("🔥⏭️ تم تخطي $cleanTarget: لا يوجد له منشور أو رد مناسب.")
                            continue@targetLoop
                        }
                        is BskyApi.PostLookup.Found -> {
                            val commentRes = api.postReply(
                                account, comment, lookup.rootUri, lookup.rootCid, lookup.uri, lookup.cid
                            )
                            val commentBodyStr = commentRes.body?.string() ?: ""

                            if (commentRes.code == 429) {
                                broadcastLog("🔥⏳ تجاوز الحد المسموح (429) - إيقاف مؤقت ${RATE_LIMIT_BACKOFF_MS / 1000} ثانية...")
                                delay(RATE_LIMIT_BACKOFF_MS)
                                continue@targetLoop
                            }
                            if (api.isAccountUnavailable(commentRes.code)) {
                                val reason = "الحساب أصبح غير صالح أثناء التعليق (وضع الإحماء، رمز ${commentRes.code})"
                                broadcastLog("🔥🚫 الحساب ${account.handle} $reason - يتم استبعاده.")
                                excludeAccount(account.handle, reason)
                                continue@targetLoop
                            }
                            if (commentRes.isSuccessful) {
                                successCount++
                                remainingToday[account.handle] = (remainingToday[account.handle] ?: 1) - 1
                                BotPrefs.recordWarmupComment(this@WarmupEngagementService, account.handle)
                                BotPrefs.addCommentedTarget(this@WarmupEngagementService, cleanTarget)
                                broadcastLog("🔥 تم تعليق إحماء من ${account.handle} على $cleanTarget: \"$comment\" (متبقٍ اليوم: ${remainingToday[account.handle]})")

                                if ((remainingToday[account.handle] ?: 0) <= 0) {
                                    skippedCapCount++
                                    broadcastLog("🔥 ${account.handle} وصل لسقف اليوم، سيُستبعد من بقية هذه الجلسة (ليس تعطيلاً دائماً).")
                                }
                            } else {
                                failedCount++
                                broadcastLog("🔥 فشل التعليق من ${account.handle} على $cleanTarget")
                            }
                        }
                    }
                } catch (e: Exception) {
                    failedCount++
                    broadcastLog("🔥 خطأ مع $cleanTarget: ${e.message}")
                }

                val randomDelaySeconds = (delayMin..delayMax).random()
                for (i in 0 until randomDelaySeconds) {
                    if (!isRunning) break
                    delay(1000)
                }
            }

            isRunning = false

            val elapsedMs = System.currentTimeMillis() - startTimeMillis
            val elapsedMinutes = elapsedMs / 60000
            val elapsedSecondsRemainder = (elapsedMs / 1000) % 60

            broadcastLog(
                "🔥📊 ملخص جلسة الإحماء: نجح $successCount | فشل $failedCount | مكرر $skippedDuplicateCount | " +
                    "بدون منشور $skippedNoPostCount | وصل لسقف اليوم $skippedCapCount | " +
                    "الوقت ${elapsedMinutes} د ${elapsedSecondsRemainder} ث"
            )
            broadcastLog("🔥 اكتملت جلسة الإحماء!")
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
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "🔥 وضع الإحماء",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار مستمر أثناء تشغيل وضع الإحماء التدريجي بالخلفية"
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int, max: Int): Notification {
        val stopIntent = Intent(this, WarmupEngagementService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bluesky Bot Hub - 🔥 وضع الإحماء")
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
