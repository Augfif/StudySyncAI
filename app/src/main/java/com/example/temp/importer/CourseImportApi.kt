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

data class LlmApiConfig(
    val endpoint: String,
    val apiKey: String,
    val model: String
)

class CourseImportApi(
    okHttpClient: OkHttpClient = OkHttpClient()
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
                JSONArray().put(
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
            val choices = root.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val first = choices.optJSONObject(0)
                val delta = first?.optJSONObject("delta")
                val content = delta?.optString("content")
                if (!content.isNullOrEmpty()) {
                    return@runCatching content
                }
                val message = first?.optJSONObject("message")?.optString("content")
                if (!message.isNullOrEmpty()) {
                    return@runCatching message
                }
            }
            root.optString("output_text", "")
        }.getOrElse {
            // Some providers stream plain text chunks instead of JSON payloads.
            data
        }
    }
}
