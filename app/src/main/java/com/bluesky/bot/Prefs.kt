package com.bluesky.bot

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * تخزين محلي (SharedPreferences مشفّرة عبر Jetpack Security) لكل شيء يحتاج التطبيق
 * تذكّره بين الجلسات وإعادة فتح التطبيق:
 * 1. نموذج الوضع الرئيسي (الأهداف، الاستثناء، التعليقات، التأخير، اللغات، فلاتر الجودة).
 * 2. نموذج وضع الإحماء (الحسابات المختارة، الأهداف، التعليق).
 * 3. سجل دائم بكل الحسابات المستهدفة اللي تم التعليق عليها من قبل (منع التكرار/الازدحام،
 *    مشترك بين الوضعين حتى لا يعلّق وضع الإحماء على من عُلّق عليه من الوضع الرئيسي والعكس).
 * 4. قائمة الحسابات "المعطّلة" بشكل دائم + سجل تفصيلي بالسبب والوقت + إعادة تفعيل يدوية.
 * 5. سجل مختصر لآخر الجلسات.
 * 6. حسابات "معروفة" (هاندل + refreshJwt) يستخدمها الفحص الدوري في الخلفية للتأكد من
 *    سلامة الحسابات حتى بدون تشغيل أي جلسة.
 * 7. حالة وضع الإحماء لكل حساب (تاريخ البدء + عدد تعليقات اليوم) لتطبيق سقف تدريجي.
 */
object BotPrefs {
    private const val PREFS_NAME = "bluesky_bot_prefs_secure"

    private const val KEY_HANDLES = "handles_input"
    private const val KEY_EXCLUDE = "exclude_input"
    private const val KEY_COMMENT = "comment_input"
    private const val KEY_DELAY_MIN = "delay_min"
    private const val KEY_DELAY_MAX = "delay_max"
    private const val KEY_ALLOWED_LANGS = "allowed_langs"
    private const val KEY_MIN_FOLLOWERS = "min_followers"
    private const val KEY_MAX_REPLIES = "max_replies"

    private const val KEY_WARMUP_ACCOUNTS = "warmup_accounts_csv"
    private const val KEY_WARMUP_TARGETS = "warmup_targets"
    private const val KEY_WARMUP_COMMENT = "warmup_comment"

    private const val KEY_COMMENTED_TARGETS = "commented_targets"
    private const val KEY_DISABLED_ACCOUNTS = "disabled_accounts_set"
    private const val KEY_EXCLUSION_LOG = "exclusion_log_json"
    private const val KEY_SESSION_HISTORY = "session_history_json"
    private const val KEY_KNOWN_ACCOUNTS = "known_accounts_json"
    private const val KEY_WARMUP_STATE = "warmup_state_json"

    private const val MAX_EXCLUSION_LOG_ENTRIES = 100
    private const val MAX_SESSION_HISTORY_ENTRIES = 30

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private fun today(): String = dayFormat.format(Date())

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

