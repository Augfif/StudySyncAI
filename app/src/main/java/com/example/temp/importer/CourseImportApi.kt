package com.example.temp.importer

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LlmApiConfig(
    val endpoint: String,
    val apiKey: String,
    val model: String
)

class CourseImportApi(
    okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    private val eventSourceFactory: EventSource.Factory = EventSources.createFactory(okHttpClient)

    suspend fun parseScheduleWithSse(
        config: LlmApiConfig,
        html: String,
        rawText: String,
        onBytesReceived: (Int) -> Unit
    ): String = suspendCancellableCoroutine { continuation ->
        val payload = JSONObject()
            .put("model", config.model)
            .put("stream", true)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", COURSE_SCHEDULE_SYSTEM_PROMPT)
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", buildPrompt(html, rawText))
                    )
            )

        val request = Request.Builder()
            .url(config.endpoint)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Accept", "text/event-stream")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val resultBuilder = StringBuilder()
        var accumulatedBytes = 0
        var finished = false

        lateinit var eventSource: EventSource
        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (!continuation.isActive || finished) return

                if (data == "[DONE]") {
                    finished = true
                    eventSource.cancel()
                    continuation.resume(resultBuilder.toString())
                    return
                }

                val chunk = extractTextChunk(data)
                if (chunk.isEmpty()) return

                resultBuilder.append(chunk)
                accumulatedBytes += chunk.toByteArray(Charsets.UTF_8).size
                onBytesReceived(accumulatedBytes)
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                if (!continuation.isActive || finished) return
                finished = true
                continuation.resumeWithException(
                    t ?: IOException("SSE request failed: ${response?.code ?: "unknown"}")
                )
            }

            override fun onClosed(eventSource: EventSource) {
                if (!continuation.isActive || finished) return
                finished = true
                continuation.resume(resultBuilder.toString())
            }
        }

        eventSource = eventSourceFactory.newEventSource(request, listener)
        continuation.invokeOnCancellation {
            eventSource.cancel()
        }
    }

    private fun buildPrompt(html: String, rawText: String): String {
        return "```html\n$html\n```\n\n```raw-text\n$rawText\n```"
    }

    private fun extractTextChunk(data: String): String {
        return runCatching {
            val root = JSONObject(data)

            // 兼容 1：标准的 choices 数组结构
            val choices = root.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val first = choices.optJSONObject(0)

                // 优先取标准流式格式 delta.content
                val delta = first?.optJSONObject("delta")
                if (delta != null && !delta.isNull("content")) {
                    val content = delta.optString("content")
                    if (content.isNotEmpty() && content != "null") {
                        return@runCatching content
                    }
                }

                // 兼容取 message.content (部分国内模型在流式输出中依然使用 message)
                val message = first?.optJSONObject("message")
                if (message != null && !message.isNull("content")) {
                    val content = message.optString("content")
                    if (content.isNotEmpty() && content != "null") {
                        return@runCatching content
                    }
                }
            }

            // 兼容 2：部分模型直接在顶层返回 output_text
            val outputText = root.optString("output_text")
            if (outputText.isNotEmpty() && outputText != "null") {
                return@runCatching outputText
            }

            "" // 如果都没有，返回空字符串
        }.getOrElse {
            // 兼容 3：如果传过来的根本不是 JSON，而是纯文本块，直接将 data 作为结果返回
            if (data != "null") data else ""
        }
    }

    private fun JSONObject.stringOrEmpty(name: String): String {
        if (!has(name) || isNull(name)) return ""
        val value = opt(name)
        return if (value is String && value != "null") value else ""
    }
}
