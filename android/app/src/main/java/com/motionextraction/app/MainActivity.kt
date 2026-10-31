package com.motionextraction.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

private val Bg = Color(0xFF10120F)
private val Panel = Color(0xFF242824)
private val Accent = Color(0xFFD6F25C)
private val Ink = Color(0xFF16180F)
private val Muted = Color(0xFF9AA194)
private val Warm = Color(0xFFFFB03A)
private val Record = Color(0xFFFF4D3A)

private val swaps = listOf(
    "none" to "Оригинал",
    "rb" to "Червено ↔ синьо",
    "rg" to "Червено ↔ зелено",
    "gb" to "Зелено ↔ синьо",
    "cycle_fwd" to "Цикъл напред",
    "cycle_back" to "Цикъл назад",
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, primary = Accent)) {
                MotionScreen()
            }
        }
    }
}

@Composable
private fun MotionScreen() {
    val context = LocalContext.current
    val runtime = remember { MotionRuntime(context.applicationContext) }
    DisposableEffect(runtime) {
        onDispose { runtime.release() }
    }
    val openVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runtime.openVideo(uri)
    }
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runtime.openCamera()
    }
    var channels by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var colorsOpen by remember { mutableStateOf(false) }
    var delay by remember { mutableIntStateOf(1) }
    var blur by remember { mutableIntStateOf(0) }
    var glow by remember { mutableStateOf(false) }
    var swap by remember { mutableStateOf("none") }
    var offR by remember { mutableIntStateOf(0) }
    var offG by remember { mutableIntStateOf(0) }
    var offB by remember { mutableIntStateOf(0) }
    val span = runtime.span
    fun push(
        nextDelay: Int = delay,
        nextBlur: Int = blur,
        nextGlow: Boolean = glow,
        nextSwap: String = swap,
        nextR: Int = offR,
        nextG: Int = offG,
        nextB: Int = offB,
    ) {
        runtime.settings = MotionSettings(nextDelay, nextR, nextG, nextB, nextBlur, nextGlow, nextSwap, span)
    }
    val scroll = rememberScrollState()
    var controlsHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
    ) {
        if (runtime.mode != "live") {
            Column(modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Motion Extraction", color = Accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(
                        "by Dr. Baysinger",
                        color = Muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier.clickable { about = true },
                    )
                }
                Text(
                    "инверсия   ·   50%   ·   изместване във времето",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
        val frame = runtime.bitmap
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (frame != null) {
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = "Движение",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    "Отвори клип, камера или демо\nНеподвижното става сиво",
                    color = Muted,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (channels && controlsHeight > 0) {
                        Modifier.height(with(density) { controlsHeight.toDp() })
                    } else {
                        Modifier
                    }
                )
                .onSizeChanged { size ->
                    if (!channels) controlsHeight = size.height
                }
                .then(if (channels) Modifier else Modifier.verticalScroll(scroll))
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = if (channels) 8.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        if (!channels && runtime.mode == "file" && runtime.position >= 0f) {
            Slider(
                value = runtime.position.coerceIn(0f, 1f),
                onValueChange = { runtime.seek(it) },
            )
        }
        if (!channels) Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Отвори", Accent, Ink, runtime.exporting.not(), Modifier.weight(1f)) { openVideo.launch("video/*") }
                ActionButton(
                    if (runtime.mode == "live") "Спри" else "Камера",
                    if (runtime.mode == "live") Accent else Panel,
                    if (runtime.mode == "live") Ink else Color.White,
                    !runtime.exporting,
                    Modifier.weight(1f),
                ) {
                    if (runtime.mode == "live") {
                        runtime.stop()
                    } else if (
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        runtime.openCamera()
                    } else {
                        requestCamera.launch(Manifest.permission.CAMERA)
                    }
                }
                ActionButton(
                    if (runtime.mode == "demo") "Спри" else "Демо",
                    if (runtime.mode == "demo") Accent else Panel,
                    if (runtime.mode == "demo") Ink else Color.White,
                    !runtime.exporting,
                    Modifier.weight(1f),
                ) {
                    if (runtime.mode == "demo") runtime.stop() else runtime.openDemo()
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    if (runtime.playing && runtime.mode == "file") "Пауза" else "Пусни",
                    Panel,
                    Color.White,
                    runtime.mode == "file" && !runtime.exporting,
                    Modifier.weight(1f),
                ) { runtime.togglePlay() }
                ActionButton(
                    "Експорт",
                    Panel,
                    Color.White,
                    runtime.mode == "file" && !runtime.exporting && !runtime.recording,
                    Modifier.weight(1f),
                ) {
                    runtime.export()
                }
                ActionButton(
                    if (runtime.recording) "Спри записа" else "Запис",
                    if (runtime.recording) Record else Panel,
                    Color.White,
                    (runtime.mode == "live" || runtime.mode == "demo") && !runtime.exporting,
                    Modifier.weight(1f),
                ) { runtime.toggleRecord() }
            }
        }
        if (!channels) LabeledSlider(
            title = "Изместване",
            value = delay.toFloat(),
            range = 1f..span.toFloat(),
            steps = (span - 2).coerceAtLeast(0),
            caption = frameCaption(delay, span),
        ) {
            delay = it.roundToInt().coerceIn(1, span)
            push(nextDelay = delay)
        }
        if (!channels) LabeledSlider(
            title = "Блур за детайл",
            value = blur.toFloat(),
            range = 0f..24f,
            steps = 23,
            caption = if (blur <= 0) "дребен детайл" else "блур $blur  ·  по-едър",
        ) {
            blur = it.roundToInt().coerceIn(0, 24)
            push(nextBlur = blur)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionButton(
                if (glow) "Сияние ВКЛ" else "Сияние",
                if (glow) Warm else Panel,
                if (glow) Ink else Color.White,
                true,
                Modifier.weight(1f),
            ) {
                glow = !glow
                push(nextGlow = glow)
            }
            Box(modifier = Modifier.weight(1f)) {
                ActionButton("Цветове", Panel, Color.White, true, Modifier.fillMaxWidth()) { colorsOpen = true }
                DropdownMenu(expanded = colorsOpen, onDismissRequest = { colorsOpen = false }) {
                    swaps.forEach { (key, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                swap = key
                                push(nextSwap = key)
                                colorsOpen = false
                            },
                        )
                    }
                }
            }
            ActionButton(
                "Канали",
                if (channels) Accent else Panel,
                if (channels) Ink else Color.White,
                true,
                Modifier.weight(1f),
            ) { channels = !channels }
        }
        if (channels) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                ChannelSlider("Червено", Color(0xFFE25B4A), offR, delay, span) {
                    offR = it
                    push(nextR = it)
                }
                ChannelSlider("Зелено", Color(0xFF8DCE78), offG, delay, span) {
                    offG = it
                    push(nextG = it)
                }
                ChannelSlider("Синьо", Color(0xFF7EB0E8), offB, delay, span) {
                    offB = it
                    push(nextB = it)
                }
            }
        }
        }
        if (about) {
            AboutDialog { about = false }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    background: Color,
    foreground: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(30.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = background,
            contentColor = foreground,
            disabledContainerColor = Panel,
            disabledContentColor = Muted,
        ),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(text, fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun LabeledSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    caption: String,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = Muted)
            Text(caption, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.End)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier
                .padding(horizontal = sliderSideInset())
                .pullUp(10.dp),
        )
    }
}

@Composable
private fun ChannelSlider(name: String, color: Color, value: Int, delay: Int, span: Int, onChange: (Int) -> Unit) {
    val total = (delay + value).coerceIn(0, span * 2)
    Text("$name    ${value.signed()}    общо $total к", color = color, fontSize = 13.sp)
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.roundToInt().coerceIn(-span, span)) },
        valueRange = -span.toFloat()..span.toFloat(),
        steps = (span * 2 - 1).coerceAtLeast(0),
        modifier = Modifier.padding(horizontal = sliderSideInset()),
    )
}

@Composable
private fun sliderSideInset(): Dp {
    val prefix = rememberTextMeasurer().measure(text = "Из", style = LocalTextStyle.current).size.width
    return with(LocalDensity.current) { prefix.toDp() }
}

private fun frameCaption(delay: Int, span: Int): String {
    val seconds = delay.toFloat() / span.coerceAtLeast(1)
    val word = if (delay == 1) "кадър" else "кадъра"
    return "$delay $word   ·   ${"%.2f".format(seconds)} сек"
}

private fun Int.signed(): String = if (this > 0) "+$this" else toString()

private const val ABOUT_EN = "Donate to Dr. Baysinger. He promises to spend it on hookers and beer."
private const val ABOUT_BG = "Дарете на Dr. Baysinger. Той обещава да ги изхарчи за курви и бело."
private const val REVOLUT = "revolut.me/georgi09qc"
private val BTC = listOf(
    "bc1qr84akpfry7sjs6759wwq722fyert9apcarpst6",
    "bc1p6hp5q20u9t9upsjt7425wgqdrs0cr8wnnlf9atu24z92pzsnq85q62uc8e",
)

@Composable
private fun AboutDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = onClose) { Text("OK", color = Accent) }
        },
        title = { Text("About", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(ABOUT_EN)
                Text(ABOUT_BG, modifier = Modifier.padding(top = 12.dp))
                Text("Revolut", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
                CopyLine(REVOLUT, context)
                Text("BTC address", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                BTC.forEach { CopyLine(it, context) }
            }
        },
    )
}

@Composable
private fun CopyLine(value: String, context: Context) {
    Text(
        value,
        color = Accent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("address", value))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
            .padding(vertical = 6.dp),
    )
}

private fun Modifier.pullUp(by: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val lift = by.roundToPx().coerceAtMost(placeable.height)
    layout(placeable.width, placeable.height - lift) {
        placeable.place(0, -lift)
    }
}

private fun Modifier.fullDisplayWidth(horizontalInset: Dp): Modifier = layout { measurable, constraints ->
    val extra = horizontalInset.roundToPx()
    val width = constraints.maxWidth + extra * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) {
        placeable.place(-extra, 0)
    }
}
