package com.motionextraction.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

var appEnglish by mutableStateOf(false)

fun tr(bg: String, en: String): String = if (appEnglish) en else bg

@Composable
fun LanguageFlag() {
    Canvas(
        modifier = Modifier
            .clickable { appEnglish = !appEnglish }
            .padding(vertical = 8.dp)
            .size(width = 30.dp, height = 18.dp),
    ) {
        if (appEnglish) {
            drawUnionJack()
        } else {
            val band = size.height / 3f
            drawRect(Color.White, size = Size(size.width, band))
            drawRect(Color(0xFF00966E), topLeft = Offset(0f, band), size = Size(size.width, band))
            drawRect(Color(0xFFD62612), topLeft = Offset(0f, band * 2f), size = Size(size.width, band))
        }
        drawRect(Color(0xFF3A4250), style = Stroke(width = 1.dp.toPx()))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUnionJack() {
    val width = size.width
    val height = size.height
    drawRect(Color(0xFF012169))
    val white = Color.White
    val red = Color(0xFFC8102E)
    val whiteSaltire = height * 0.22f
    val redSaltire = height * 0.09f
    drawLine(white, Offset(0f, 0f), Offset(width, height), whiteSaltire, StrokeCap.Butt)
    drawLine(white, Offset(width, 0f), Offset(0f, height), whiteSaltire, StrokeCap.Butt)
    drawLine(red, Offset(0f, 0f), Offset(width, height), redSaltire, StrokeCap.Butt)
    drawLine(red, Offset(width, 0f), Offset(0f, height), redSaltire, StrokeCap.Butt)
    val whiteBar = height * 0.34f
    val redBar = height * 0.18f
    drawRect(white, topLeft = Offset((width - whiteBar) / 2f, 0f), size = Size(whiteBar, height))
    drawRect(white, topLeft = Offset(0f, (height - whiteBar) / 2f), size = Size(width, whiteBar))
    drawRect(red, topLeft = Offset((width - redBar) / 2f, 0f), size = Size(redBar, height))
    drawRect(red, topLeft = Offset(0f, (height - redBar) / 2f), size = Size(width, redBar))
}
