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
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private var accountHandles: ArrayList<String> = arrayListOf()
    private var accountDids: ArrayList<String> = arrayListOf()
    private var accountJwts: ArrayList<String> = arrayListOf()

    private lateinit var loggedInAsText: TextView
    private lateinit var logoutBtn: TextView
    private lateinit var handlesInput: EditText
    private lateinit var excludeInput: EditText
    private lateinit var commentInput: EditText
    private lateinit var delayMinInput: EditText
    private lateinit var delayMaxInput: EditText
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var logText: TextView

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

        if (accountHandles.isEmpty()) {
            // لا توجد جلسة صالحة - رجّعه لشاشة الدخول
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
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        logText = findViewById(R.id.logText)

        // استرجاع آخر بيانات محفوظة (إن وجدت) حتى ما تضيع بين مرات فتح التطبيق
        BotPrefs.loadHandles(this)?.let { handlesInput.setText(it) }
        BotPrefs.loadExclude(this)?.let { excludeInput.setText(it) }
        BotPrefs.loadComment(this)?.let { commentInput.setText(it) }
        BotPrefs.loadDelayMin(this)?.let { delayMinInput.setText(it) }
        BotPrefs.loadDelayMax(this)?.let { delayMaxInput.setText(it) }

        loggedInAsText.text = "مسجل الدخول بـ ${accountHandles.size} حساب: ${accountHandles.joinToString(", ")}"

        requestNotificationPermissionIfNeeded()

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

            BotPrefs.saveForm(
                this,
                handlesInput.text.toString(),
                excludeInput.text.toString(),
                commentInput.text.toString(),
                delayMin.toString(),
                delayMax.toString()
            )

            val serviceIntent = Intent(this, EngagementService::class.java).apply {
                action = EngagementService.ACTION_START
                putStringArrayListExtra(EngagementService.EXTRA_ACCOUNT_HANDLES, accountHandles)
                putStringArrayListExtra(EngagementService.EXTRA_ACCOUNT_DIDS, accountDids)
                putStringArrayListExtra(EngagementService.EXTRA_ACCOUNT_JWTS, accountJwts)
                putStringArrayListExtra(EngagementService.EXTRA_TARGET_HANDLES, ArrayList(handles))
                putStringArrayListExtra(EngagementService.EXTRA_COMMENTS, ArrayList(comments))
                putExtra(EngagementService.EXTRA_DELAY_MIN_SECONDS, delayMin)
                putExtra(EngagementService.EXTRA_DELAY_MAX_SECONDS, delayMax)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            startBtn.isEnabled = false
            addLog("تم إرسال المهمة إلى الخدمة الخلفية، بتوزيع عشوائي على ${accountHandles.size} حساب...")
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

            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun addLog(msg: String) {
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        logText.append("[$time] $msg\n")
    }

    /**
     * يحوّل أي صيغة مكتوبة (رابط كامل، بـ @، بأحرف كبيرة...) إلى هاندل نظيف موحّد،
     * حتى تقدر المقارنة والفلترة (تكرار/استبعاد) تشتغل بشكل صحيح.
     * أمثلة تدخل كلها لنفس النتيجة "micheeel.bsky.social":
     *   - "https://bsky.app/profile/micheeel.bsky.social"
     *   - "https://bsky.app/profile/micheeel.bsky.social/post/xyz"
     *   - "@Micheeel.bsky.social"
     *   - "  micheeel.bsky.social  "
     */
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
