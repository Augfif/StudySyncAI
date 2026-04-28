package com.example.temp.importer

import com.google.gson.annotations.SerializedName

data class Course(
    @SerializedName("name")
    val name: String,
    @SerializedName("position")
    val position: String,
    @SerializedName("teacher")
    val teacher: String,
    @SerializedName("weeks")
    val weeks: List<Int>,
    @SerializedName("day")
    val day: Int,
    @SerializedName("sections")
    val sections: List<Int>
)
