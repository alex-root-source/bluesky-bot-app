package com.bluesky.bot

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * شاشة مراجعة: الحسابات المعطّلة بشكل دائم (مع زر إعادة تفعيل لكل حساب)
 * + سجل مختصر لآخر الجلسات. مبنية برمجياً بدون ملف layout منفصل لتقليل نقاط الخطأ.
 */
class HistoryActivity : AppCompatActivity() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private lateinit var disabledContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val title = TextView(this).apply {
            text = "الحسابات المعطّلة وسجل الجلسات"
            setTextColor(Color.parseColor("#0085FF"))
            textSize = 20f
            setPadding(0, 0, 0, 24)
        }
        root.addView(title)

        val disabledTitle = sectionTitle("🚫 حسابات معطّلة بشكل دائم")
        root.addView(disabledTitle)

        disabledContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(disabledContainer)
        renderDisabledAccounts()

        val clearLogBtn = Button(this).apply {
            text = "مسح سجل الاستبعاد القديم (لا يمسّ الحسابات النشطة المعطّلة)"
            setOnClickListener {
                BotPrefs.clearExclusionLog(this@HistoryActivity)
                renderDisabledAccounts()
            }
        }
        root.addView(clearLogBtn)

        val historyTitle = sectionTitle("📊 آخر الجلسات")
        historyTitle.setPadding(0, 32, 0, 8)
        root.addView(historyTitle)

        val history = BotPrefs.getSessionHistory(this)
        if (history.isEmpty()) {
            root.addView(emptyText("لا توجد جلسات مسجّلة بعد."))
        } else {
            history.forEach { entry ->
                root.addView(sessionRow(entry))
            }
        }

        val clearHistoryBtn = Button(this).apply {
            text = "مسح سجل الجلسات"
            setOnClickListener {
                BotPrefs.clearSessionHistory(this@HistoryActivity)
                recreate()
            }
        }
        root.addView(clearHistoryBtn)

        val scroll = ScrollView(this).apply {
            addView(root)
        }
        setContentView(scroll)
    }

    private fun renderDisabledAccounts() {
        disabledContainer.removeAllViews()
        val log = BotPrefs.getExclusionLog(this)
        val activeEntries = log.filter { it.active }

        if (activeEntries.isEmpty()) {
            disabledContainer.addView(emptyText("لا توجد حسابات معطّلة حالياً. 👍"))
        } else {
            activeEntries.forEach { entry ->
                disabledContainer.addView(disabledAccountRow(entry))
            }
        }

        val inactiveEntries = log.filter { !it.active }
        if (inactiveEntries.isNotEmpty()) {
            val hint = TextView(this).apply {
                text = "سجل قديم (تمت إعادة تفعيلها):"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                setPadding(0, 16, 0, 4)
            }
            disabledContainer.addView(hint)
            inactiveEntries.forEach { entry ->
                disabledContainer.addView(historyLine(entry))
            }
        }
    }

    private fun disabledAccountRow(entry: BotPrefs.ExclusionLogEntry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 12)
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val handleText = TextView(this).apply {
            text = "@${entry.handle}"
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        val reasonText = TextView(this).apply {
            text = entry.reason
            setTextColor(Color.parseColor("#F87171"))
            textSize = 12f
        }
        val timeText = TextView(this).apply {
            text = dateFormat.format(Date(entry.timestampMillis))
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
        }
        textCol.addView(handleText)
        textCol.addView(reasonText)
        textCol.addView(timeText)

        val reactivateBtn = Button(this).apply {
            text = "إعادة تفعيل"
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
            setTextColor(Color.parseColor("#64748B"))
            textSize = 11f
            setPadding(0, 4, 0, 4)
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
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 12f
            setPadding(0, 6, 0, 6)
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 16f
        }
    }

    private fun emptyText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
    }
}
