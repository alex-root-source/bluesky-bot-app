package com.bluesky.bot

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * شاشة مراجعة: الحسابات المعطّلة بشكل دائم (مع زر إعادة تفعيل لكل حساب)
 * + سجل مختصر لآخر الجلسات. مبنية برمجياً بنفس هوية التطبيق البصرية (بطاقات Material3).
 */
class HistoryActivity : AppCompatActivity() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private lateinit var disabledContainer: LinearLayout

    private fun c(resId: Int) = ContextCompat.getColor(this, resId)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20))
            setBackgroundColor(c(R.color.bg_base))
        }

        val title = TextView(this).apply {
            text = "📋 الحسابات المعطّلة وسجل الجلسات"
            setTextColor(c(R.color.text_primary))
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(18))
        }
        root.addView(title)

        // ===== بطاقة: الحسابات المعطّلة =====
        val disabledCard = card()
        val disabledCardInner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        disabledCard.addView(disabledCardInner)

        disabledCardInner.addView(sectionTitle("🚫 حسابات معطّلة بشكل دائم"))

        disabledContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        disabledCardInner.addView(disabledContainer)
        renderDisabledAccounts()

        val clearLogBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "مسح سجل الاستبعاد القديم"
            textSize = 12f
            isAllCaps = false
            setTextColor(c(R.color.text_secondary))
            strokeColor = ContextCompat.getColorStateList(this@HistoryActivity, R.color.outline)
            setOnClickListener {
                BotPrefs.clearExclusionLog(this@HistoryActivity)
                renderDisabledAccounts()
            }
        }
        val clearLogBtnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
        disabledCardInner.addView(clearLogBtn, clearLogBtnParams)

        root.addView(disabledCard, cardMargins())

        // ===== بطاقة: سجل الجلسات =====
        val historyCard = card()
        val historyCardInner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historyCard.addView(historyCardInner)

        historyCardInner.addView(sectionTitle("📊 آخر الجلسات"))

        val history = BotPrefs.getSessionHistory(this)
        if (history.isEmpty()) {
            historyCardInner.addView(emptyText("لا توجد جلسات مسجّلة بعد."))
        } else {
            history.forEach { entry -> historyCardInner.addView(sessionRow(entry)) }
        }

        val clearHistoryBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "مسح سجل الجلسات"
            textSize = 12f
            isAllCaps = false
            setTextColor(c(R.color.text_secondary))
            strokeColor = ContextCompat.getColorStateList(this@HistoryActivity, R.color.outline)
            setOnClickListener {
                BotPrefs.clearSessionHistory(this@HistoryActivity)
                recreate()
            }
        }
        val clearHistoryBtnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
        historyCardInner.addView(clearHistoryBtn, clearHistoryBtnParams)

        root.addView(historyCard, cardMargins())

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun renderDisabledAccounts() {
        disabledContainer.removeAllViews()
        val log = BotPrefs.getExclusionLog(this)
        val activeEntries = log.filter { it.active }

        if (activeEntries.isEmpty()) {
            disabledContainer.addView(emptyText("لا توجد حسابات معطّلة حالياً. 👍"))
        } else {
            activeEntries.forEach { entry -> disabledContainer.addView(disabledAccountRow(entry)) }
        }

        val inactiveEntries = log.filter { !it.active }
        if (inactiveEntries.isNotEmpty()) {
            val hint = TextView(this).apply {
                text = "سجل قديم (تمت إعادة تفعيلها):"
                setTextColor(c(R.color.text_muted))
                textSize = 12f
                setPadding(0, dp(16), 0, dp(4))
            }
            disabledContainer.addView(hint)
            inactiveEntries.forEach { entry -> disabledContainer.addView(historyLine(entry)) }
        }
    }

    private fun disabledAccountRow(entry: BotPrefs.ExclusionLogEntry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        textCol.addView(TextView(this).apply {
            text = "@${entry.handle}"
            setTextColor(c(R.color.text_primary))
            textSize = 15f
        })
        textCol.addView(TextView(this).apply {
            text = entry.reason
            setTextColor(c(R.color.brand_error))
            textSize = 12f
        })
        textCol.addView(TextView(this).apply {
            text = dateFormat.format(Date(entry.timestampMillis))
            setTextColor(c(R.color.text_muted))
            textSize = 11f
        })

        val reactivateBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = "إعادة تفعيل"
            textSize = 12f
            isAllCaps = false
            cornerRadius = dp(12)
            backgroundTintList = ContextCompat.getColorStateList(this@HistoryActivity, R.color.brand_secondary)
            setTextColor(c(R.color.brand_on_secondary))
            setOnClickListener {
                BotPrefs.reactivateAccount(this@HistoryActivity, entry.handle)
                renderDisabledAccounts()
            }
        }

        row.addView(textCol)
        row.addView(reactivateBtn)
        return row
    }

    private fun historyLine(entry: BotPrefs.ExclusionLogEntry): View {
        return TextView(this).apply {
            text = "@${entry.handle} - ${entry.reason} (${dateFormat.format(Date(entry.timestampMillis))})"
            setTextColor(c(R.color.text_muted))
            textSize = 11f
            setPadding(0, dp(4), 0, dp(4))
        }
    }

    private fun sessionRow(entry: BotPrefs.SessionHistoryEntry): View {
        val elapsedMin = entry.elapsedMs / 60000
        val elapsedSec = (entry.elapsedMs / 1000) % 60
        return TextView(this).apply {
            text = "${dateFormat.format(Date(entry.timestampMillis))} — " +
                "أهداف ${entry.totalTargets} | نجح ${entry.success} | فشل ${entry.failed} | " +
                "مكرر ${entry.skippedDuplicate} | بدون منشور ${entry.skippedNoPost} | " +
                "مستبعد ${entry.excludedAccounts} | ${elapsedMin}د ${elapsedSec}ث"
            setTextColor(c(R.color.text_primary))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(6))
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
            setPadding(0, dp(6), 0, dp(6))
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
