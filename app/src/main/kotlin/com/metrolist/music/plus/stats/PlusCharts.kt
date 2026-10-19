/**
 * Metrolist Plus — gráficas sencillas dibujadas con Canvas para Estadísticas Plus.
 */

package com.metrolist.music.plus.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val HOUR_MS = 3_600_000.0

/** Hours as a short label: "2.1k", "350", "4.5", "0". */
fun compactHours(ms: Long): String {
    val hours = ms / HOUR_MS
    return when {
        hours >= 10_000 -> "%.0fk".format(hours / 1000)
        hours >= 1_000 -> "%.1fk".format(hours / 1000)
        hours >= 10 -> "%.0f".format(hours)
        hours >= 0.05 -> "%.1f".format(hours)
        else -> "0"
    }
}

@Composable
fun seriesColors(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.tertiary,
    Color(0xFFE59A3C),
    Color(0xFF4FA3D9),
    MaterialTheme.colorScheme.error,
)

/**
 * Vertical bars. A tap selects a bar (shows its value); a tap on the selected bar calls [onOpen].
 */
@Composable
fun BarChart(
    bars: List<ChartBar>,
    onOpen: (ChartBar) -> Unit,
    modifier: Modifier = Modifier,
    valueText: (Long) -> String = ::compactHours,
) {
    if (bars.isEmpty()) return
    val barColor = MaterialTheme.colorScheme.primary
    val dimColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    var selected by remember(bars) { mutableIntStateOf(-1) }
    val max = bars.maxOf { it.ms }.coerceAtLeast(1)
    val maxIndex = bars.indexOfFirst { it.ms == max }
    val labelEvery = when {
        bars.size <= 13 -> 1
        bars.size <= 20 -> 2
        else -> 5
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(190.dp)
            .pointerInput(bars) {
                detectTapGestures { offset ->
                    val index = (offset.x / (size.width.toFloat() / bars.size)).toInt().coerceIn(0, bars.lastIndex)
                    if (index == selected && bars[index].drillTo != null) onOpen(bars[index]) else selected = index
                }
            },
    ) {
        val labelHeight = 18.dp.toPx()
        val valueHeight = 18.dp.toPx()
        val chartHeight = size.height - labelHeight - valueHeight
        val slot = size.width / bars.size
        val barWidth = (slot * 0.68f).coerceAtMost(28.dp.toPx())
        val radius = CornerRadius(barWidth / 3, barWidth / 3)
        val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
        val valueStyle = TextStyle(fontSize = 11.sp, color = valueColor)

        bars.forEachIndexed { index, bar ->
            val left = slot * index + (slot - barWidth) / 2
            drawRoundRect(trackColor, Offset(left, valueHeight), Size(barWidth, chartHeight), radius)
            val height = (bar.ms.toFloat() / max * chartHeight).coerceAtLeast(if (bar.ms > 0) 2.dp.toPx() else 0f)
            val highlighted = selected == -1 || selected == index
            drawRoundRect(
                if (highlighted) barColor else dimColor,
                Offset(left, valueHeight + chartHeight - height),
                Size(barWidth, height),
                radius,
            )

            if (index % labelEvery == 0 || index == selected) {
                val label = measurer.measure(bar.label, labelStyle)
                drawText(
                    label,
                    topLeft = Offset(
                        (slot * index + slot / 2 - label.size.width / 2).clampTo(size.width - label.size.width),
                        size.height - labelHeight + 3.dp.toPx(),
                    ),
                )
            }
            val showValue = if (selected >= 0) index == selected else bars.size <= 13 || index == maxIndex
            if (showValue && bar.ms > 0) {
                val value = measurer.measure(valueText(bar.ms), valueStyle)
                drawText(
                    value,
                    topLeft = Offset(
                        (slot * index + slot / 2 - value.size.width / 2).clampTo(size.width - value.size.width),
                        (valueHeight + chartHeight - height - value.size.height).coerceAtLeast(0f),
                    ),
                )
            }
        }
    }
}