    data class WarmupState(
        val startDate: String,
        val todayDate: String,
        val todayCount: Int
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
                context.applicationContext.getSharedPreferences(
                    PREFS_NAME + "_fallback", Context.MODE_PRIVATE
                )
            }
            cached = created
            return created
        }
    }

    // ---------------------------------------------------------------------
    // نموذج الوضع الرئيسي
    // ---------------------------------------------------------------------

    fun saveForm(
        context: Context,
        handles: String,
        exclude: String,
        comment: String,
        delayMin: String,
        delayMax: String,
        allowedLangs: String = "",
        minFollowers: String = "",
        maxReplies: String = ""
    ) {
        prefs(context).edit()
            .putString(KEY_HANDLES, handles)
            .putString(KEY_EXCLUDE, exclude)
            .putString(KEY_COMMENT, comment)
            .putString(KEY_DELAY_MIN, delayMin)
            .putString(KEY_DELAY_MAX, delayMax)
            .putString(KEY_ALLOWED_LANGS, allowedLangs)
            .putString(KEY_MIN_FOLLOWERS, minFollowers)
            .putString(KEY_MAX_REPLIES, maxReplies)
            .apply()
    }

    fun loadHandles(context: Context): String? = prefs(context).getString(KEY_HANDLES, null)
    fun loadExclude(context: Context): String? = prefs(context).getString(KEY_EXCLUDE, null)
    fun loadComment(context: Context): String? = prefs(context).getString(KEY_COMMENT, null)
    fun loadDelayMin(context: Context): String? = prefs(context).getString(KEY_DELAY_MIN, null)
    fun loadDelayMax(context: Context): String? = prefs(context).getString(KEY_DELAY_MAX, null)
    fun loadAllowedLangs(context: Context): String? = prefs(context).getString(KEY_ALLOWED_LANGS, null)
    fun loadMinFollowers(context: Context): String? = prefs(context).getString(KEY_MIN_FOLLOWERS, null)
    fun loadMaxReplies(context: Context): String? = prefs(context).getString(KEY_MAX_REPLIES, null)

    // ---------------------------------------------------------------------
    // نموذج وضع الإحماء
    // ---------------------------------------------------------------------

    fun saveWarmupForm(context: Context, accountsCsv: String, targets: String, comment: String) {
        prefs(context).edit()
            .putString(KEY_WARMUP_ACCOUNTS, accountsCsv)
            .putString(KEY_WARMUP_TARGETS, targets)
            .putString(KEY_WARMUP_COMMENT, comment)
            .apply()
    }

    fun loadWarmupAccounts(context: Context): String? = prefs(context).getString(KEY_WARMUP_ACCOUNTS, null)
    fun loadWarmupTargets(context: Context): String? = prefs(context).getString(KEY_WARMUP_TARGETS, null)
    fun loadWarmupComment(context: Context): String? = prefs(context).getString(KEY_WARMUP_COMMENT, null)

    // ---------------------------------------------------------------------
    // سجل "تم التعليق عليه من قبل" - مشترك بين الوضعين لمنع الازدحام على نفس الهدف
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

    fun getDisabledAccounts(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DISABLED_ACCOUNTS, emptySet()) ?: emptySet()

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

    fun getExclusionLog(context: Context): List<ExclusionLogEntry> = readExclusionLog(prefs(context))

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

    // ---------------------------------------------------------------------
    // حسابات معروفة (هاندل + refreshJwt) - للفحص الدوري بالخلفية
    // ---------------------------------------------------------------------

    /** يحفظ/يحدّث refreshJwt لحساب معروف (يُستدعى بعد كل تسجيل دخول ناجح). */
    fun rememberAccount(context: Context, handle: String, refreshJwt: String) {
        if (refreshJwt.isBlank()) return
        val p = prefs(context)
        val current = readKnownAccounts(p).toMutableMap()
        current[handle] = refreshJwt
        p.edit().putString(KEY_KNOWN_ACCOUNTS, writeKnownAccounts(current)).apply()
    }

    fun getKnownAccounts(context: Context): Map<String, String> = readKnownAccounts(prefs(context))

    private fun readKnownAccounts(p: SharedPreferences): Map<String, String> {
        val raw = p.getString(KEY_KNOWN_ACCOUNTS, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = LinkedHashMap<String, String>()
            obj.keys().forEach { key -> map[key] = obj.getString(key) }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun writeKnownAccounts(map: Map<String, String>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    // ---------------------------------------------------------------------
    // حالة وضع الإحماء لكل حساب (تاريخ البدء + عدّاد اليوم، يُصفَّر تلقائياً كل يوم جديد)
    // ---------------------------------------------------------------------

    fun getWarmupState(context: Context, handle: String): WarmupState {
        val p = prefs(context)
        val map = readWarmupState(p)
        val existing = map[handle]
        val todayStr = today()
        return if (existing == null) {
            WarmupState(startDate = todayStr, todayDate = todayStr, todayCount = 0)
        } else if (existing.todayDate != todayStr) {
            WarmupState(startDate = existing.startDate, todayDate = todayStr, todayCount = 0)
        } else {
            existing
        }
    }

    /** يسجّل تعليقاً جديداً لحساب في وضع الإحماء (يهيّئ الحالة أول مرة، ويصفّر عند يوم جديد). */
    fun recordWarmupComment(context: Context, handle: String) {
        val p = prefs(context)
        val map = readWarmupState(p).toMutableMap()
        val state = getWarmupState(context, handle)
        map[handle] = state.copy(todayCount = state.todayCount + 1)
        p.edit().putString(KEY_WARMUP_STATE, writeWarmupState(map)).apply()
    }

    /** عدد الأيام منذ بدء الإحماء لهذا الحساب (اليوم الأول = 1). */
    fun warmupDayNumber(context: Context, handle: String): Int {
        val state = getWarmupState(context, handle)
        return try {
            val start = dayFormat.parse(state.startDate)?.time ?: return 1
            val now = dayFormat.parse(today())?.time ?: return 1
            (((now - start) / 86_400_000L).toInt() + 1).coerceAtLeast(1)
        } catch (e: Exception) {
            1
        }
    }

    private fun readWarmupState(p: SharedPreferences): Map<String, WarmupState> {
        val raw = p.getString(KEY_WARMUP_STATE, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = LinkedHashMap<String, WarmupState>()
            obj.keys().forEach { key ->
                val o = obj.getJSONObject(key)
                map[key] = WarmupState(
                    startDate = o.getString("start"),
                    todayDate = o.getString("date"),
                    todayCount = o.getInt("count")
                )
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun writeWarmupState(map: Map<String, WarmupState>): String {
        val obj = JSONObject()
        map.forEach { (handle, state) ->
            obj.put(handle, JSONObject().apply {
                put("start", state.startDate)
                put("date", state.todayDate)
                put("count", state.todayCount)
            })
        }
        return obj.toString()
    }
}
