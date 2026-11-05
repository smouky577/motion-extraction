package com.temporalfpt.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

private val Bg = Color(0xFF10120F)
private val Panel = Color(0xFF242824)
private val Accent = Color(0xFFD6F25C)
private val Ink = Color(0xFF16180F)
private val Muted = Color(0xFF9AA194)

private val modes = listOf(
    0 to "Бързи",
    1 to "Бавни",
    2 to "Лента",
    3 to "Спектър",
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, primary = Accent)) {
                FftScreen(
                    onBusy = { busy ->
                        if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    },
                )
            }
        }
    }
}

class FftSession(private val context: android.content.Context) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "temporal-fft").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val gate = ReentrantLock()
    @Volatile var cancel = false
    @Volatile private var closed = false
    private var active = 0L
    private var ready = false
    var width = 0
    var height = 0
    var outFrames = 0
    var fps = 30f

    fun start(
        uri: Uri,
        mode: Int,
        cutoff: Float,
        low: Float,
        high: Float,
        gain: Float,
        onProgress: (Float) -> Unit,
        onReady: (Int, Int, Int) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        cancel = false
        executor.execute {
            var handle = 0L
            var decoder: VideoDecoder? = null
            var encoder: Mp4Encoder? = null
            try {
                val check = NativeFilter.selfTest()
                if (check != 0) error("Проверката на сметката не мина ($check).")
                decoder = VideoDecoder(context, uri)
                val clipFps = decoder.fps
                val maxFrames = (clipFps * MAX_SECONDS).toInt().coerceIn(4, 720)
                var stored = 0
                var streak = 0
                var frameW = 0
                var frameH = 0
                while (stored < maxFrames && !cancel) {
                    when (val item = decoder.next()) {
                        is Decoded.Frame -> {
                            streak = 0
                            var frame = scaleMaxWidth(rotateArgb(item.raw, decoder.rotation), MAX_WIDTH)
                            if (handle == 0L) {
                                frameW = frame.width
                                frameH = frame.height
                                handle = NativeFilter.create(frameW, frameH, maxFrames)
                                if (handle == 0L) error("Няма достатъчно памет за този клип.")
                            } else if (frame.width != frameW || frame.height != frameH) {
                                frame = fitFrame(frame, frameW, frameH)
                            }
                            NativeFilter.push(handle, frame.pixels)
                            stored++
                            val fraction = 0.2f * stored / maxFrames
                            main.post { onProgress(fraction) }
                        }
                        Decoded.Pending -> {
                            streak++
                            if (streak > 80) error("Клипът спря да се чете.")
                        }
                        Decoded.End -> break
                    }
                }
                decoder.close()
                decoder = null
                if (cancel) {
                    main.post { onDone("Спряно.") }
                    return@execute
                }
                if (stored < 4) error("Твърде малко кадри.")
                val count = NativeFilter.process(handle, clipFps, mode, cutoff, low, high, gain) { done, total ->
                    if (total > 0) {
                        val fraction = 0.2f + 0.65f * done / total
                        main.post { onProgress(fraction) }
                    }
                    cancel
                }
                if (cancel || count == -5) {
                    main.post { onDone("Спряно.") }
                    return@execute
                }
                if (count < 0) {
                    error(
                        when (count) {
                            -2 -> "Няма достатъчно памет за този клип."
                            -3 -> "Твърде малко кадри."
                            else -> "Филтърът спря с код $count."
                        },
                    )
                }
                var accepted = false
                gate.withLock {
                    if (closed) return@withLock
                    val previous = active
                    active = handle
                    width = frameW
                    height = frameH
                    outFrames = count
                    fps = clipFps
                    ready = true
                    handle = 0L
                    accepted = true
                    if (previous != 0L) NativeFilter.destroy(previous)
                }
                if (!accepted) {
                    main.post { onDone("Спряно.") }
                    return@execute
                }
                val shown = outFrames
                main.post { onReady(shown, frameW, frameH) }
                val pixels = IntArray(frameW * frameH)
                val writer = Mp4Encoder(context, frameW, frameH, clipFps)
                encoder = writer
                writer.start()
                for (index in 0 until shown) {
                    if (cancel) break
                    gate.withLock {
                        if (active != 0L) NativeFilter.copyFrame(active, index, pixels)
                    }
                    writer.write(pixels)
                    val fraction = 0.85f + 0.15f * (index + 1) / shown
                    main.post { onProgress(fraction) }
                }
                val keep = !cancel
                writer.stop(keep)
                encoder = null
                if (!keep) {
                    main.post { onDone("Спряно.") }
                } else {
                    main.post { onDone("Готово · Movies/Temporal FFT · без звук") }
                }
            } catch (exc: Throwable) {
                main.post { onError(exc.message ?: "Грешка") }
            } finally {
                decoder?.close()
                if (encoder != null) {
                    try {
                        encoder.stop(false)
                    } catch (_: Exception) {
                    }
                }
                gate.withLock {
                    if (handle != 0L) NativeFilter.destroy(handle)
                    if (closed && active != 0L) {
                        NativeFilter.destroy(active)
                        active = 0L
                        ready = false
                    }
                }
            }
        }
    }

    fun bitmap(index: Int): Bitmap? {
        gate.withLock {
            if (!ready || active == 0L || index !in 0 until outFrames) return null
            val pixels = IntArray(width * height)
            NativeFilter.copyFrame(active, index, pixels)
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }
    }

    fun release() {
        cancel = true
        closed = true
        gate.withLock { ready = false }
        executor.shutdown()
    }
}

