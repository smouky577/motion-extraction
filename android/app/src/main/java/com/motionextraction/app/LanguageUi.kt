package com.motionextraction.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

var appEnglish by mutableStateOf(false)

fun tr(bg: String, en: String): String = if (appEnglish) en else bg

@Composable
fun LanguageFlag(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { appEnglish = !appEnglish }
            .padding(end = 8.dp)
            .size(width = 30.dp, height = 18.dp),
    ) {
        Canvas(Modifier.matchParentSize()) {
            if (appEnglish) drawUnionJack() else drawBulgaria()
        }
    }
}

private fun DrawScope.drawBulgaria() {
    val band = size.height / 3f
    drawRect(Color.White, size = Size(size.width, band))
    drawRect(Color(0xFF00966E), topLeft = Offset(0f, band), size = Size(size.width, band))
    drawRect(Color(0xFFD62612), topLeft = Offset(0f, band * 2f), size = Size(size.width, band))
}

private fun DrawScope.drawUnionJack() {
    val width = size.width
    val height = size.height
    val red = Color(0xFFC8102E)
    drawRect(Color(0xFF012169))
    drawLine(Color.White, Offset(0f, 0f), Offset(width, height), height * 0.22f, cap = StrokeCap.Square)
    drawLine(Color.White, Offset(width, 0f), Offset(0f, height), height * 0.22f, cap = StrokeCap.Square)
    drawLine(red, Offset(0f, height * 0.08f), Offset(width, height + height * 0.08f), height * 0.08f, cap = StrokeCap.Square)
    drawLine(red, Offset(width, height * 0.08f), Offset(0f, height + height * 0.08f), height * 0.08f, cap = StrokeCap.Square)
    val arm = width * 0.16f
    val bar = height * 0.28f
    drawRect(Color.White, topLeft = Offset((width - arm) / 2f, 0f), size = Size(arm, height))
    drawRect(Color.White, topLeft = Offset(0f, (height - bar) / 2f), size = Size(width, bar))
    val redArm = arm * 0.56f
    val redBar = bar * 0.56f
    drawRect(red, topLeft = Offset((width - redArm) / 2f, 0f), size = Size(redArm, height))
    drawRect(red, topLeft = Offset(0f, (height - redBar) / 2f), size = Size(width, redBar))
}
