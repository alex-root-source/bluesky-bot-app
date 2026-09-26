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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACCOUNT_HANDLES = "extra_account_handles"
        const val EXTRA_ACCOUNT_DIDS = "extra_account_dids"
        const val EXTRA_ACCOUNT_JWTS = "extra_account_jwts"
    }

    private val client = OkHttpClient()
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
            val failedHandles = ArrayList<String>()

            scope.launch(Dispatchers.IO) {
                for ((i, line) in lines.withIndex()) {
                    val parts = line.split(":", limit = 2)
                    if (parts.size != 2) {
                        failedHandles.add(line)
                        continue
                    }
                    val handle = parts[0].trim()
                    val pass = parts[1].trim()

                    withContext(Dispatchers.Main) {
                        statusText.text = "تسجيل الدخول (${i + 1}/${lines.size}): $handle..."
                    }

                    try {
                        val json = JSONObject().apply {
                            put("identifier", handle)
                            put("password", pass)
                        }
                        val body = json.toString().toRequestBody("application/json".toMediaType())
                        val request = Request.Builder()
                            .url("https://bsky.social/xrpc/com.atproto.server.createSession")
                            .post(body)
                            .build()

                        val response = client.newCall(request).execute()
                        val resStr = response.body?.string() ?: ""

                        if (response.isSuccessful) {
                            val resJson = JSONObject(resStr)
                            successHandles.add(handle)
                            successDids.add(resJson.getString("did"))
                            successJwts.add(resJson.getString("accessJwt"))
                        } else {
                            failedHandles.add(handle)
                        }
                    } catch (e: Exception) {
                        failedHandles.add(handle)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (successHandles.isEmpty()) {
                        loginBtn.isEnabled = true
                        statusText.text = "فشل تسجيل الدخول لجميع الحسابات. تحقق من البيانات (الصيغة: handle:password)."
                        return@withContext
                    }

                    if (failedHandles.isNotEmpty()) {
                        statusText.text = "تم الدخول بـ ${successHandles.size} حساب. فشل: ${failedHandles.joinToString(", ")}"
                    }

                    val intent = Intent(this@LoginActivity, MainActivity::class.java).apply {
                        putStringArrayListExtra(EXTRA_ACCOUNT_HANDLES, successHandles)
                        putStringArrayListExtra(EXTRA_ACCOUNT_DIDS, successDids)
                        putStringArrayListExtra(EXTRA_ACCOUNT_JWTS, successJwts)
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
