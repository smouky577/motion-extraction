package com.motionextraction.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin
import kotlin.random.Random

class MotionRuntime(private val context: Context) {
    var bitmap by mutableStateOf<Bitmap?>(null)
        private set
    var mode by mutableStateOf("idle")
        private set
    var playing by mutableStateOf(false)
        private set
    var message by mutableStateOf("Отвори клип, пусни камерата или демото")
        private set
    var detail by mutableStateOf("")
        private set
    var span by mutableIntStateOf(30)
        private set
    var position by mutableFloatStateOf(0f)
        private set
    var recording by mutableStateOf(false)
        private set
    var exporting by mutableStateOf(false)
        private set

    @Volatile
    var settings = MotionSettings()

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var modeValue = "idle"
    @Volatile private var playingValue = false
    @Volatile private var recordingValue = false
    @Volatile private var exportingValue = false
    @Volatile private var spanValue = 30
    @Volatile private var positionValue = 0f
    @Volatile private var detailValue = ""

    private fun onMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun showMessage(text: String) {
        onMain { message = text }
    }

    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private val latestCamera = AtomicReference<RawFrame?>(null)
    private val history = MotionHistory()
    private var camera: CameraFeed? = null
    private var decoder: VideoDecoder? = null
    private var encoder: Mp4Encoder? = null
    private var nextDue = 0L
    private var fps = 30f
    private var demoIndex = 0
    private var demoStatic: IntArray? = null
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var front = 0
    @Volatile
    private var running = true
    private val worker = Thread(::loop, "motion-runtime").apply { start() }

    fun openVideo(uri: Uri) {
        commands.add {
            stopSource()
            try {
                val video = VideoDecoder(context, uri)
                decoder = video
                fps = video.fps
                updateSpan(video.fps)
                modeValue = "file"
                playingValue = true
                positionValue = 0f
                detailValue = "${video.width}×${video.height}    ${video.fps.toInt()} кадъра/сек"
                onMain {
                    mode = "file"
                    playing = true
                    position = 0f
                    message = ""
                    detail = detailValue
                }
                nextDue = 0L
            } catch (error: Exception) {
                modeValue = "idle"
                val text = error.message ?: "Не мога да отворя този клип."
                onMain {
                    mode = "idle"
                    message = text
                }
            }
        }
    }

    fun openCamera() {
        commands.add {
            stopSource()
            modeValue = "live"
            playingValue = true
            detailValue = "Камера"
            onMain {
                mode = "live"
                playing = true
                message = ""
                detail = "Камера"
            }
            fps = 30f
            nextDue = 0L
            camera = CameraFeed(
                context,
                onFrame = { latestCamera.set(it) },
                onError = { text -> showMessage(text) },
            ).also { it.start() }
        }
    }

    fun openDemo() {
        commands.add {
            stopSource()
            modeValue = "demo"
            playingValue = true
            fps = 30f
            updateSpan(30f)
            demoIndex = 0
            demoStatic = null
            detailValue = "Демо"
            onMain {
                mode = "demo"
                playing = true
                message = ""
                detail = "Демо"
            }
            nextDue = 0L
        }
    }

    fun stop() {
        commands.add {
            val saved = encoder?.uri
            stopSource()
            modeValue = "idle"
            playingValue = false
            detailValue = ""
            val text = if (saved != null) "Записът е в Movies/Motion Extraction" else "Спряно"
            onMain {
                bitmap = null
                mode = "idle"
                playing = false
                detail = ""
                message = text
            }
        }
    }

    fun togglePlay() {
        commands.add {
            if (modeValue == "file") {
                playingValue = !playingValue
                val nowPlaying = playingValue
                onMain { playing = nowPlaying }
                nextDue = 0L
            }
        }
    }

    fun seek(fraction: Float) {
        commands.add {
            val video = decoder ?: return@add
            video.seekToFraction(fraction)
            history.clear()
            positionValue = fraction
            onMain { position = fraction }
            nextDue = 0L
        }
    }

    fun toggleRecord() {
        commands.add {
            if (recordingValue) {
                finishRecording(true)
            } else if (modeValue == "live" || modeValue == "demo") {
                recordingValue = true
                onMain {
                    recording = true
                    message = "Запис…"
                }
            }
        }
    }

    fun export() {
        commands.add { exportFile() }
    }

