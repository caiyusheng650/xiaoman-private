package dev.xiaoman.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpRequestToolTest {
    private lateinit var server: MockWebServer
    private lateinit var executor: HttpRequestExecutor

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        executor = HttpRequestExecutor(OkHttpClient())
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun getReturnsStatusAndBody() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setHeader("Content-Type", "application/json"))

        val result = executor.execute(args("""{"method":"GET","url":"${server.url("/v1/ping")}"}"""))
        val root = Json.parseToJsonElement(result) as JsonObject

        assertEquals(200, (root["statusCode"] as JsonPrimitive).intOrNull)
        assertTrue("\"ok\":true" in (root["body"] as JsonPrimitive).content)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun postSendsJsonBodyAndHeaders() = runTest {
        server.enqueue(MockResponse().setBody("""{"created":true}"""))

        val result = executor.execute(
            args(
                """{"method":"POST","url":"${server.url("/v1/pages")}",
                "headers":{"Authorization":"Bearer t"},
                "body":{"name":"x"}}""",
            ),
        )
        val root = Json.parseToJsonElement(result) as JsonObject

        assertEquals(200, (root["statusCode"] as JsonPrimitive).intOrNull)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("Bearer t", request.getHeader("Authorization"))
        assertTrue("\"name\":\"x\"" in request.body.readUtf8())
    }

    @Test
    fun missingUrlReturnsError() = runTest {
        val result = executor.execute(args("""{"method":"GET"}"""))
        assertTrue("\"error\"" in result)
        assertTrue("缺少 url" in result)
    }

    @Test
    fun unsupportedMethodReturnsError() = runTest {
        val result = executor.execute(args("""{"method":"TRACE","url":"https://example.com"}"""))
        assertTrue("\"error\"" in result)
        assertTrue("不支持的请求方法" in result)
    }
}
