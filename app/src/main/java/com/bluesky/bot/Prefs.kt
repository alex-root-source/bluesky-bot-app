package com.bluesky.bot

import android.content.Context

/**
 * تخزين محلي بسيط (SharedPreferences) لأمرين:
 * 1. حفظ محتوى نموذج الأداة (الأهداف، الاستثناء، التعليقات، التأخير) حتى ما تضيع بعد إغلاق التطبيق.
 * 2. سجل دائم بكل الحسابات اللي تم التعليق عليها من قبل، لمنع تكرار التعليق على نفس الحساب
 *    بين جلسات مختلفة (حتى لو نفس القائمة انلصقت مرة ثانية بعد أيام).
 */
object BotPrefs {
    private const val PREFS_NAME = "bluesky_bot_prefs"

    private const val KEY_HANDLES = "handles_input"
    private const val KEY_EXCLUDE = "exclude_input"
    private const val KEY_COMMENT = "comment_input"
    private const val KEY_DELAY_MIN = "delay_min"
    private const val KEY_DELAY_MAX = "delay_max"
    private const val KEY_COMMENTED_TARGETS = "commented_targets"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveForm(
        context: Context,
        handles: String,
        exclude: String,
        comment: String,
        delayMin: String,
        delayMax: String
    ) {
        prefs(context).edit()
            .putString(KEY_HANDLES, handles)
            .putString(KEY_EXCLUDE, exclude)
            .putString(KEY_COMMENT, comment)
            .putString(KEY_DELAY_MIN, delayMin)
            .putString(KEY_DELAY_MAX, delayMax)
            .apply()
    }

    fun loadHandles(context: Context): String? = prefs(context).getString(KEY_HANDLES, null)
    fun loadExclude(context: Context): String? = prefs(context).getString(KEY_EXCLUDE, null)
    fun loadComment(context: Context): String? = prefs(context).getString(KEY_COMMENT, null)
    fun loadDelayMin(context: Context): String? = prefs(context).getString(KEY_DELAY_MIN, null)
    fun loadDelayMax(context: Context): String? = prefs(context).getString(KEY_DELAY_MAX, null)

    /** يرجع مجموعة كل الحسابات (هاندل نظيف) اللي تم التعليق عليها في أي جلسة سابقة. */
    fun getCommentedTargets(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_COMMENTED_TARGETS, emptySet()) ?: emptySet()

    /** يضيف هاندل واحد لسجل "تم التعليق عليه من قبل" الدائم. */
    fun addCommentedTarget(context: Context, target: String) {
        val p = prefs(context)
        val current = HashSet(p.getStringSet(KEY_COMMENTED_TARGETS, emptySet()) ?: emptySet())
        current.add(target)
        p.edit().putStringSet(KEY_COMMENTED_TARGETS, current).apply()
    }
}
