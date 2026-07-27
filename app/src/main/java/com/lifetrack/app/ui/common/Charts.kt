package com.lifetrack.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt

fun parseColor(hex: String?): Color =
    runCatching { Color((hex ?: "#78909C").toColorInt()) }.getOrDefault(Color.Gray)

data class PieSlice(val label: String, val value: Double, val color: Color)

@Composable
fun DonutChart(slices: List<PieSlice>, modifier: Modifier = Modifier) {
    val total = slices.sumOf { it.value }.takeIf { it > 0 } ?: return
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(16.dp)
    ) {
        val stroke = Stroke(width = 48f, cap = StrokeCap.Butt)
        val diameter = minOf(size.width, size.height)
        val topLeft = Offset((size.width - diameter) / 2 + 24f, (size.height - diameter) / 2 + 24f)
        val arcSize = Size(diameter - 48f, diameter - 48f)
        var startAngle = -90f
        slices.forEach { s ->
            val sweep = (s.value / total * 360f).toFloat()
            drawArc(
                color = s.color, startAngle = startAngle, sweepAngle = sweep,
                useCenter = false, topLeft = topLeft, size = arcSize, style = stroke
            )
            startAngle += sweep
        }
    }
}

data class Bar(val label: String, val value: Double)

@Composable
fun BarChart(bars: List<Bar>, color: Color, modifier: Modifier = Modifier) {
    if (bars.isEmpty()) return
    val max = bars.maxOf { it.value }.takeIf { it > 0 } ?: return
    Column(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .padding(horizontal = 16.dp)
        ) {
            val gap = 8f
            val barWidth = (size.width - gap * (bars.size - 1)) / bars.size
            bars.forEachIndexed { i, bar ->
                val h = (bar.value / max * size.height).toFloat()
                drawRoundRect(
                    color = color,
                    topLeft = Offset(i * (barWidth + gap), size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 3, barWidth / 3)
                )
            }
        }
    }
}

/** Simple polyline chart for progress over time. */
@Composable
fun LineChart(points: List<Double>, color: Color, modifier: Modifier = Modifier) {
    if (points.size < 2) return
    val max = points.max().takeIf { it > 0 } ?: return
    val min = points.min()
    val range = (max - min).takeIf { it > 0 } ?: 1.0
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .padding(16.dp)
    ) {
        val stepX = size.width / (points.size - 1)
        var prev = Offset(0f, (size.height * (1 - (points[0] - min) / range)).toFloat())
        points.drop(1).forEachIndexed { i, p ->
            val cur = Offset((i + 1) * stepX, (size.height * (1 - (p - min) / range)).toFloat())
            drawLine(color = color, start = prev, end = cur, strokeWidth = 6f, cap = StrokeCap.Round)
            prev = cur
        }
    }
}
