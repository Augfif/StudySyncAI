package com.example.temp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.temp.data.Course

private val timetableDays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val timetableSections = 1..12

@Composable
fun CourseTimetable(
    courses: List<Course>,
    modifier: Modifier = Modifier,
    onCourseClick: (Course) -> Unit = {},
    onExportClick: () -> Unit = {}
) {
    val coursesByCell = remember(courses) {
        timetableDays.indices.associate { dayIndex ->
            val day = dayIndex + 1
            day to timetableSections.associateWith { section ->
                courses
                    .filter { it.dayOfWeek == day && section in it.startPeriod..it.endPeriod }
                    .sortedWith(compareBy<Course> { it.startPeriod }
                        .thenBy { it.name }
                        .thenBy { it.weekRange })
            }
        }
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "课程表预览",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Button(onClick = onExportClick) {
                Text("导出日历")
            }
        }
        Text(
            text = "同一格内会按周次差异纵向堆叠，便于检查是否完整导入。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .verticalScroll(rememberScrollState())
        ) {
            Column {
                TimetableHeaderRow()
                timetableSections.forEach { section ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        SectionLabel(section = section)
                        timetableDays.indices.forEach { dayIndex ->
                            val day = dayIndex + 1
                            TimetableCell(
                                courses = coursesByCell[day]?.get(section).orEmpty(),
                                onCourseClick = onCourseClick
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimetableHeaderRow() {
    Row {
        Spacer(
            modifier = Modifier
                .width(40.dp)
                .height(36.dp)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
        )
        timetableDays.forEach { dayLabel ->
            Box(
                modifier = Modifier
                    .width(104.dp)
                    .height(36.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = dayLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(section: Int) {
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(96.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = section.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun TimetableCell(
    courses: List<Course>,
    onCourseClick: (Course) -> Unit
) {
    Column(
        modifier = Modifier
            .width(104.dp)
            .height(96.dp)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(3.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        courses.forEachIndexed { index, course ->
            CourseChip(
                course = course,
                color = courseColor(index),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clickable { onCourseClick(course) }
            )
        }
    }
}

@Composable
private fun CourseChip(
    course: Course,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(6.dp)),
        color = color,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp)) {
            Text(
                text = course.name,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = course.location,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${formatSections(course.startPeriod, course.endPeriod)} | ${course.weekRange}",
                fontSize = 9.sp,
                lineHeight = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun courseColor(index: Int): Color {
    val colors = listOf(
        Color(0xFFD7E3FF),
        Color(0xFFFFD8E4),
        Color(0xFFD8F3DC),
        Color(0xFFFFE8C2),
        Color(0xFFEADDFF)
    )
    return colors[index % colors.size]
}

private fun formatSections(startPeriod: Int, endPeriod: Int): String {
    return if (startPeriod == endPeriod) {
        "$startPeriod 节"
    } else {
        "$startPeriod-$endPeriod 节"
    }
}
