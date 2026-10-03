package com.bluesky.bot

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient

/**
 * فحص دوري بالخلفية (كل عدة ساعات، حتى بدون تشغيل أي جلسة تفاعل) للتأكد أن كل حساب
 * بوت "معروف" ما زال قادراً على تجديد جلسته. إن فشل التجديد، يُعطَّل الحساب تلقائياً
 * بنفس آلية EngagementService (سجل + إشعار)، بدل اكتشاف ذلك لاحقاً أثناء جلسة فعلية.
 */
class AccountHealthCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "account_health_check"
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val client = OkHttpClient()
        val api = BskyApi(client) { /* لا حاجة لسجل مرئي هنا، الفحص صامت */ }

        val known = BotPrefs.getKnownAccounts(context)
        val disabled = BotPrefs.getDisabledAccounts(context)

        known.forEach { (handle, refreshJwt) ->
            if (handle in disabled) return@forEach

            val session = BskyApi.AccountSession(handle = handle, did = "", jwt = "", refreshJwt = refreshJwt)
            val ok = try {
                api.refreshAccountSession(session)
            } catch (e: Exception) {
                false
            }

            if (ok) {
                // التوكن الجديد غالباً مختلف (rotating refresh token) - نحدّث المحفوظ
                BotPrefs.rememberAccount(context, handle, session.refreshJwt)
            } else {
                AccountAlerts.exclude(
                    context, handle,
                    "فشل الفحص الدوري: تعذر تجديد الجلسة (قد يكون الحساب معلّقاً أو كلمة المرور تغيرت)"
                )
            }
        }

        return Result.success()
    }
}