/** 7 rows (Monday first) × 24 hours, darker = more listening. */
@Composable
fun HeatmapGrid(
    grid: Array<LongArray>,
    dayLabels: List<String>,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val max = grid.maxOf { row -> row.max() }.coerceAtLeast(1)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(200.dp),
    ) {
        val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
        val leftLabel = 22.dp.toPx()
        val bottomLabel = 16.dp.toPx()
        val gap = 2.dp.toPx()
        val cellWidth = (size.width - leftLabel) / 24
        val cellHeight = (size.height - bottomLabel) / 7
        val radius = CornerRadius(3.dp.toPx(), 3.dp.toPx())

        for (day in 0 until 7) {
            val label = measurer.measure(dayLabels.getOrElse(day) { "" }, labelStyle)
            drawText(label, topLeft = Offset(0f, day * cellHeight + (cellHeight - label.size.height) / 2))
            for (hour in 0 until 24) {
                val value = grid[day][hour]
                val fraction = value.toFloat() / max
                drawRoundRect(
                    if (value == 0L) empty else color.copy(alpha = 0.12f + 0.88f * fraction),
                    Offset(leftLabel + hour * cellWidth + gap / 2, day * cellHeight + gap / 2),
                    Size(cellWidth - gap, cellHeight - gap),
                    radius,
                )
            }
        }
        listOf(0, 6, 12, 18, 23).forEach { hour ->
            val label = measurer.measure(hour.toString(), labelStyle)
            drawText(
                label,
                topLeft = Offset(
                    leftLabel + hour * cellWidth + (cellWidth - label.size.width) / 2,
                    size.height - bottomLabel + 2.dp.toPx(),
                ),
            )
        }
    }
}

/** Several lines over the same x labels, with a legend. */
@Composable
fun LineChart(
    series: List<Pair<String, List<Long>>>,
    xLabels: List<String>,
    modifier: Modifier = Modifier,
) {
    if (series.isEmpty() || xLabels.size < 2) return
    val colors = seriesColors()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val max = series.maxOf { (_, values) -> values.maxOrNull() ?: 0L }.coerceAtLeast(1)
    val labelEvery = if (xLabels.size <= 8) 1 else 2

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp),
        ) {
            val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
            val bottomLabel = 16.dp.toPx()
            val chartHeight = size.height - bottomLabel - 6.dp.toPx()
            val step = size.width / (xLabels.size - 1)

            for (i in 0..3) {
                val y = 6.dp.toPx() + chartHeight * i / 3
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            series.forEachIndexed { index, (_, values) ->
                val path = Path()
                values.forEachIndexed { i, value ->
                    val point = Offset(step * i, 6.dp.toPx() + chartHeight * (1 - value.toFloat() / max))
                    if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                drawPath(
                    path,
                    colors[index % colors.size],
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            xLabels.forEachIndexed { i, text ->
                if (i % labelEvery != 0 && i != xLabels.lastIndex) return@forEachIndexed
                val label = measurer.measure(text, labelStyle)
                drawText(
                    label,
                    topLeft = Offset(
                        (step * i - label.size.width / 2).clampTo(size.width - label.size.width),
                        size.height - bottomLabel + 2.dp.toPx(),
                    ),
                )
            }
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            series.forEachIndexed { index, (name, _) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(colors[index % colors.size]),
                    )
                    Text(
                        name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** Label, value and a bar proportional to the largest value. */
@Composable
fun HorizontalBars(
    items: List<Pair<String, Long>>,
    valueText: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val max = items.maxOf { it.second }.coerceAtLeast(1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { (label, value) ->
            Column {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(valueText(value), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ProportionBar(value.toFloat() / max, Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
fun ProportionBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

private fun Float.clampTo(max: Float): Float = coerceAtMost(max).coerceAtLeast(0f)
