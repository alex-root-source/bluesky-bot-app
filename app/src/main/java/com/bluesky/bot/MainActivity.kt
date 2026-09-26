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

    private var accessJwt: String? = null
    private var userDid: String? = null
    private var handle: String? = null

    private lateinit var loggedInAsText: TextView
    private lateinit var logoutBtn: TextView
    private lateinit var handlesInput: EditText
    private lateinit var commentInput: EditText
    private lateinit var delayInput: EditText
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

        accessJwt = intent.getStringExtra(LoginActivity.EXTRA_ACCESS_JWT)
        userDid = intent.getStringExtra(LoginActivity.EXTRA_USER_DID)
        handle = intent.getStringExtra(LoginActivity.EXTRA_HANDLE)

        if (accessJwt == null || userDid == null) {
            // لا توجد جلسة صالحة - رجّعه لشاشة الدخول
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        loggedInAsText = findViewById(R.id.loggedInAsText)
        logoutBtn = findViewById(R.id.logoutBtn)
        handlesInput = findViewById(R.id.handlesInput)
        commentInput = findViewById(R.id.commentInput)
        delayInput = findViewById(R.id.delayInput)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        logText = findViewById(R.id.logText)

        loggedInAsText.text = "مسجل الدخول: ${handle ?: ""}"

        requestNotificationPermissionIfNeeded()

        startBtn.setOnClickListener {
            val jwt = accessJwt
            val did = userDid
            if (jwt == null || did == null) {
                addLog("خطأ: انتهت صلاحية الجلسة، يرجى تسجيل الدخول من جديد.")
                return@setOnClickListener
            }

            val handles = handlesInput.text.toString()
                .split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val comments = commentInput.text.toString()
                .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val delaySeconds = delayInput.text.toString().trim().toIntOrNull()?.coerceAtLeast(1) ?: 10

            if (handles.isEmpty()) {
                addLog("خطأ: أدخل قائمة الحسابات المستهدفة.")
                return@setOnClickListener
            }
            if (comments.isEmpty()) {
                addLog("خطأ: أدخل تعليق واحد على الأقل (كل سطر = تعليق مستقل).")
                return@setOnClickListener
            }

            val serviceIntent = Intent(this, EngagementService::class.java).apply {
                action = EngagementService.ACTION_START
                putExtra(EngagementService.EXTRA_ACCESS_JWT, jwt)
                putExtra(EngagementService.EXTRA_USER_DID, did)
                putStringArrayListExtra(EngagementService.EXTRA_HANDLES, ArrayList(handles))
                putStringArrayListExtra(EngagementService.EXTRA_COMMENTS, ArrayList(comments))
                putExtra(EngagementService.EXTRA_DELAY_SECONDS, delaySeconds)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            startBtn.isEnabled = false
            addLog("تم إرسال المهمة إلى الخدمة الخلفية (Foreground Service)...")
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

            accessJwt = null
            userDid = null

            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun addLog(msg: String) {
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        logText.append("[$time] $msg\n")
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