@Composable
private fun FftScreen(onBusy: (Boolean) -> Unit) {
    val context = LocalContext.current
    val session = remember { FftSession(context.applicationContext) }
    DisposableEffect(session) {
        onDispose { session.release() }
    }
    var about by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf("Няма избран клип") }
    var mode by remember { mutableIntStateOf(0) }
    var cutoff by remember { mutableFloatStateOf(2f) }
    var low by remember { mutableFloatStateOf(0.5f) }
    var high by remember { mutableFloatStateOf(3f) }
    var gain by remember { mutableFloatStateOf(3f) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("До 480 px и 10 секунди. Звукът не се пренася.") }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var frames by remember { mutableIntStateOf(0) }
    var frameIndex by remember { mutableIntStateOf(0) }

    val openVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            source = uri
            sourceName = uri.lastPathSegment ?: "Клип"
            status = "Клипът е избран."
        }
    }

    val scroll = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Temporal FFT", color = Accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "by Dr. Baysinger",
                    color = Muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.clickable { about = true },
                )
            }
            Text(
                "бързи   ·   бавни   ·   лента   ·   спектър",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val image = preview
            if (image != null && !image.isRecycled) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "Резултат",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    "Отвори клип\nРезултатът остава тук",
                    color = Muted,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (frames > 1) {
                Slider(
                    value = frameIndex.toFloat(),
                    onValueChange = {
                        val next = it.roundToInt().coerceIn(0, frames - 1)
                        frameIndex = next
                        preview = session.bitmap(next)
                    },
                    valueRange = 0f..(frames - 1).toFloat(),
                    modifier = Modifier.padding(horizontal = sliderSideInset()),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Отвори", Accent, Ink, !busy, Modifier.weight(1f)) {
                    openVideo.launch("video/*")
                }
                ActionButton("Обработи", Panel, Color.White, !busy && source != null, Modifier.weight(1f)) {
                    val uri = source ?: return@ActionButton
                    busy = true
                    onBusy(true)
                    frames = 0
                    frameIndex = 0
                    preview = null
                    status = "Чете клипа…"
                    session.start(uri, mode, cutoff, low, high, gain, { fraction ->
                        status = "Обработка ${(fraction * 100).roundToInt()}%"
                    }, { count, _, _ ->
                        frames = count
                        frameIndex = 0
                        preview = session.bitmap(0)
                        status = "Записва видеото…"
                    }, { message ->
                        busy = false
                        onBusy(false)
                        status = message
                    }, { message ->
                        busy = false
                        onBusy(false)
                        status = message
                    })
                }
                ActionButton("Отказ", Color(0xFFFF4D3A), Color.White, busy, Modifier.weight(1f)) {
                    session.cancel = true
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { (id, label) ->
                    val selected = mode == id
                    ActionButton(
                        label,
                        if (selected) Accent else Panel,
                        if (selected) Ink else Color.White,
                        !busy,
                        Modifier.weight(1f),
                    ) { mode = id }
                }
            }
            if (mode == 0 || mode == 1) {
                LabeledSlider("Граница", cutoff, 0.2f..12f, "${"%.1f".format(cutoff)} Hz") { cutoff = it }
            }
            if (mode == 2) {
                LabeledSlider("Долна", low, 0.1f..12f, "${"%.1f".format(low)} Hz") { low = it.coerceAtMost(high) }
                LabeledSlider("Горна", high, 0.2f..16f, "${"%.1f".format(high)} Hz") { high = it.coerceAtLeast(low) }
            }
            if (mode == 0 || mode == 2) {
                LabeledSlider("Усилване", gain, 0.5f..8f, "${"%.1f".format(gain)}×") { gain = it }
            }
            Text(if (source == null) status else "$sourceName    $status", color = Muted, fontSize = 12.sp)
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
            modifier = Modifier
                .padding(horizontal = sliderSideInset())
                .pullUp(10.dp),
        )
    }
}

@Composable
private fun sliderSideInset(): Dp {
    val prefix = rememberTextMeasurer().measure(text = "Из", style = LocalTextStyle.current).size.width
    return with(LocalDensity.current) { prefix.toDp() }
}

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
