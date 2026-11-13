package dev.xiaoman.llm

import dev.xiaoman.api.LlmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Reads `GET {baseUrl}/models` so Settings can offer the provider's model ids instead of
 * asking the user to type one from memory. Works for any OpenAI-compatible endpoint
 * (OpenAI, Ark, Bailian, OpenRouter, Ollama, vLLM…).
 *
 * Providers that do not implement the endpoint throw [LlmException]; the screen then falls
 * back to the free-text field, so nothing here is mandatory.
 */
object ModelCatalog {

    suspend fun listModels(baseUrl: String, apiKey: String): List<String> =
        fetch(baseUrl, apiKey, LlmClients.sharedHttp)

    internal suspend fun fetch(
        baseUrl: String,
        apiKey: String,
        http: OkHttpClient,
    ): List<String> = withContext(Dispatchers.IO) {
        val url = baseUrl.trim().trimEnd('/') + "/models"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .get()
            .build()
        val body = try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw LlmException("HTTP ${response.code}：${detail(text)}", response.code)
                }
                text
            }
        } catch (e: IOException) {
            throw LlmException("连接失败：${e.message ?: e.javaClass.simpleName}", cause = e)
        }
        parse(body, url)
    }

    internal fun parse(body: String, url: String = "models"): List<String> {
        val root = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw LlmException("返回内容不是 JSON：${body.take(120)}", cause = e)
        }
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return items.mapNotNull { element ->
            when (element) {
                is JsonObject -> (element["id"] as? JsonPrimitive)?.contentOrNull
                    ?: (element["name"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> element.contentOrNull
                else -> null
            }
        }.filter { it.isNotBlank() }.distinct().sorted()
    }

    private fun detail(body: String): String = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        (root["error"] as? JsonObject)?.let { (it["message"] as? JsonPrimitive)?.contentOrNull }
            ?: (root["message"] as? JsonPrimitive)?.contentOrNull
    }.getOrNull() ?: body.take(200).ifBlank { "空响应" }

    private val json = Json { ignoreUnknownKeys = true }
}
