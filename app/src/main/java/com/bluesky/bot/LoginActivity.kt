package com.bluesky.bot

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class LoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACCOUNT_HANDLES = "extra_account_handles"
        const val EXTRA_ACCOUNT_DIDS = "extra_account_dids"
        const val EXTRA_ACCOUNT_JWTS = "extra_account_jwts"
        const val EXTRA_ACCOUNT_REFRESH_JWTS = "extra_account_refresh_jwts"
        const val EXTRA_ACCOUNT_PDS_URLS = "extra_account_pds_urls"
    }

    private val client = OkHttpClient()
    private val api = BskyApi(client) { /* لا حاجة لسجل مرئي أثناء تسجيل الدخول */ }
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var accountsInput: EditText
    private lateinit var loginBtn: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        accountsInput = findViewById(R.id.accountsInput)
        loginBtn = findViewById(R.id.loginBtn)
        statusText = findViewById(R.id.statusText)

        loginBtn.setOnClickListener {
            val lines = accountsInput.text.toString()
                .split("\n").map { it.trim() }.filter { it.isNotEmpty() }

            if (lines.isEmpty()) {
                statusText.text = "خطأ: أدخل حساب واحد على الأقل بصيغة handle:password"
                return@setOnClickListener
            }

            loginBtn.isEnabled = false

            val successHandles = ArrayList<String>()
            val successDids = ArrayList<String>()
            val successJwts = ArrayList<String>()
            val successRefreshJwts = ArrayList<String>()
            val successPdsUrls = ArrayList<String>()
            val failedHandles = ArrayList<String>()

            scope.launch(Dispatchers.IO) {
                for ((i, line) in lines.withIndex()) {
                    val parts = line.split(":", limit = 2)
                    if (parts.size != 2) {
                        failedHandles.add(line)
                        continue
                    }
                    val identifier = parts[0].trim()
                    val pass = parts[1].trim()

                    withContext(Dispatchers.Main) {
                        statusText.text = "جاري اكتشاف خادم الحساب وتسجيل الدخول (${i + 1}/${lines.size}): $identifier..."
                    }

                    // يكتشف خادم الحساب الحقيقي (PDS) تلقائياً قبل تسجيل الدخول - يعمل
                    // بنفس الدقة سواء كان الحساب على bsky.social أو أي خادم مستقل آخر
                    // (بلاك سكاي، إيروسكاي، أو أي PDS مستقل على الشبكة).
                    when (val result = api.login(identifier, pass)) {
                        is BskyApi.LoginResult.Success -> {
                            successHandles.add(result.handle)
                            successDids.add(result.did)
                            successJwts.add(result.accessJwt)
                            successRefreshJwts.add(result.refreshJwt)
                            successPdsUrls.add(result.pdsUrl)
                        }
                        is BskyApi.LoginResult.Failure -> {
                            failedHandles.add("$identifier (${result.reason})")
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (successHandles.isEmpty()) {
                        loginBtn.isEnabled = true
                        statusText.text = "فشل تسجيل الدخول لجميع الحسابات:\n${failedHandles.joinToString("\n")}"
                        return@withContext
                    }

                    if (failedHandles.isNotEmpty()) {
                        statusText.text = "تم الدخول بـ ${successHandles.size} حساب. فشل: ${failedHandles.joinToString(", ")}"
                    }

                    val intent = Intent(this@LoginActivity, MainActivity::class.java).apply {
                        putStringArrayListExtra(EXTRA_ACCOUNT_HANDLES, successHandles)
                        putStringArrayListExtra(EXTRA_ACCOUNT_DIDS, successDids)
                        putStringArrayListExtra(EXTRA_ACCOUNT_JWTS, successJwts)
                        putStringArrayListExtra(EXTRA_ACCOUNT_REFRESH_JWTS, successRefreshJwts)
                        putStringArrayListExtra(EXTRA_ACCOUNT_PDS_URLS, successPdsUrls)
                    }
                    startActivity(intent)
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
