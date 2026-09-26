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
        const val EXTRA_ACCESS_JWT = "extra_access_jwt"
        const val EXTRA_USER_DID = "extra_user_did"
        const val EXTRA_HANDLE = "extra_handle"
    }

    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var handleInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginBtn: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        handleInput = findViewById(R.id.handleInput)
        passwordInput = findViewById(R.id.passwordInput)
        loginBtn = findViewById(R.id.loginBtn)
        statusText = findViewById(R.id.statusText)

        loginBtn.setOnClickListener {
            val handle = handleInput.text.toString().trim()
            val pass = passwordInput.text.toString().trim()

            if (handle.isEmpty() || pass.isEmpty()) {
                statusText.text = "خطأ: يرجى إدخال اسم المستخدم وكلمة المرور."
                return@setOnClickListener
            }

            loginBtn.isEnabled = false
            statusText.text = "جاري تسجيل الدخول..."

            scope.launch(Dispatchers.IO) {
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
                        val accessJwt = resJson.getString("accessJwt")
                        val did = resJson.getString("did")

                        withContext(Dispatchers.Main) {
                            statusText.text = "تم تسجيل الدخول بنجاح!"
                            val intent = Intent(this@LoginActivity, MainActivity::class.java).apply {
                                putExtra(EXTRA_ACCESS_JWT, accessJwt)
                                putExtra(EXTRA_USER_DID, did)
                                putExtra(EXTRA_HANDLE, handle)
                            }
                            startActivity(intent)
                            finish()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            loginBtn.isEnabled = true
                            statusText.text = "فشل تسجيل الدخول: تحقق من اسم المستخدم وكلمة المرور."
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        loginBtn.isEnabled = true
                        statusText.text = "خطأ شبكة: ${e.message}"
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
