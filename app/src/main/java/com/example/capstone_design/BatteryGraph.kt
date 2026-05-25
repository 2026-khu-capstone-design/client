package com.example.capstone_design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class BatteryDataPoint(
    val elapsedSeconds: Float,
    val level: Int,       // 배터리 잔량 (%)
    val currentMa: Float, // 전류 mA (절댓값)
    val powerMw: Float    // 전력 mW (절댓값)
)

private val CurrentOrange = Color(0xFFFF9800)
private val PowerRed      = Color(0xFFF44336)
private val GridColor     = Color(0xFFE0E0E0)

@Composable
fun BatteryGraph(
    dataPoints: List<BatteryDataPoint>,
    modifier: Modifier = Modifier
) {
    if (dataPoints.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().height(140.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "트래킹 시작 후 데이터가 수집됩니다",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
        return
    }

    val initialLevel    = dataPoints.first().level
    val currentLevel    = dataPoints.last().level
    val drainedPercent  = initialLevel - currentLevel
    val totalElapsedSec = dataPoints.last().elapsedSeconds
    val avgCurrentMa    = dataPoints.map { it.currentMa }.average().toFloat()
    val avgPowerMw      = dataPoints.map { it.powerMw }.average().toFloat()
    val peakCurrentMa   = dataPoints.maxOf { it.currentMa }
    val peakPowerMw     = dataPoints.maxOf { it.powerMw }

    // 전류·전력 각각 독립 min-max 스케일 계산
    // → 작은 변화량도 차트 전체 높이에 걸쳐 크게 표시됨
    val rawRangeCurrent = dataPoints.maxOf { it.currentMa } - dataPoints.minOf { it.currentMa }
    val rawRangePower   = dataPoints.maxOf { it.powerMw   } - dataPoints.minOf { it.powerMw   }
    val padCurrent = (if (rawRangeCurrent < 1f) dataPoints.maxOf { it.currentMa } * 0.1f else rawRangeCurrent * 0.08f)
    val padPower   = (if (rawRangePower   < 1f) dataPoints.maxOf { it.powerMw   } * 0.1f else rawRangePower   * 0.08f)
    val yMinCurrent = dataPoints.minOf { it.currentMa } - padCurrent
    val yMaxCurrent = dataPoints.maxOf { it.currentMa } + padCurrent
    val yMinPower   = dataPoints.minOf { it.powerMw   } - padPower
    val yMaxPower   = dataPoints.maxOf { it.powerMw   } + padPower

    val maxElapsed = dataPoints.last().elapsedSeconds.coerceAtLeast(10f)

    val density  = LocalDensity.current
    val paddingL = with(density) { 58.dp.toPx() }
    val paddingR = with(density) { 62.dp.toPx() }
    val paddingT = with(density) { 10.dp.toPx() }
    val paddingB = with(density) { 28.dp.toPx() }
    val tSize    = with(density) { 9.sp.toPx() }

    val leftPaint = remember(tSize) {
        android.graphics.Paint().apply {
            textAlign   = android.graphics.Paint.Align.RIGHT
            textSize    = tSize
            color       = CurrentOrange.toArgb()
            isAntiAlias = true
        }
    }
    val rightPaint = remember(tSize) {
        android.graphics.Paint().apply {
            textAlign   = android.graphics.Paint.Align.LEFT
            textSize    = tSize
            color       = PowerRed.toArgb()
            isAntiAlias = true
        }
    }
    val xPaint = remember(tSize) {
        android.graphics.Paint().apply {
            textAlign   = android.graphics.Paint.Align.CENTER
            textSize    = tSize
            color       = android.graphics.Color.GRAY
            isAntiAlias = true
        }
    }

    Column(modifier = modifier) {
        // 요약 행 1: 배터리 소모
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (drainedPercent > 0) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem("시작 배터리", "$initialLevel%", false)
            StatItem("현재 배터리", "$currentLevel%", false)
            StatItem("누적 소모", "$drainedPercent%", drainedPercent > 0)
            StatItem("경과 시간", formatElapsed(totalElapsedSec), false)
        }

        Spacer(Modifier.height(6.dp))

        // 요약 행 2: 전류/전력 통계
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem("평균 전류", "${"%.0f".format(avgCurrentMa)} mA", false)
            StatItem("최대 전류", "${"%.0f".format(peakCurrentMa)} mA", false)
            StatItem("평균 전력", "${"%.0f".format(avgPowerMw)} mW", false)
            StatItem("최대 전력", "${"%.0f".format(peakPowerMw)} mW", false)
        }

        Spacer(Modifier.height(8.dp))

        // 범례 (각 선이 독립 스케일임을 표시)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LegendDot(CurrentOrange, "전류 mA (좌축·독립스케일)")
            LegendDot(PowerRed, "전력 mW (우축·독립스케일)")
        }

        Spacer(Modifier.height(4.dp))

        Canvas(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            val cW = size.width - paddingL - paddingR
            val cH = size.height - paddingT - paddingB
            val nc = drawContext.canvas.nativeCanvas

            // 수평 그리드 + 좌축(전류)·우축(전력) 레이블
            repeat(6) { i ->
                val ratio = i / 5f
                val y = paddingT + cH * (1f - ratio)
                drawLine(GridColor, Offset(paddingL, y), Offset(paddingL + cW, y), 1f)
                nc.drawText(
                    "%.0f".format(yMinCurrent + (yMaxCurrent - yMinCurrent) * ratio),
                    paddingL - 8f, y + tSize / 2.5f, leftPaint
                )
                nc.drawText(
                    "%.0f".format(yMinPower + (yMaxPower - yMinPower) * ratio),
                    paddingL + cW + 8f, y + tSize / 2.5f, rightPaint
                )
            }

            // X축 시간 레이블
            repeat(6) { i ->
                val ratio = i / 5f
                val sec = maxElapsed * ratio
                val lbl = if (sec < 60f) "${"%.0f".format(sec)}s" else "${"%.1f".format(sec / 60)}m"
                nc.drawText(lbl, paddingL + cW * ratio, size.height - 6f, xPaint)
            }

            // 축 테두리
            drawLine(Color.Black.copy(alpha = 0.15f), Offset(paddingL, paddingT), Offset(paddingL, paddingT + cH), 2f)
            drawLine(Color.Black.copy(alpha = 0.15f), Offset(paddingL, paddingT + cH), Offset(paddingL + cW, paddingT + cH), 2f)
            drawLine(Color.Black.copy(alpha = 0.15f), Offset(paddingL + cW, paddingT), Offset(paddingL + cW, paddingT + cH), 2f)

            fun toX(e: Float) = paddingL + (e / maxElapsed) * cW
            // 각 선이 독립적으로 차트 높이를 꽉 채움
            fun toYCurrent(v: Float) = paddingT + cH * (1f - ((v - yMinCurrent) / (yMaxCurrent - yMinCurrent)).coerceIn(0f, 1f))
            fun toYPower(v: Float)   = paddingT + cH * (1f - ((v - yMinPower)   / (yMaxPower   - yMinPower  )).coerceIn(0f, 1f))

            drawChartLine(dataPoints, CurrentOrange, 2f,   { toX(it.elapsedSeconds) }, { toYCurrent(it.currentMa) })
            drawChartLine(dataPoints, PowerRed,      2.5f, { toX(it.elapsedSeconds) }, { toYPower(it.powerMw) })
        }
    }
}

private fun DrawScope.drawChartLine(
    points: List<BatteryDataPoint>,
    color: Color,
    sw: Float,
    toX: (BatteryDataPoint) -> Float,
    toY: (BatteryDataPoint) -> Float
) {
    if (points.size < 2) return
    val path = Path()
    path.moveTo(toX(points[0]), toY(points[0]))
    for (i in 1 until points.size) path.lineTo(toX(points[i]), toY(points[i]))
    drawPath(path, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
private fun StatItem(label: String, value: String, highlight: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun formatElapsed(sec: Float): String {
    val s = sec.toInt()
    return if (s < 60) "${s}초" else "${s / 60}분 ${s % 60}초"
}
