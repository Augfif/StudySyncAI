package com.example.temp.importer

sealed interface ImportState {
    data object Idle : ImportState
    data class Parsing(val receivedBytes: Int) : ImportState
    data class Success(
        val json: String,
        val courses: List<Course>
    ) : ImportState
    data class Error(val message: String) : ImportState
}
