package com.example.temp

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.temp.importer.Course
import com.example.temp.importer.CourseImportViewModel
import com.example.temp.importer.ImportState
import com.example.temp.importer.LlmApiConfig
import com.example.temp.ui.theme.TempTheme
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val courseImportViewModel: CourseImportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TempTheme {
                ScheduleImportScreen(
                    viewModel = courseImportViewModel,
                    setupWebView = ::setupWebView,
                    injectScheduleParser = ::injectScheduleParser,
                    educationSystemUrl = EDUCATION_SYSTEM_URL,
                    onCourseClick = { course ->
                        Log.d(TAG, "course clicked: ${course.name}")
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        // 1. 允许 HTTP 和 HTTPS 混合加载（解决绝大部分白屏问题）
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // 2. 扩大页面排版与 JS 兼容支持
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.databaseEnabled = true

        // 3. 强制允许所有的 Cookie（包括第三方跨域 Cookie），防止登录状态丢失
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(
            ScheduleJavascriptInterface(::onScheduleParsed),
            SCHEDULE_BRIDGE_NAME
        )

        // 4. 重写 WebViewClient，忽略学校教务系统可能过期的 SSL 证书错误
        webView.webViewClient = object : WebViewClient() {
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                // 默认行为是 handler?.cancel()，也就是白屏。我们改成 proceed() 忽略错误继续加载
                handler?.proceed()
            }
        }

        // 5. 加上 WebChromeClient，支持老式教务系统的 alert 弹窗和 window.open 机制
        webView.webChromeClient = android.webkit.WebChromeClient()
    }
    private fun injectScheduleParser(webView: WebView) {
        val script = assets.open(SCHEDULE_PARSE_ASSET).bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }

        webView.evaluateJavascript(script) { rawResult ->
            Log.d(TAG, "evaluateJavascript result: $rawResult")
        }
    }

    private fun onScheduleParsed(json: String) {
        Log.d(TAG, "schedule parse result: $json")
        runOnUiThread {
            runCatching {
                val result = JSONObject(json)
                if (!result.optBoolean("success")) {
                    val message = result.optString("message", "Schedule DOM parse failed.")
                    Log.e(TAG, "Schedule DOM parse failed: $message")
                    courseImportViewModel.reportError(message)
                    return@runCatching
                }

                courseImportViewModel.importScheduleByAi(
                    config = LLM_CONFIG,
                    html = result.optString("html"),
                    rawText = result.optString("text")
                )
            }.onFailure { throwable ->
                Log.e(TAG, "Failed to handle schedule parse result", throwable)
                courseImportViewModel.reportError(
                    throwable.message ?: "Failed to handle schedule parse result."
                )
            }
        }
    }

    private class ScheduleJavascriptInterface(
        private val onParsed: (String) -> Unit
    ) {
        @JavascriptInterface
        fun onScheduleParsed(json: String) {
            onParsed(json)
        }
    }

    private companion object {
        private const val TAG = "ScheduleImport"
        private const val SCHEDULE_BRIDGE_NAME = "ScheduleBridge"
        private const val SCHEDULE_PARSE_ASSET = "schedule_parse.js"
        private const val EDUCATION_SYSTEM_URL = "https://jwgl.hbmzu.edu.cn/edu"

        private val LLM_CONFIG = LlmApiConfig(
            endpoint = "https://api-ai.vivo.com.cn/v1/chat/completions",
            apiKey = "sk-xuanji-2026584001-Q1FvQkdOZGhPTHluSWlDUg==",
            model = "Doubao-Seed-2.0-pro"
        )
    }
}


@Composable
private fun ScheduleImportScreen(
    viewModel: CourseImportViewModel,
    setupWebView: (WebView) -> Unit,
    injectScheduleParser: (WebView) -> Unit,
    educationSystemUrl: String,
    onCourseClick: (Course) -> Unit,
    modifier: Modifier = Modifier
) {
    val importState by viewModel.importState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val webViewState = remember { mutableStateOf<WebView?>(null) }
    val importedCourses = remember(importState) {
        when (val state = importState) {
            is ImportState.Success -> state.courses
            else -> emptyList()
        }
    }

    // 新增：用于保存输入框里的网址状态
    var urlInput by remember { mutableStateOf(educationSystemUrl.takeIf { it != "about:blank" } ?: "https://") }

    LaunchedEffect(importState) {
        when (val state = importState) {
            is ImportState.Success -> {
                Log.d("ScheduleImport", "valid course count: ${importedCourses.size}")
                snackbarHostState.showSnackbar("导入成功，解析到 ${importedCourses.size} 条课程")
            }

            is ImportState.Error -> {
                snackbarHostState.showSnackbar("导入失败：${state.message}")
            }

            ImportState.Idle,
            is ImportState.Parsing -> Unit
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewState.value?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
            webViewState.value = null
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 新增：在顶部增加一个输入栏
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("教务系统网址") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        // 点击前往时，让 WebView 加载输入的网址
                        var finalUrl = urlInput
                        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
                            finalUrl = "http://$finalUrl"
                            urlInput = finalUrl
                        }
                        webViewState.value?.loadUrl(finalUrl)
                    }
                ) {
                    Text("前往")
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { webViewState.value?.let(injectScheduleParser) }
            ) {
                Text("提取课表")
            }
        }
    ) { innerPadding ->
        if (importState is ImportState.Success) {
            CourseTimetable(
                courses = importedCourses,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onCourseClick = onCourseClick
            )
        } else {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                factory = { context ->
                    WebView(context).apply {
                        // 新增：强制指定 WebView 的 LayoutParams，解决网页无法滑动的问题
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        setupWebView(this)
                        // 初始化加载（如果不是 about:blank 就加载）
                        if (educationSystemUrl != "about:blank") {
                            loadUrl(educationSystemUrl)
                        }
                        webViewState.value = this
                    }
                }
            )
        }
    }

    if (importState is ImportState.Parsing) {
        val parsing = importState as ImportState.Parsing
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("正在解析课程表") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("已接收 ${parsing.receivedBytes} 字节")
                }
            }
        )
    }
}
@Preview(showBackground = true)
@Composable
private fun ScheduleImportScreenPreview() {
    TempTheme {
        Text("Schedule import screen")
    }
}