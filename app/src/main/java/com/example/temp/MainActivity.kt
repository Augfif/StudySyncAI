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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.temp.importer.CourseImportViewModel
import com.example.temp.importer.ImportState
import com.example.temp.importer.LlmApiConfig
import com.example.temp.importer.validateCourses
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
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.addJavascriptInterface(
            ScheduleJavascriptInterface(::onScheduleParsed),
            SCHEDULE_BRIDGE_NAME
        )
        webView.webViewClient = WebViewClient()
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
        private const val EDUCATION_SYSTEM_URL = "about:blank"

        private val LLM_CONFIG = LlmApiConfig(
            endpoint = "https://api.example.com/v1/chat/completions",
            apiKey = "YOUR_API_KEY",
            model = "YOUR_MODEL"
        )
    }
}

@Composable
private fun ScheduleImportScreen(
    viewModel: CourseImportViewModel,
    setupWebView: (WebView) -> Unit,
    injectScheduleParser: (WebView) -> Unit,
    educationSystemUrl: String,
    modifier: Modifier = Modifier
) {
    val importState by viewModel.importState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val webViewState = remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(importState) {
        when (val state = importState) {
            is ImportState.Success -> {
                val courses = validateCourses(state.json)
                Log.d("ScheduleImport", "valid course count: ${courses.size}")
                snackbarHostState.showSnackbar("导入成功，解析到 ${courses.size} 条课程")
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { webViewState.value?.let(injectScheduleParser) },
                text = { Text("提取课表") }
            )
        }
    ) { innerPadding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            factory = { context ->
                WebView(context).apply {
                    setupWebView(this)
                    loadUrl(educationSystemUrl)
                    webViewState.value = this
                }
            }
        )
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