    fun release() {
        running = false
        commands.add { stopSource() }
        worker.join(1500)
        mainHandler.removeCallbacksAndMessages(null)
        try {
            camera?.release()
        } catch (_: Exception) {
        }
    }

    private fun loop() {
        while (true) {
            while (true) {
                val command = commands.poll() ?: break
                try {
                    command()
                } catch (error: OutOfMemoryError) {
                    showMessage("Няма достатъчно памет.")
                } catch (error: Exception) {
                    showMessage(error.message ?: "Грешка")
                }
            }
            if (!running) break
            try {
                when (modeValue) {
                    "file" -> stepFile()
                    "live" -> stepCamera()
                    "demo" -> stepDemo()
                    else -> Thread.sleep(40)
                }
            } catch (error: OutOfMemoryError) {
                showMessage("Няма достатъчно памет.")
                Thread.sleep(40)
            } catch (error: Exception) {
                showMessage(error.message ?: "Грешка")
                Thread.sleep(40)
            }
        }
    }

    private fun stepFile() {
        val video = decoder ?: return
        if (!playingValue) {
            publish(history.render(tuned()))
            Thread.sleep(40)
            return
        }
        if (!waitForFrame()) return
        when (val decoded = video.next()) {
            is Decoded.Frame -> {
                show(decoded.raw)
                if (video.durationUs > 0) {
                    positionValue = (positionValue + 1f / video.frameCount.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val shown = positionValue
                    onMain { position = shown }
                }
            }
            Decoded.End -> {
                video.seekToStart()
                history.clear()
                positionValue = 0f
                onMain { position = 0f }
            }
            Decoded.Pending -> Thread.sleep(5)
        }
    }

    private fun stepCamera() {
        val frame = latestCamera.getAndSet(null)
        if (frame == null) {
            Thread.sleep(8)
            return
        }
        show(frame)
    }

    private fun stepDemo() {
        if (!waitForFrame()) return
        show(demoFrame())
    }

    private fun show(frame: RawFrame) {
        history.capacity = spanValue * 2 + 3
        history.add(frame.pixels, frame.width, frame.height)
        val rendered = history.render(tuned())
        if (recordingValue) writeRecording(rendered, frame.width, frame.height)
        val nextDetail = when (modeValue) {
            "live" -> "Камера    ${frame.width}×${frame.height}"
            "demo" -> "Демо    неподвижното изчезва"
            else -> detailValue
        }
        detailValue = nextDetail
        publish(rendered, frame.width, frame.height, nextDetail)
    }

    private fun tuned(): MotionSettings {
        val current = settings
        val spanNow = spanValue.coerceAtLeast(1)
        return current.copy(span = spanNow, delay = current.delay.coerceIn(1, spanNow))
    }

    private fun publish(
        pixels: IntArray,
        width: Int = history.width,
        height: Int = history.height,
        detailText: String? = null,
    ) {
        if (pixels.isEmpty() || width <= 1 || height <= 1) return
        val index = 1 - front
        var target = buffers[index]
        if (target == null || target.width != width || target.height != height) {
            target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            buffers[index] = target
        }
        target.setPixels(pixels, 0, width, 0, 0, width, height)
        front = index
        val shown = target
        onMain {
            bitmap = shown
            if (detailText != null) detail = detailText
        }
    }

    private fun waitForFrame(): Boolean {
        val period = (1000f / fps.coerceAtLeast(1f)).toLong()
        val now = System.currentTimeMillis()
        if (nextDue == 0L) nextDue = now
        val wait = nextDue - now
        if (wait > 2) Thread.sleep(wait)
        nextDue += period
        if (System.currentTimeMillis() - nextDue > 400) nextDue = System.currentTimeMillis()
        return true
    }

    private fun updateSpan(value: Float) {
        fps = value.coerceIn(1f, 120f)
        val next = value.toInt().coerceIn(2, 120)
        if (kotlin.math.abs(next - spanValue) >= 2) {
            spanValue = next
            onMain { span = next }
        }
    }

    private fun writeRecording(pixels: IntArray, width: Int, height: Int) {
        if (encoder == null) {
            encoder = Mp4Encoder(context, width, height, fps).also { it.start() }
        }
        encoder?.write(pixels)
    }

    private fun finishRecording(keep: Boolean) {
        encoder?.stop(keep)
        encoder = null
        recordingValue = false
        onMain { recording = false }
    }

    private fun exportFile() {
        val video = decoder ?: run {
            showMessage("Експортът е за отворен клип.")
            return
        }
        playingValue = false
        exportingValue = true
        onMain {
            playing = false
            exporting = true
        }
        val snapshot = tuned()
        val saved = video.frameCount
        history.clear()
        System.gc()
        video.seekToStart()
        val exportHistory = MotionHistory(snapshot.span * 2 + 3)
        var recorder: Mp4Encoder? = null
        var count = 0
        var pending = 0
        try {
            while (running && count < saved.coerceAtLeast(1) + 30 && pending < 40) {
                when (val decoded = video.next()) {
                    is Decoded.Frame -> {
                        pending = 0
                        val current = recorder ?: Mp4Encoder(
                            context,
                            decoded.raw.width,
                            decoded.raw.height,
                            video.fps,
                        ).also {
                            it.start()
                            recorder = it
                        }
                        exportHistory.add(decoded.raw.pixels, decoded.raw.width, decoded.raw.height)
                        current.write(exportHistory.render(snapshot))
                        count++
                        if (saved > 0) positionValue = count.toFloat() / saved
                        val percent = if (saved > 0) count * 100 / saved else count
                        val shown = positionValue
                        onMain {
                            if (saved > 0) position = shown
                            message = "Експорт $percent%"
                        }
                    }
                    Decoded.End -> break
                    Decoded.Pending -> {
                        pending++
                        Thread.sleep(5)
                    }
                }
            }
            recorder?.stop(true)
            showMessage("Готово в Movies/Motion Extraction  ·  без звук")
        } catch (error: OutOfMemoryError) {
            try {
                recorder?.stop(false)
            } catch (_: Throwable) {
            }
            showMessage("Няма достатъчно памет за експорта.")
        } catch (error: Exception) {
            try {
                recorder?.stop(false)
            } catch (_: Throwable) {
            }
            showMessage(error.message ?: "Грешка при запис")
        } finally {
            exportingValue = false
            positionValue = 0f
            onMain {
                exporting = false
                position = 0f
            }
            video.seekToStart()
            history.clear()
        }
    }

    private fun stopSource() {
        finishRecording(recordingValue)
        camera?.release()
        camera = null
        latestCamera.set(null)
        decoder?.close()
        decoder = null
        history.clear()
        playingValue = false
        onMain { playing = false }
    }

    private fun demoFrame(): RawFrame {
        val width = 480
        val height = 270
        val base = demoStatic ?: IntArray(width * height).also { pixels ->
            val random = Random(1)
            for (i in pixels.indices) {
                val noise = random.nextInt(28, 46)
                pixels[i] = (0xFF shl 24) or ((noise + 6) shl 16) or (noise shl 8) or (noise + 14)
            }
            fillRect(pixels, width, 40, 30, 160, 70, 0xFF3A4038.toInt())
            fillRect(pixels, width, 300, 160, 150, 80, 0xFF4A3A30.toInt())
            demoStatic = pixels
        }
        val frame = base.copyOf()
        val index = demoIndex++
        val x = 40 + (index * 7) % (width - 80)
        val y = (110 + sin(index / 8.0) * 60).toInt()
        fillCircle(frame, width, height, x, y, 22, 0xFFF2F2EB.toInt())
        val x2 = 20 + (index * 3) % (width - 140)
        fillRect(frame, width, x2, height - 70, 90, 36, 0xFFE6AA28.toInt())
        return RawFrame(frame, width, height)
    }
}

private fun fillRect(pixels: IntArray, width: Int, x: Int, y: Int, w: Int, h: Int, color: Int) {
    for (row in y until y + h) {
        if (row < 0 || row >= pixels.size / width) continue
        for (col in x until x + w) {
            if (col in 0 until width) pixels[row * width + col] = color
        }
    }
}

private fun fillCircle(pixels: IntArray, width: Int, height: Int, cx: Int, cy: Int, radius: Int, color: Int) {
    val r2 = radius * radius
    for (y in (cy - radius)..(cy + radius)) {
        if (y !in 0 until height) continue
        for (x in (cx - radius)..(cx + radius)) {
            if (x !in 0 until width) continue
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= r2) pixels[y * width + x] = color
        }
    }
}
