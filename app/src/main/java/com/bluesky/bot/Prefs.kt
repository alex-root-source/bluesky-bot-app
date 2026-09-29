package com.bluesky.bot

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * تخزين محلي (SharedPreferences مشفّرة عبر Jetpack Security) لعدة أمور:
 * 1. حفظ محتوى نموذج الأداة (الأهداف، الاستثناء، التعليقات، التأخير، اللغات المسموحة).
 * 2. سجل دائم بكل الحسابات اللي تم التعليق عليها من قبل (منع التكرار بين الجلسات).
 * 3. قائمة الحسابات "المعطّلة" بشكل دائم (حسابات استُبعدت أثناء التشغيل بسبب تقييد/شادو-بان
 *    أو جلسة منتهية) + سجل تفصيلي بالسبب والوقت، مع إمكانية "إعادة تفعيل" الحساب يدوياً.
 * 4. سجل مختصر لآخر الجلسات (تاريخ/عدد النجاح/الفشل...) للمراجعة لاحقاً.
 *
 * يُستخدم EncryptedSharedPreferences لتشفير هذه البيانات على القرص (AES-256)،
 * مع رجوع تلقائي وآمن إلى SharedPreferences عادية إن فشل إنشاء التخزين المشفّر
 * (مثلاً على أجهزة قديمة جداً أو Keystore تالف)، حتى لا يتعطّل التطبيق بالكامل.
 */
object BotPrefs {
    private const val PREFS_NAME = "bluesky_bot_prefs_secure"

    private const val KEY_HANDLES = "handles_input"
    private const val KEY_EXCLUDE = "exclude_input"
    private const val KEY_COMMENT = "comment_input"
    private const val KEY_DELAY_MIN = "delay_min"
    private const val KEY_DELAY_MAX = "delay_max"
    private const val KEY_ALLOWED_LANGS = "allowed_langs"
    private const val KEY_COMMENTED_TARGETS = "commented_targets"
    private const val KEY_DISABLED_ACCOUNTS = "disabled_accounts_set"
    private const val KEY_EXCLUSION_LOG = "exclusion_log_json"
    private const val KEY_SESSION_HISTORY = "session_history_json"

    private const val MAX_EXCLUSION_LOG_ENTRIES = 100
    private const val MAX_SESSION_HISTORY_ENTRIES = 30

    data class ExclusionLogEntry(
        val handle: String,
        val reason: String,
        val timestampMillis: Long,
        val active: Boolean
    )

