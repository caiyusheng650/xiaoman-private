package dev.xiaoman.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Built-in `http_request` tool: issues an HTTP/HTTPS request from the app itself and returns the
 * status code, response body and response headers as a JSON string for the model.
 *
 * The tool is intentionally generic: no host allow-list or confirmation gate is enforced here.
 * Response bodies are truncated to keep them from blowing up the model context.
 */
internal class HttpRequestExecutor(private val http: OkHttpClient) {

    suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val method = (JsonText.string(args, "method") ?: "GET").uppercase()
        if (method !in ALLOWED_METHODS) {
            return@withContext errorJson("不支持的请求方法：$method（仅 GET/POST/PUT/PATCH/DELETE）")
        }
        val url = JsonText.string(args, "url")?.trim().orEmpty()
        if (url.isEmpty()) return@withContext errorJson("缺少 url")

        val headers = args["headers"] as? JsonObject ?: JsonObject(emptyMap())
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) ->
            val text = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            if (!text.isNullOrEmpty()) builder.header(name, text)
        }

        val bodyElement = args["body"]
        val bodyJson = bodyElement?.takeIf { it !is JsonNull }?.toString()

        val request = when (method) {
            "GET" -> builder.get().build()
            "HEAD" -> builder.head().build()
            "POST" -> builder.post(bodyJson?.toRequestBody(JSON_MEDIA_TYPE) ?: emptyBody()).build()
            "PUT" -> builder.put(bodyJson?.toRequestBody(JSON_MEDIA_TYPE) ?: emptyBody()).build()
            "PATCH" -> builder.patch(bodyJson?.toRequestBody(JSON_MEDIA_TYPE) ?: emptyBody()).build()
            "DELETE" -> if (bodyJson != null) {
                builder.delete(bodyJson.toRequestBody(JSON_MEDIA_TYPE)).build()
            } else {
                builder.delete().build()
            }
            else -> return@withContext errorJson("不支持的请求方法：$method")
        }

        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val truncated = if (body.length > MAX_BODY_CHARS) {
                    body.take(MAX_BODY_CHARS) + "\n[响应已截断，超出长度限制]"
                } else body
                buildJsonObject {
                    put("statusCode", response.code)
                    put("isSuccessful", response.isSuccessful)
                    put("body", truncated)
                    putJsonObject("headers") {
                        response.headers.names().forEach { name ->
                            put(name, response.header(name).orEmpty())
                        }
                    }
                }.toString()
            }
        } catch (e: IOException) {
            errorJson("网络请求失败：${e.message ?: e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            errorJson("URL 非法：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun emptyBody(): RequestBody = "".toRequestBody(null)

    private fun errorJson(message: String): String =
        buildJsonObject { put("error", message) }.toString()

    companion object {
        private val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        private const val MAX_BODY_CHARS = 3000
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Shared client for built-in HTTP requests; call/connect/read timeouts enforced here. */
        val shared: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
