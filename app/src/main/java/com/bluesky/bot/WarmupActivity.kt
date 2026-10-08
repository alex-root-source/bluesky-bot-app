package com.bluesky.bot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * شاشة وضع الإحماء: اختيار أي الحسابات المسجّلة تشارك، أهداف وتعليق خاصان به،
 * مستقل تماماً عن الوضع الرئيسي (خدمة منفصلة WarmupEngagementService).
 */
class WarmupActivity : AppCompatActivity() {

    private var accountHandles: ArrayList<String> = arrayListOf()
    private var accountDids: ArrayList<String> = arrayListOf()
    private var accountJwts: ArrayList<String> = arrayListOf()
    private var accountRefreshJwts: ArrayList<String> = arrayListOf()
    private var accountPdsUrls: ArrayList<String> = arrayListOf()

    private val checkboxes = mutableMapOf<String, CheckBox>()

    private lateinit var targetsInput: TextInputEditText
    private lateinit var commentInput: TextInputEditText
    private lateinit var statusContainer: LinearLayout
    private lateinit var startBtn: MaterialButton
    private lateinit var stopBtn: MaterialButton
    private lateinit var logText: TextView

    private fun c(resId: Int) = ContextCompat.getColor(this, resId)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WarmupEngagementService.BROADCAST_LOG -> {
                    val msg = intent.getStringExtra(WarmupEngagementService.EXTRA_LOG_MESSAGE) ?: return
                    val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    logText.append("[$time] $msg\n")
                }
                WarmupEngagementService.BROADCAST_FINISHED -> {
                    startBtn.isEnabled = true
                    renderAccountStatus()
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
        accountPdsUrls = intent.getStringArrayListExtra(LoginActivity.EXTRA_ACCOUNT_PDS_URLS) ?: arrayListOf()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20))
            setBackgroundColor(c(R.color.bg_base))
        }

        root.addView(TextView(this).apply {
            text = "🔥 وضع الإحماء"
            setTextColor(c(R.color.text_primary))
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "يبدأ كل حساب بعدد قليل من التعليقات يومياً ويزيده تدريجياً، بدل نفس معدّل حساب قديم من أول يوم."
            setTextColor(c(R.color.text_secondary))
            textSize = 12.5f
            setPadding(0, 0, 0, dp(16))
        })

        // ===== بطاقة: اختيار الحسابات =====
        val accountsCard = card()
        val accountsInner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        accountsCard.addView(accountsInner)
        accountsInner.addView(sectionTitle("👤 حسابات مشاركة في الإحماء"))

        if (accountHandles.isEmpty()) {
            accountsInner.addView(emptyText("لا توجد حسابات مسجّلة."))
        } else {
            accountHandles.forEach { handle ->
                val row = CheckBox(this).apply {
                    text = "@$handle"
                    setTextColor(c(R.color.text_primary))
                    isChecked = false
                }
                checkboxes[handle] = row
                accountsInner.addView(row)
            }
        }
        root.addView(accountsCard, cardMargins())

        // ===== بطاقة: الأهداف والتعليق =====
        val formCard = card()
        val formInner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        formCard.addView(formInner)
        formInner.addView(sectionTitle("🎯 الأهداف والتعليق"))

        val targetsLayout = TextInputLayout(this).apply {
            hint = "الأهداف (حساب في كل سطر)"
        }
        targetsInput = TextInputEditText(this).apply {
            setLines(4)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(c(R.color.text_primary))
        }
        targetsLayout.addView(targetsInput)
        formInner.addView(targetsLayout)

        val commentLayout = TextInputLayout(this).apply {
            hint = "نص تعليق الإحماء"
            (layoutParams as? LinearLayout.LayoutParams)
        }
        val commentParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
        commentInput = TextInputEditText(this).apply {
            setTextColor(c(R.color.text_primary))
        }
        commentLayout.addView(commentInput)
        formInner.addView(commentLayout, commentParams)

        BotPrefs.loadWarmupTargets(this)?.let { targetsInput.setText(it) }
        BotPrefs.loadWarmupComment(this)?.let { commentInput.setText(it) }
        BotPrefs.loadWarmupAccounts(this)?.split(",")?.map { it.trim() }?.forEach { h ->
            checkboxes[h]?.isChecked = true
        }

        root.addView(formCard, cardMargins())

        // ===== بطاقة: حالة كل حساب (اليوم/السقف) =====
        val statusCard = card()
        statusContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusCard.addView(statusContainer)
        root.addView(statusCard, cardMargins())
        renderAccountStatus()

        // ===== أزرار =====
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        startBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = "🔥 بدء الإحماء"
            isAllCaps = false
            backgroundTintList = ContextCompat.getColorStateList(this@WarmupActivity, R.color.brand_secondary)
            setTextColor(c(R.color.brand_on_secondary))
            cornerRadius = dp(14)
            setOnClickListener { startWarmup() }
        }
        stopBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "⏹ إيقاف"
            isAllCaps = false
            setTextColor(c(R.color.brand_error))
            strokeColor = ContextCompat.getColorStateList(this@WarmupActivity, R.color.brand_error)
            cornerRadius = dp(14)
            setOnClickListener {
                val stopIntent = Intent(this@WarmupActivity, WarmupEngagementService::class.java).apply {
                    action = WarmupEngagementService.ACTION_STOP
                }
                startService(stopIntent)
                startBtn.isEnabled = true
            }
        }
        val startParams = LinearLayout.LayoutParams(0, dp(52), 1f)
        val stopParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(10) }
        buttonsRow.addView(startBtn, startParams)
        buttonsRow.addView(stopBtn, stopParams)
        root.addView(buttonsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16); topMargin = dp(4) })

        // ===== سجل =====
        val logCard = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = c(R.color.outline_variant)
            setCardBackgroundColor(c(R.color.log_bg))
            setContentPadding(dp(12), dp(12), dp(12), dp(12))
        }
        logText = TextView(this).apply {
            setTextColor(c(R.color.log_text))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11.5f
        }
        logCard.addView(logText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(180)
        ))
        root.addView(logCard)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun startWarmup() {
        val selected = checkboxes.filterValues { it.isChecked }.keys.toList()
        if (selected.isEmpty()) {
            addStatusLine("⚠️ اختر حساباً واحداً على الأقل.")
            return
        }
        val targets = targetsInput.text.toString()
            .split("\n").map { it.trim().removePrefix("@") }.filter { it.isNotEmpty() }.distinct()
        val comment = commentInput.text.toString().trim()

        if (targets.isEmpty()) {
            addStatusLine("⚠️ أدخل هدفاً واحداً على الأقل.")
            return
        }
        if (comment.isEmpty()) {
            addStatusLine("⚠️ أدخل نص التعليق.")
            return
        }

        BotPrefs.saveWarmupForm(this, selected.joinToString(","), targetsInput.text.toString(), comment)

        val indices = accountHandles.indices.filter { accountHandles[it] in selected }
        val serviceIntent = Intent(this, WarmupEngagementService::class.java).apply {
            action = WarmupEngagementService.ACTION_START
            putStringArrayListExtra(WarmupEngagementService.EXTRA_ACCOUNT_HANDLES, ArrayList(indices.map { accountHandles[it] }))
            putStringArrayListExtra(WarmupEngagementService.EXTRA_ACCOUNT_DIDS, ArrayList(indices.map { accountDids[it] }))
            putStringArrayListExtra(WarmupEngagementService.EXTRA_ACCOUNT_JWTS, ArrayList(indices.map { accountJwts[it] }))
            putStringArrayListExtra(
                WarmupEngagementService.EXTRA_ACCOUNT_REFRESH_JWTS,
                ArrayList(indices.map { accountRefreshJwts.getOrElse(it) { "" } })
            )
            putStringArrayListExtra(
                WarmupEngagementService.EXTRA_ACCOUNT_PDS_URLS,
                ArrayList(indices.map { accountPdsUrls.getOrElse(it) { BskyApi.DEFAULT_PDS } })
            )
            putStringArrayListExtra(WarmupEngagementService.EXTRA_TARGET_HANDLES, ArrayList(targets))
            putStringArrayListExtra(WarmupEngagementService.EXTRA_COMMENTS, arrayListOf(comment))
            val excluded = (BotPrefs.loadExclude(this@WarmupActivity) ?: "")
                .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            putStringArrayListExtra(WarmupEngagementService.EXTRA_EXCLUDED_HANDLES, ArrayList(excluded))
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        startBtn.isEnabled = false
        addStatusLine("🔥 تم إرسال جلسة إحماء بـ ${indices.size} حساب.")
    }

    private fun addStatusLine(msg: String) {
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        logText.append("[$time] $msg\n")
    }

    private fun renderAccountStatus() {
        statusContainer.removeAllViews()
        statusContainer.addView(sectionTitle("📅 حالة الإحماء الحالية"))
        if (accountHandles.isEmpty()) {
            statusContainer.addView(emptyText("لا توجد حسابات."))
            return
        }
        accountHandles.forEach { handle ->
            val day = BotPrefs.warmupDayNumber(this, handle)
            val cap = WarmupEngagementService.dailyCapForDay(day)
            val used = BotPrefs.getWarmupState(this, handle).todayCount
            statusContainer.addView(TextView(this).apply {
                text = "@$handle — يوم #$day، اليوم: $used / $cap"
                setTextColor(c(R.color.text_secondary))
                textSize = 12.5f
                setPadding(0, dp(3), 0, dp(3))
            })
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(c(R.color.text_primary))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        }
    }

    private fun emptyText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(c(R.color.text_secondary))
            textSize = 13f
        }
    }

    private fun card(): MaterialCardView {
        return MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = c(R.color.outline_variant)
            setCardBackgroundColor(c(R.color.bg_surface))
            setContentPadding(dp(16), dp(16), dp(16), dp(16))
        }
    }

    private fun cardMargins(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(WarmupEngagementService.BROADCAST_LOG)
            addAction(WarmupEngagementService.BROADCAST_FINISHED)
        }
        ContextCompat.registerReceiver(this, logReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(logReceiver)
    }
}
