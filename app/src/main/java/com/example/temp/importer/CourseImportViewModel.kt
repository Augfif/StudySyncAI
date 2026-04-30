package com.example.temp.importer

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CourseImportViewModel(
    private val api: CourseImportApi = CourseImportApi(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    constructor() : this(CourseImportApi(), Dispatchers.IO)

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    fun importScheduleByAi(
        config: LlmApiConfig,
        html: String,
        rawText: String
    ) {
        viewModelScope.launch {
            _importState.value = ImportState.Parsing(receivedBytes = 0)

            runCatching {
                withContext(ioDispatcher) {
                    api.parseScheduleWithSse(
                        config = config,
                        html = html,
                        rawText = rawText
                    ) { bytes ->
                        _importState.value = ImportState.Parsing(receivedBytes = bytes)
                    }
                }
            }.onSuccess { rawResult ->
                // ===== 新增这两行日志 =====
                Log.d("ScheduleImport", "大模型原始返回: \n$rawResult")

                val cleanJson = cleanMarkdownJson(rawResult)

                Log.d("ScheduleImport", "清洗后的 JSON: \n$cleanJson")
                // ==========================

                _importState.value = ImportState.Success(
                    json = cleanJson,
                    courses = validateCourses(cleanJson)
                )
            }.onFailure { throwable ->
                _importState.value = ImportState.Error(
                    throwable.message ?: "Unknown parsing error."
                )
            }
        }
    }

    fun resetState() {
        _importState.value = ImportState.Idle
    }

    fun reportError(message: String) {
        _importState.value = ImportState.Error(message)
    }

    private fun cleanMarkdownJson(input: String): String {
        var cleaned = input.trim().removePrefix("\uFEFF")

        val fencedRegex = Regex(
            pattern = "```(?:json|JSON)?\\s*([\\s\\S]*?)\\s*```",
            option = RegexOption.MULTILINE
        )
        val fencedMatch = fencedRegex.find(cleaned)
        if (fencedMatch != null && fencedMatch.groupValues.size > 1) {
            cleaned = fencedMatch.groupValues[1].trim()
        } else {
            cleaned = cleaned
                .replace(Regex("^```(?:json|JSON)?\\s*"), "")
                .replace(Regex("\\s*```$"), "")
                .trim()
        }

        cleaned = cleaned
            .replace(Regex("^json\\s*`*\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("`+$"), "")
            .trim()

        val objectStart = cleaned.indexOf('{').takeIf { it >= 0 } ?: Int.MAX_VALUE
        val arrayStart = cleaned.indexOf('[').takeIf { it >= 0 } ?: Int.MAX_VALUE
        val startIndex = minOf(objectStart, arrayStart)
        val endIndex = maxOf(cleaned.lastIndexOf('}'), cleaned.lastIndexOf(']'))

        if (startIndex != Int.MAX_VALUE && endIndex > startIndex) {
            cleaned = cleaned.substring(startIndex, endIndex + 1).trim()
        }

        return cleaned
    }
}
