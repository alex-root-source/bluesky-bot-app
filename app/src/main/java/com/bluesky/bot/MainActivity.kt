package com.bluesky.bot

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private var accountHandles: ArrayList<String> = arrayListOf()
    private var accountDids: ArrayList<String> = arrayListOf()
    private var accountJwts: ArrayList<String> = arrayListOf()
    private var accountRefreshJwts: ArrayList<String> = arrayListOf()

    private lateinit var loggedInAsText: TextView
    private lateinit var logoutBtn: TextView
    private lateinit var handlesInput: EditText
    private lateinit var excludeInput: EditText
    private lateinit var commentInput: EditText
    private lateinit var delayMinInput: EditText
    private lateinit var delayMaxInput: EditText
    private lateinit var allowedLangsInput: EditText
    private lateinit var minFollowersInput: EditText
    private lateinit var maxRepliesInput: EditText
    private lateinit var historyBtn: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var logText: TextView
    private lateinit var fabMain: FloatingActionButton
    private lateinit var fabWarmup: FloatingActionButton

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                EngagementService.BROADCAST_LOG -> {
                    val msg = intent.getStringExtra(EngagementService.EXTRA_LOG_MESSAGE) ?: return
                    addLog(msg)
                }
                EngagementService.BROADCAST_FINISHED -> {
                    startBtn.isEnabled = true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        accountHandles = intent.getStringArrayListExtra(LoginActivity.EXTRA_ACCOUNT_HANDLES) ?: arrayListOf()
        accountDids = intent.getStringArrayListExtra(LoginActivity.EXTRA_ACCOUNT_DIDS) ?: arrayListOf()
        accountJwts = intent.getStringArrayListExtra(LoginActivity.EXTRA_ACCOUNT_JWTS) ?: arrayListOf()
        accountRefreshJwts = intent.getStringArrayListExtra(LoginActivity.EXTRA_ACCOUNT_REFRESH_JWTS) ?: arrayListOf()

        if (accountHandles.isEmpty()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        loggedInAsText = findViewById(R.id.loggedInAsText)
        logoutBtn = findViewById(R.id.logoutBtn)
        handlesInput = findViewById(R.id.handlesInput)
        excludeInput = findViewById(R.id.excludeInput)
        commentInput = findViewById(R.id.commentInput)
        delayMinInput = findViewById(R.id.delayMinInput)
        delayMaxInput = findViewById(R.id.delayMaxInput)
        allowedLangsInput = findViewById(R.id.allowedLangsInput)
        minFollowersInput = findViewById(R.id.minFollowersInput)
        maxRepliesInput = findViewById(R.id.maxRepliesInput)
        historyBtn = findViewById(R.id.historyBtn)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        logText = findViewById(R.id.logText)
        fabMain = findViewById(R.id.fabMain)
        fabWarmup = findViewById(R.id.fabWarmup)

        BotPrefs.loadHandles(this)?.let { handlesInput.setText(it) }
        BotPrefs.loadExclude(this)?.let { excludeInput.setText(it) }
        BotPrefs.loadComment(this)?.let { commentInput.setText(it) }
        BotPrefs.loadDelayMin(this)?.let { delayMinInput.setText(it) }
        BotPrefs.loadDelayMax(this)?.let { delayMaxInput.setText(it) }
        BotPrefs.loadAllowedLangs(this)?.let { allowedLangsInput.setText(it) }
        BotPrefs.loadMinFollowers(this)?.let { minFollowersInput.setText(it) }
        BotPrefs.loadMaxReplies(this)?.let { maxRepliesInput.setText(it) }

        loggedInAsText.text = "مسجل الدخول بـ ${accountHandles.size} حساب: ${accountHandles.joinToString(", ")}"

        requestNotificationPermissionIfNeeded()
        rememberAllAccounts()
        scheduleAccountHealthCheck()

        historyBtn.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        fabMain.setOnClickListener {
            startBtn.performClick()
        }

        fabWarmup.setOnClickListener {
            val warmupIntent = Intent(this, WarmupActivity::class.java).apply {
                putStringArrayListExtra(LoginActivity.EXTRA_ACCOUNT_HANDLES, accountHandles)
                putStringArrayListExtra(LoginActivity.EXTRA_ACCOUNT_DIDS, accountDids)
                putStringArrayListExtra(LoginActivity.EXTRA_ACCOUNT_JWTS, accountJwts)
                putStringArrayListExtra(LoginActivity.EXTRA_ACCOUNT_REFRESH_JWTS, accountRefreshJwts)
            }
            startActivity(warmupIntent)
        }

        startBtn.setOnClickListener {
            if (accountHandles.isEmpty()) {
                addLog("خطأ: لا توجد حسابات مسجلة، يرجى تسجيل الدخول من جديد.")
                return@setOnClickListener
            }

            val rawLines = handlesInput.text.toString()
                .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val normalizedAll = rawLines.map { normalizeHandle(it) }.filter { it.isNotEmpty() }
            val uniqueHandles = normalizedAll.distinct()
            val duplicatesRemoved = normalizedAll.size - uniqueHandles.size

            val excludedSet = excludeInput.text.toString()
                .split("\n").map { normalizeHandle(it) }.filter { it.isNotEmpty() }.toSet()
            val handles = uniqueHandles.filterNot { excludedSet.contains(it) }
            val excludedRemoved = uniqueHandles.size - handles.size

            if (duplicatesRemoved > 0) {
                addLog("تم إزالة $duplicatesRemoved اسم مكرر من قائمة الأهداف.")
            }
            if (excludedRemoved > 0) {
                addLog("تم استبعاد $excludedRemoved حساب حسب قائمة الاستثناء.")
            }

            val comments = commentInput.text.toString()
                .split("\n").map { it.trim() }.filter { it.isNotEmpty() }

            var delayMin = delayMinInput.text.toString().trim().toIntOrNull()?.coerceAtLeast(1) ?: 8
            var delayMax = delayMaxInput.text.toString().trim().toIntOrNull()?.coerceAtLeast(1) ?: 15
            if (delayMin > delayMax) {
                val tmp = delayMin
                delayMin = delayMax
                delayMax = tmp
            }

            if (handles.isEmpty()) {
                addLog("خطأ: أدخل قائمة الحسابات المستهدفة.")
                return@setOnClickListener
            }
            if (comments.isEmpty()) {
                addLog("خطأ: أدخل تعليق واحد على الأقل (كل سطر = تعليق مستقل).")
                return@setOnClickListener
            }

            val allowedLangs = allowedLangsInput.text.toString()
                .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val minFollowers = minFollowersInput.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
            val maxReplies = maxRepliesInput.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0

            BotPrefs.saveForm(
                this,
                handlesInput.text.toString(),
                excludeInput.text.toString(),
                commentInput.text.toString(),
                delayMin.toString(),
                delayMax.toString(),
                allowedLangsInput.text.toString(),
                minFollowersInput.text.toString(),
                maxRepliesInput.text.toString()
            )

            val disabledBots = BotPrefs.getDisabledAccounts(this)
            val activeIndices = accountHandles.indices.filter { accountHandles[it] !in disabledBots }
            if (activeIndices.size < accountHandles.size) {
                addLog("⏭️ تم استبعاد ${accountHandles.size - activeIndices.size} حساب بوت معطّل مسبقاً (راجع شاشة الحسابات المعطّلة).")
            }
            if (activeIndices.isEmpty()) {
                addLog("خطأ: جميع حسابات البوت معطّلة حالياً. أعد تفعيل حساب واحد على الأقل من شاشة السجل.")
                return@setOnClickListener
            }
            val activeAccountHandles = ArrayList(activeIndices.map { accountHandles[it] })
            val activeAccountDids = ArrayList(activeIndices.map { accountDids[it] })
            val activeAccountJwts = ArrayList(activeIndices.map { accountJwts[it] })
            val activeAccountRefreshJwts = ArrayList(activeIndices.map {
                accountRefreshJwts.getOrElse(it) { "" }
            })

            val serviceIntent = Intent(this, EngagementService::class.java).apply {
                action = EngagementService.ACTION_START
                putStringArrayListExtra(EngagementService.EXTRA_ACCOUNT_HANDLES, activeAccountHandles)
                putStringArrayListExtra(EngagementService.EXTRA_ACCOUNT_DIDS, activeAccountDids)
                putStringArrayListExtra(EngagementService.EXTRA_ACCOUNT_JWTS, activeAccountJwts)
                putStringArrayListExtra(EngagementService.EXTRA_ACCOUNT_REFRESH_JWTS, activeAccountRefreshJwts)
                putStringArrayListExtra(EngagementService.EXTRA_TARGET_HANDLES, ArrayList(handles))
                putStringArrayListExtra(EngagementService.EXTRA_COMMENTS, ArrayList(comments))
                putStringArrayListExtra(EngagementService.EXTRA_EXCLUDED_HANDLES, ArrayList(excludedSet))
                putStringArrayListExtra(EngagementService.EXTRA_ALLOWED_LANGS, ArrayList(allowedLangs))
                putExtra(EngagementService.EXTRA_MIN_FOLLOWERS, minFollowers)
                putExtra(EngagementService.EXTRA_MAX_REPLIES, maxReplies)
                putExtra(EngagementService.EXTRA_DELAY_MIN_SECONDS, delayMin)
                putExtra(EngagementService.EXTRA_DELAY_MAX_SECONDS, delayMax)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            startBtn.isEnabled = false
            addLog("تم إرسال المهمة إلى الخدمة الخلفية، بتوزيع عشوائي على ${activeAccountHandles.size} حساب...")
        }

        stopBtn.setOnClickListener {
            val stopIntent = Intent(this, EngagementService::class.java).apply {
                action = EngagementService.ACTION_STOP
            }
            startService(stopIntent)
            startBtn.isEnabled = true
        }

        logoutBtn.setOnClickListener {
            val stopIntent = Intent(this, EngagementService::class.java).apply {
                action = EngagementService.ACTION_STOP
            }
            startService(stopIntent)

            accountHandles = arrayListOf()
            accountDids = arrayListOf()
            accountJwts = arrayListOf()
            accountRefreshJwts = arrayListOf()

            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    /** يحفظ كل الحسابات المسجّلة دخولها في مخزن "الحسابات المعروفة" حتى يقدر الفحص الدوري بالخلفية يفحصها. */
    private fun rememberAllAccounts() {
        accountHandles.indices.forEach { i ->
            BotPrefs.rememberAccount(this, accountHandles[i], accountRefreshJwts.getOrElse(i) { "" })
        }
    }

    /** يجدول فحصاً دورياً كل 6 ساعات للتأكد من سلامة الحسابات حتى بدون تشغيل أي جلسة. */
    private fun scheduleAccountHealthCheck() {
        val request = PeriodicWorkRequestBuilder<AccountHealthCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            AccountHealthCheckWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun addLog(msg: String) {
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        logText.append("[$time] $msg\n")
    }

    private fun normalizeHandle(raw: String): String {
        var h = raw.trim()
        if (h.isEmpty()) return ""

        val profileMarker = "bsky.app/profile/"
        val markerIdx = h.indexOf(profileMarker)
        if (markerIdx != -1) {
            h = h.substring(markerIdx + profileMarker.length)
        }

        h = h.removePrefix("@")

        val slashIdx = h.indexOf("/")
        if (slashIdx != -1) {
            h = h.substring(0, slashIdx)
        }

        val queryIdx = h.indexOf("?")
        if (queryIdx != -1) {
            h = h.substring(0, queryIdx)
        }

        return h.trim().lowercase()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(EngagementService.BROADCAST_LOG)
            addAction(EngagementService.BROADCAST_FINISHED)
        }
        ContextCompat.registerReceiver(
            this, logReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(logReceiver)
    }
}
