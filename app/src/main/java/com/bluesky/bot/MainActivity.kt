package com.bluesky.bot

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var accessJwt: String? = null
    private var userDid: String? = null
    private var isEngagementRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val handleInput = findViewById<EditText>(R.id.handleInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val loginBtn = findViewById<Button>(R.id.loginBtn)
        val handlesInput = findViewById<EditText>(R.id.handlesInput)
        val commentInput = findViewById<EditText>(R.id.commentInput)
        val startBtn = findViewById<Button>(R.id.startBtn)
        val stopBtn = findViewById<Button>(R.id.stopBtn)
        val logText = findViewById<TextView>(R.id.logText)

        fun addLog(msg: String) {
            val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            logText.append("[$time] $msg\n")
        }

        loginBtn.setOnClickListener {
            val handle = handleInput.text.toString().trim()
            val pass = passwordInput.text.toString().trim()
            if (handle.isEmpty() || pass.isEmpty()) {
                addLog("خطأ: يرجى إدخال اسم المستخدم وكلمة المرور.")
                return@setOnClickListener
            }

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
                        accessJwt = resJson.getString("accessJwt")
                        userDid = resJson.getString("did")
                        withContext(Dispatchers.Main) {
                            addLog("تم تسجيل الدخول بنجاح!")
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            addLog("فشل تسجيل الدخول: $resStr")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        addLog("خطأ شبكة: ${e.message}")
                    }
                }
            }
        }

        startBtn.setOnClickListener {
            if (accessJwt == null) {
                addLog("تنبيه: يلزم تسجيل الدخول أولاً.")
                return@setOnClickListener
            }

            val rawHandles = handlesInput.text.toString()
            val handles = rawHandles.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val comment = commentInput.text.toString().trim().ifEmpty { "شكراً لك" }

            if (handles.isEmpty()) {
                addLog("خطأ: أدخل قائمة الحسابات المستهدفة.")
                return@setOnClickListener
            }

            isEngagementRunning = true
            addLog("بدء التفاعل مع ${handles.size} حساب فريد...")

            scope.launch(Dispatchers.IO) {
                for (handle in handles) {
                    if (!isEngagementRunning) {
                        withContext(Dispatchers.Main) { addLog("تم إيقاف التفاعل بواسطة المستخدم.") }
                        break
                    }

                    val cleanHandle = handle.replace("@", "")

                    try {
                        val resolveReq = Request.Builder()
                            .url("https://bsky.social/xrpc/com.atproto.identity.resolveHandle?handle=$cleanHandle")
                            .build()
                        val resolveRes = client.newCall(resolveReq).execute()

                        if (resolveRes.isSuccessful) {
                            val targetDid = JSONObject(resolveRes.body?.string() ?: "").getString("did")

                            val feedReq = Request.Builder()
                                .url("https://bsky.social/xrpc/app.bsky.feed.getAuthorFeed?actor=$targetDid&limit=1")
                                .addHeader("Authorization", "Bearer $accessJwt")
                                .build()
                            val feedRes = client.newCall(feedReq).execute()

                            if (feedRes.isSuccessful) {
                                val feedArray = JSONObject(feedRes.body?.string() ?: "").getJSONArray("feed")
                                if (feedArray.length() > 0) {
                                    val post = feedArray.getJSONObject(0).getJSONObject("post")
                                    val postUri = post.getString("uri")
                                    val postCid = post.getString("cid")

                                    val replyRecord = JSONObject().apply {
                                        put("text", comment)
                                        put("createdAt", java.time.Instant.now().toString())
                                        put("reply", JSONObject().apply {
                                            put("root", JSONObject().apply {
                                                put("uri", postUri)
                                                put("cid", postCid)
                                            })
                                            put("parent", JSONObject().apply {
                                                put("uri", postUri)
                                                put("cid", postCid)
                                            })
                                        })
                                    }

                                    val createRecordJson = JSONObject().apply {
                                        put("repo", userDid)
                                        put("collection", "app.bsky.feed.post")
                                        put("record", replyRecord)
                                    }

                                    val postBody = createRecordJson.toString().toRequestBody("application/json".toMediaType())
                                    val commentReq = Request.Builder()
                                        .url("https://bsky.social/xrpc/com.atproto.repo.createRecord")
                                        .addHeader("Authorization", "Bearer $accessJwt")
                                        .post(postBody)
                                        .build()

                                    val commentRes = client.newCall(commentReq).execute()
                                    if (commentRes.isSuccessful) {
                                        withContext(Dispatchers.Main) { addLog("تم التعليق بنجاح على $cleanHandle: \"$comment\"") }
                                    } else {
                                        withContext(Dispatchers.Main) { addLog("فشل التعليق على $cleanHandle") }
                                    }
                                } else {
                                    withContext(Dispatchers.Main) { addLog("لا توجد منشورات للحساب $cleanHandle") }
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) { addLog("تعذر العثور على $cleanHandle") }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { addLog("خطأ مع $cleanHandle: ${e.message}") }
                    }

                    for (i in 0 until 10) {
                        if (!isEngagementRunning) break
                        delay(1000)
                    }
                }
                isEngagementRunning = false
                withContext(Dispatchers.Main) { addLog("اكتملت العملية بالكامل!") }
            }
        }

        stopBtn.setOnClickListener {
            isEngagementRunning = false
            addLog("جاري إيقاف العملية...")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