    data class SessionHistoryEntry(
        val timestampMillis: Long,
        val totalTargets: Int,
        val success: Int,
        val failed: Int,
        val skippedDuplicate: Int,
        val skippedNoPost: Int,
        val excludedAccounts: Int,
        val elapsedMs: Long
    )

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val created = try {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // رجوع آمن لتخزين عادي غير مشفّر بدل تعطّل التطبيق بالكامل
                context.applicationContext.getSharedPreferences(
                    PREFS_NAME + "_fallback", Context.MODE_PRIVATE
                )
            }
            cached = created
            return created
        }
    }

    // ---------------------------------------------------------------------
    // نموذج الأداة (يشمل الآن اللغات المسموحة كحقل اختياري)
    // ---------------------------------------------------------------------

    fun saveForm(
        context: Context,
        handles: String,
        exclude: String,
        comment: String,
        delayMin: String,
        delayMax: String,
        allowedLangs: String = ""
    ) {
        prefs(context).edit()
            .putString(KEY_HANDLES, handles)
            .putString(KEY_EXCLUDE, exclude)
            .putString(KEY_COMMENT, comment)
            .putString(KEY_DELAY_MIN, delayMin)
            .putString(KEY_DELAY_MAX, delayMax)
            .putString(KEY_ALLOWED_LANGS, allowedLangs)
            .apply()
    }

    fun loadHandles(context: Context): String? = prefs(context).getString(KEY_HANDLES, null)
    fun loadExclude(context: Context): String? = prefs(context).getString(KEY_EXCLUDE, null)
    fun loadComment(context: Context): String? = prefs(context).getString(KEY_COMMENT, null)
    fun loadDelayMin(context: Context): String? = prefs(context).getString(KEY_DELAY_MIN, null)
    fun loadDelayMax(context: Context): String? = prefs(context).getString(KEY_DELAY_MAX, null)
    fun loadAllowedLangs(context: Context): String? = prefs(context).getString(KEY_ALLOWED_LANGS, null)

    // ---------------------------------------------------------------------
    // سجل "تم التعليق عليه من قبل" (منع التكرار بين الجلسات)
    // ---------------------------------------------------------------------

    fun getCommentedTargets(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_COMMENTED_TARGETS, emptySet()) ?: emptySet()

    fun addCommentedTarget(context: Context, target: String) {
        val p = prefs(context)
        val current = HashSet(p.getStringSet(KEY_COMMENTED_TARGETS, emptySet()) ?: emptySet())
        current.add(target)
        p.edit().putStringSet(KEY_COMMENTED_TARGETS, current).apply()
    }

    // ---------------------------------------------------------------------
    // حسابات معطّلة بشكل دائم + سجل الاستبعاد
    // ---------------------------------------------------------------------

    /** يرجع هاندلات الحسابات المعطّلة حالياً (لن تُستخدم في أي جلسة جديدة حتى إعادة تفعيلها). */
    fun getDisabledAccounts(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DISABLED_ACCOUNTS, emptySet()) ?: emptySet()

    /** يعطّل حساباً بشكل دائم (عبر الجلسات القادمة) ويسجّل السبب والوقت. */
    fun disableAccount(context: Context, handle: String, reason: String) {
        val p = prefs(context)
        val current = HashSet(p.getStringSet(KEY_DISABLED_ACCOUNTS, emptySet()) ?: emptySet())
        current.add(handle)

        val log = readExclusionLog(p).toMutableList()
        log.add(0, ExclusionLogEntry(handle, reason, System.currentTimeMillis(), active = true))
        val trimmed = log.take(MAX_EXCLUSION_LOG_ENTRIES)

        p.edit()
            .putStringSet(KEY_DISABLED_ACCOUNTS, current)
            .putString(KEY_EXCLUSION_LOG, writeExclusionLog(trimmed))
            .apply()
    }

    /** يعيد تفعيل حساب معطّل يدوياً (يشارك في الجلسات القادمة من جديد). */
    fun reactivateAccount(context: Context, handle: String) {
        val p = prefs(context)
        val current = HashSet(p.getStringSet(KEY_DISABLED_ACCOUNTS, emptySet()) ?: emptySet())
        current.remove(handle)

        val log = readExclusionLog(p).map {
            if (it.handle == handle && it.active) it.copy(active = false) else it
        }

        p.edit()
            .putStringSet(KEY_DISABLED_ACCOUNTS, current)
            .putString(KEY_EXCLUSION_LOG, writeExclusionLog(log))
            .apply()
    }

    /** سجل كل الاستبعادات (الأحدث أولاً)، لعرضها في شاشة المراجعة. */
    fun getExclusionLog(context: Context): List<ExclusionLogEntry> = readExclusionLog(prefs(context))

    /** يمسح سجل الاستبعاد بالكامل (لا يمسّ قائمة الحسابات المعطّلة نفسها). */
    fun clearExclusionLog(context: Context) {
        prefs(context).edit().remove(KEY_EXCLUSION_LOG).apply()
    }

    private fun readExclusionLog(p: SharedPreferences): List<ExclusionLogEntry> {
        val raw = p.getString(KEY_EXCLUSION_LOG, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ExclusionLogEntry(
                    handle = o.getString("handle"),
                    reason = o.getString("reason"),
                    timestampMillis = o.getLong("ts"),
                    active = o.optBoolean("active", true)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeExclusionLog(entries: List<ExclusionLogEntry>): String {
        val arr = JSONArray()
        entries.forEach {
            arr.put(JSONObject().apply {
                put("handle", it.handle)
                put("reason", it.reason)
                put("ts", it.timestampMillis)
                put("active", it.active)
            })
        }
        return arr.toString()
    }

    // ---------------------------------------------------------------------
    // سجل مختصر للجلسات السابقة
    // ---------------------------------------------------------------------

    fun addSessionHistoryEntry(context: Context, entry: SessionHistoryEntry) {
        val p = prefs(context)
        val current = readSessionHistory(p).toMutableList()
        current.add(0, entry)
        val trimmed = current.take(MAX_SESSION_HISTORY_ENTRIES)
        p.edit().putString(KEY_SESSION_HISTORY, writeSessionHistory(trimmed)).apply()
    }

    fun getSessionHistory(context: Context): List<SessionHistoryEntry> = readSessionHistory(prefs(context))

    fun clearSessionHistory(context: Context) {
        prefs(context).edit().remove(KEY_SESSION_HISTORY).apply()
    }

    private fun readSessionHistory(p: SharedPreferences): List<SessionHistoryEntry> {
        val raw = p.getString(KEY_SESSION_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SessionHistoryEntry(
                    timestampMillis = o.getLong("ts"),
                    totalTargets = o.getInt("total"),
                    success = o.getInt("success"),
                    failed = o.getInt("failed"),
                    skippedDuplicate = o.getInt("skipDup"),
                    skippedNoPost = o.getInt("skipNoPost"),
                    excludedAccounts = o.getInt("excluded"),
                    elapsedMs = o.getLong("elapsedMs")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeSessionHistory(entries: List<SessionHistoryEntry>): String {
        val arr = JSONArray()
        entries.forEach {
            arr.put(JSONObject().apply {
                put("ts", it.timestampMillis)
                put("total", it.totalTargets)
                put("success", it.success)
                put("failed", it.failed)
                put("skipDup", it.skippedDuplicate)
                put("skipNoPost", it.skippedNoPost)
                put("excluded", it.excludedAccounts)
                put("elapsedMs", it.elapsedMs)
            })
        }
        return arr.toString()
    }
}
