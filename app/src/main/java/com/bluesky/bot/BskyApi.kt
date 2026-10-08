package com.bluesky.bot

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.net.URLEncoder

/**
 * طبقة مشتركة للتخاطب مع Bluesky API، يستخدمها كل من EngagementService (الوضع الرئيسي)
 * و WarmupEngagementService (وضع الإحماء) - لتفادي تكرار نفس منطق الشبكة واختيار
 * المنشورات في مكانين مختلفين.
 */
class BskyApi(
    private val client: OkHttpClient,
    private val log: (String) -> Unit
) {
    data class AccountSession(
        val handle: String,
        val did: String,
        var jwt: String,
        var refreshJwt: String,
        /** خادم الحساب الشخصي (PDS) المكتشف - مثل https://bsky.social أو أي خادم مستقل آخر. */
        val pdsUrl: String = DEFAULT_PDS
    )

    /** نتيجة تسجيل الدخول: تحمل الخادم الحقيقي المكتشف لحساب هذا المستخدم. */
    sealed class LoginResult {
        data class Success(
            val handle: String,
            val did: String,
            val pdsUrl: String,
            val accessJwt: String,
            val refreshJwt: String
        ) : LoginResult()
        data class Failure(val reason: String) : LoginResult()
    }

    sealed class PostLookup {
        data class Found(
            val uri: String,
            val cid: String,
            val rootUri: String,
            val rootCid: String,
            val isReply: Boolean
        ) : PostLookup()
        object NoOriginalPost : PostLookup()
        object RateLimited : PostLookup()
        data class Unavailable(val code: Int) : PostLookup()
        data class OtherError(val code: Int) : PostLookup()
    }

    companion object {
        const val DEFAULT_PDS = "https://bsky.social"
        private const val FEED_PAGE_SIZE = 50
        private const val MAX_FEED_PAGES = 3
        private const val MAX_POST_AGE_HOURS = 24L
    }

    fun isAccountUnavailable(code: Int): Boolean = code == 400 || code == 401 || code == 403

    /** ينفّذ طلباً موثقاً، ويحاول تجديد الجلسة تلقائياً مرة واحدة عند 401 (توكن منتهي). */
    fun executeAuthorized(account: AccountSession, buildRequest: (jwt: String) -> Request): Response {
        var response = client.newCall(buildRequest(account.jwt)).execute()
        if (response.code == 401) {
            response.close()
            refreshAccountSession(account)
            response = client.newCall(buildRequest(account.jwt)).execute()
        }
        return response
    }

    /** يحاول تجديد accessJwt عبر refreshJwt. يحدّث account.jwt (و refreshJwt إن رجع جديد) عند النجاح. */
    fun refreshAccountSession(account: AccountSession): Boolean {
        if (account.refreshJwt.isBlank()) return false
        return try {
            val req = Request.Builder()
                .url("${account.pdsUrl}/xrpc/com.atproto.server.refreshSession")
                .addHeader("Authorization", "Bearer ${account.refreshJwt}")
                .post("".toRequestBody(null))
                .build()
            val res = client.newCall(req).execute()
            val bodyStr = res.body?.string() ?: ""
            if (!res.isSuccessful) return false

            val json = JSONObject(bodyStr)
            account.jwt = json.getString("accessJwt")
            val newRefresh = json.optString("refreshJwt", "")
            if (newRefresh.isNotEmpty()) account.refreshJwt = newRefresh
            log("🔄 تم تجديد جلسة الحساب ${account.handle} تلقائياً.")
            true
        } catch (e: Exception) {
            false
        }
    }

    /** يرجع DID للهاندل، أو null إن فشل (غير موجود / خطأ شبكة). طلب عام بلا توثيق. */
    fun resolveHandle(handle: String): String? {
        return try {
            val res = client.newCall(
                Request.Builder()
                    .url("https://bsky.social/xrpc/com.atproto.identity.resolveHandle?handle=$handle")
                    .build()
            ).execute()
            val body = res.body?.string() ?: ""
            if (res.isSuccessful) JSONObject(body).getString("did") else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * يكتشف خادم الحساب الشخصي (PDS) الحقيقي لحساب معيّن عبر قراءة وثيقة الـ DID الخاصة به:
     * - did:plc:xxx → تُقرأ من سجل PLC العام (plc.directory).
     * - did:web:domain → تُقرأ من domain/.well-known/did.json مباشرة.
     * يبحث داخل مصفوفة "service" عن الخدمة من نوع AtprotoPersonalDataServer ويرجع عنوانها.
     * عند أي فشل (شبكة/تنسيق)، يرجع الخادم الافتراضي bsky.social كأكثر الحالات شيوعاً.
     */
    fun discoverPdsUrl(did: String): String {
        return try {
            val docUrl = when {
                did.startsWith("did:plc:") -> "https://plc.directory/$did"
                did.startsWith("did:web:") -> {
                    val domain = did.removePrefix("did:web:").replace(":", "/")
                    "https://$domain/.well-known/did.json"
                }
                else -> return DEFAULT_PDS
            }
            val res = client.newCall(Request.Builder().url(docUrl).build()).execute()
            if (!res.isSuccessful) return DEFAULT_PDS
            val body = res.body?.string() ?: return DEFAULT_PDS
            val services = JSONObject(body).optJSONArray("service") ?: return DEFAULT_PDS

            for (i in 0 until services.length()) {
                val svc = services.getJSONObject(i)
                val id = svc.optString("id")
                val type = svc.optString("type")
                if (id == "#atproto_pds" || type == "AtprotoPersonalDataServer") {
                    val endpoint = svc.optString("serviceEndpoint")
                    if (endpoint.isNotBlank()) return endpoint.trimEnd('/')
                }
            }
            DEFAULT_PDS
        } catch (e: Exception) {
            DEFAULT_PDS
        }
    }

    /**
     * تسجيل دخول كامل ودقيق: يحل الـ handle إلى DID، يكتشف خادمه الشخصي الحقيقي (قد يكون
     * bsky.social أو أي خادم مستقل آخر)، ثم ينفّذ createSession على ذلك الخادم تحديداً -
     * لا على bsky.social افتراضاً. لو كان المعرّف بريداً إلكترونياً (يحتوي @) بدل handle، لا
     * يمكن اكتشاف الخادم مسبقاً فيُستخدم bsky.social كأفضل تخمين متاح.
     */
    fun login(identifier: String, password: String): LoginResult {
        val pdsForLogin = if (identifier.contains("@")) {
            DEFAULT_PDS
        } else {
            val did = resolveHandle(identifier)
                ?: return LoginResult.Failure("تعذر العثور على الحساب (تحقق من صحة الهاندل)")
            discoverPdsUrl(did)
        }

        return try {
            val json = JSONObject().apply {
                put("identifier", identifier)
                put("password", password)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$pdsForLogin/xrpc/com.atproto.server.createSession")
                .post(body)
                .build()
            val res = client.newCall(req).execute()
            val resStr = res.body?.string() ?: ""

            if (!res.isSuccessful) {
                return LoginResult.Failure("فشل تسجيل الدخول على خادم $pdsForLogin (رمز ${res.code})")
            }

            val resJson = JSONObject(resStr)
            LoginResult.Success(
                handle = resJson.getString("handle"),
                did = resJson.getString("did"),
                pdsUrl = pdsForLogin,
                accessJwt = resJson.getString("accessJwt"),
                refreshJwt = resJson.optString("refreshJwt", "")
            )
        } catch (e: Exception) {
            LoginResult.Failure("خطأ شبكة أثناء تسجيل الدخول: ${e.message}")
        }
    }

    /** يرجع عدد متابعي حساب، أو null إن تعذّر الجلب (لا يُستخدم كسبب استبعاد وحده). */
    fun fetchFollowersCount(targetDid: String): Int? {
        return try {
            val encoded = URLEncoder.encode(targetDid, "UTF-8")
            val res = client.newCall(
                Request.Builder()
                    .url("https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=$encoded")
                    .build()
            ).execute()
            if (!res.isSuccessful) return null
            val body = res.body?.string() ?: return null
            val count = JSONObject(body).optInt("followersCount", -1)
            if (count < 0) null else count
        } catch (e: Exception) {
            null
        }
    }

    /**
     * يتحقق من ظهور ردّنا فعلياً ضمن شجرة ردود المنشور الذي علّقنا عليه - هذا هو ما يراه
     * أي شخص آخر يفتح ذلك المنشور، على عكس جلب تعليقنا بمفرده (الذي يبقى "موجوداً" على
     * الشبكة حتى لو بلوسكاي فلترته من ظهوره ضمن الردود الفعلية - وهو شكل شائع للحجب
     * الصامت/shadowban). عند فشل الجلب (خطأ شبكة مؤقت)، نرجع true حتى لا نعاقب الحساب
     * على مشكلة اتصال عابرة؛ false لا تُرجَع إلا بعد جلب الشجرة فعلياً وعدم إيجاد ردّنا فيها.
     */
    fun isReplyVisibleInThread(parentPostUri: String, ourReplyUri: String): Boolean {
        return try {
            val encodedUri = URLEncoder.encode(parentPostUri, "UTF-8")
            val req = Request.Builder()
                .url("https://public.api.bsky.app/xrpc/app.bsky.feed.getPostThread?uri=$encodedUri&depth=25")
                .build()
            val res = client.newCall(req).execute()
            if (!res.isSuccessful) return true
            val bodyStr = res.body?.string() ?: return true
            val thread = JSONObject(bodyStr).optJSONObject("thread") ?: return true
            if (thread.optString("\$type") != "app.bsky.feed.defs#threadViewPost") return true
            findReplyInThread(thread, ourReplyUri)
        } catch (e: Exception) {
            true
        }
    }

    private fun findReplyInThread(threadNode: JSONObject, targetUri: String): Boolean {
        val replies = threadNode.optJSONArray("replies") ?: return false
        for (i in 0 until replies.length()) {
            val replyNode = replies.getJSONObject(i)
            if (replyNode.optString("\$type") != "app.bsky.feed.defs#threadViewPost") continue
            val post = replyNode.optJSONObject("post") ?: continue
            if (post.optString("uri") == targetUri) return true
            if (findReplyInThread(replyNode, targetUri)) return true
        }
        return false
    }

    /**
     * يختار المنشور المناسب للتعليق عليه:
     * 1) آخر منشور أصلي كتبه صاحب الحساب، إذا كان عمره أقل من 24 ساعة.
     * 2) وإلا آخر رد كتبه صاحب الحساب (إن وُجد أحدث من المنشور الأصلي).
     * 3) وإلا آخر منشور أصلي مهما كان عمره.
     * يتخطى: إعادات النشر، ما كاتبه غير صاحب الحساب، المحتوى المرتبط بالحسابات المستثناة،
     * المنشورات المغلقة الردود أو التي يحظر فيها صاحبها حساب البوت، منشور بلغة لا تطابق
     * allowedLangs (إن حُدّدت)، ومنشور عليه ردود أكثر من maxReplies (إن كانت > 0).
     */
    fun findTargetPost(
        account: AccountSession,
        targetDid: String,
        excludedDids: Set<String>,
        allowedLangs: Set<String>,
        maxReplies: Int
    ): PostLookup {
        val maxAgeMs = MAX_POST_AGE_HOURS * 3_600_000L
        val now = System.currentTimeMillis()

        var firstReply: PostLookup.Found? = null
        var cursor: String? = null

        for (page in 0 until MAX_FEED_PAGES) {
            // قراءة عامة عبر الـ AppView - لا تحتاج توثيق الحساب إطلاقاً، وبالتالي تعمل بنفس
            // الدقة بغض النظر عن خادم (PDS) حساب البوت أو خادم الهدف نفسه.
            val url = StringBuilder(
                "https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed" +
                    "?actor=$targetDid&limit=$FEED_PAGE_SIZE&filter=posts_with_replies"
            )
            val c = cursor
            if (c != null) url.append("&cursor=").append(URLEncoder.encode(c, "UTF-8"))

            val res = client.newCall(Request.Builder().url(url.toString()).build()).execute()
            val bodyStr = res.body?.string() ?: ""

            if (res.code == 429) return PostLookup.RateLimited
            if (!res.isSuccessful) return PostLookup.OtherError(res.code)

            val json = JSONObject(bodyStr)
            val feed = json.getJSONArray("feed")

            for (i in 0 until feed.length()) {
                val item = feed.getJSONObject(i)

                val reasonType = item.optJSONObject("reason")?.optString("\$type")
                if (reasonType == "app.bsky.feed.defs#reasonRepost") continue

                val post = item.getJSONObject("post")
                val authorDid = post.getJSONObject("author").getString("did")
                if (authorDid != targetDid || authorDid in excludedDids) continue

                val authorBlockedBy = post.getJSONObject("author")
                    .optJSONObject("viewer")?.optBoolean("blockedBy", false) == true
                if (authorBlockedBy) continue

                val replyDisabled = post.optJSONObject("viewer")?.optBoolean("replyDisabled", false) == true
                if (replyDisabled) continue

                if (maxReplies > 0 && post.optInt("replyCount", 0) > maxReplies) continue

                val embed = post.optJSONObject("embed")
                val quotedDid = embed?.optJSONObject("record")
                    ?.optJSONObject("author")?.optString("did")
                    ?: embed?.optJSONObject("record")?.optJSONObject("record")
                        ?.optJSONObject("author")?.optString("did")
                if (!quotedDid.isNullOrEmpty() && quotedDid in excludedDids) continue

                val record = post.optJSONObject("record")

                if (allowedLangs.isNotEmpty()) {
                    val langsArr = record?.optJSONArray("langs")
                    if (langsArr != null && langsArr.length() > 0) {
                        val postLangs = (0 until langsArr.length())
                            .map { langsArr.getString(it).lowercase().substringBefore("-") }
                        if (postLangs.none { it in allowedLangs }) continue
                    }
                }

                val uri = post.getString("uri")
                val cid = post.getString("cid")
                val replyRef = record?.optJSONObject("reply")

                if (replyRef == null) {
                    val postAt = try {
                        java.time.Instant.parse(post.getString("indexedAt")).toEpochMilli()
                    } catch (e: Exception) {
                        now
                    }
                    val original = PostLookup.Found(uri, cid, uri, cid, false)
                    return if (now - postAt < maxAgeMs) original else (firstReply ?: original)
                }

                val rootRef = replyRef.getJSONObject("root")
                val parentRef = replyRef.getJSONObject("parent")
                val rootUri = rootRef.getString("uri")
                val parentUri = parentRef.getString("uri")
                val touchesExcluded = excludedDids.any {
                    rootUri.startsWith("at://$it/") || parentUri.startsWith("at://$it/")
                }
                if (touchesExcluded) continue

                if (firstReply == null) {
                    firstReply = PostLookup.Found(
                        uri = uri,
                        cid = cid,
                        rootUri = rootUri,
                        rootCid = rootRef.getString("cid"),
                        isReply = true
                    )
                }
            }

            val next = json.optString("cursor")
            if (next.isEmpty()) break
            cursor = next
        }

        return firstReply ?: PostLookup.NoOriginalPost
    }

    /** ينشر رداً على منشور محدد ويرجع استجابة الخادم كما هي (يفحصها المنادي). */
    fun postReply(
        account: AccountSession,
        text: String,
        rootUri: String,
        rootCid: String,
        parentUri: String,
        parentCid: String
    ): Response {
        val replyRecord = JSONObject().apply {
            put("text", text)
            put("createdAt", java.time.Instant.now().toString())
            put("reply", JSONObject().apply {
                put("root", JSONObject().apply {
                    put("uri", rootUri)
                    put("cid", rootCid)
                })
                put("parent", JSONObject().apply {
                    put("uri", parentUri)
                    put("cid", parentCid)
                })
            })
        }
        val createRecordJson = JSONObject().apply {
            put("repo", account.did)
            put("collection", "app.bsky.feed.post")
            put("record", replyRecord)
        }
        val postBody = createRecordJson.toString().toRequestBody("application/json".toMediaType())
        return executeAuthorized(account) { jwt ->
            Request.Builder()
                .url("${account.pdsUrl}/xrpc/com.atproto.repo.createRecord")
                .addHeader("Authorization", "Bearer $jwt")
                .post(postBody)
                .build()
        }
    }
}
