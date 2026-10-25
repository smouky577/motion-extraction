package com.motionextraction.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max

data class RawFrame(val pixels: IntArray, val width: Int, val height: Int)

fun yuvImageToArgb(image: Image, maxEdge: Int): RawFrame? {
    if (image.planes.size < 3) return null
    val width = image.width
    val height = image.height
    val longSide = max(width, height)
    val step = max(1, (longSide + maxEdge - 1) / maxEdge)
    var outW = width / step
    var outH = height / step
    outW -= outW % 16
    outH -= outH % 16
    if (outW < 16 || outH < 16) {
        outW = width / step
        outH = height / step
        outW -= outW % 2
        outH -= outH % 2
    }
    if (outW < 2 || outH < 2) return null
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val yBuf = yPlane.buffer
    val uBuf = uPlane.buffer
    val vBuf = vPlane.buffer
    val yPos = yBuf.position()
    val uPos = uBuf.position()
    val vPos = vBuf.position()
    val out = IntArray(outW * outH)
    for (y in 0 until outH) {
        val sy = y * step
        val yBase = yPos + sy * yPlane.rowStride
        val uBase = uPos + (sy / 2) * uPlane.rowStride
        val vBase = vPos + (sy / 2) * vPlane.rowStride
        val row = y * outW
        for (x in 0 until outW) {
            val sx = x * step
            val yy = yBuf.get(yBase + sx * yPlane.pixelStride).toInt() and 0xFF
            val uu = uBuf.get(uBase + (sx / 2) * uPlane.pixelStride).toInt() and 0xFF
            val vv = vBuf.get(vBase + (sx / 2) * vPlane.pixelStride).toInt() and 0xFF
            out[row + x] = yuvToArgb(yy, uu, vv)
        }
    }
    return RawFrame(out, outW, outH)
}

fun yuvToArgb(y: Int, u: Int, v: Int): Int {
    val c = y - 16
    val d = u - 128
    val e = v - 128
    val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
    val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
    val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

fun rotateArgb(frame: RawFrame, degrees: Int): RawFrame {
    val src = frame.pixels
    val width = frame.width
    val height = frame.height
    val turned = degrees % 360
    if (turned == 0) return frame
    val out = IntArray(src.size)
    when (turned) {
        90 -> {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[x * height + (height - 1 - y)] = src[y * width + x]
                }
            }
            return evenFrame(out, height, width)
        }
        180 -> {
            for (i in src.indices) out[src.size - 1 - i] = src[i]
            return evenFrame(out, width, height)
        }
        270 -> {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[(width - 1 - x) * height + y] = src[y * width + x]
                }
            }
            return evenFrame(out, height, width)
        }
        else -> return frame
    }
}

fun mirrorHorizontal(frame: RawFrame): RawFrame {
    val out = IntArray(frame.pixels.size)
    for (y in 0 until frame.height) {
        val row = y * frame.width
        for (x in 0 until frame.width) {
            out[row + x] = frame.pixels[row + (frame.width - 1 - x)]
        }
    }
    return RawFrame(out, frame.width, frame.height)
}

private fun evenFrame(pixels: IntArray, width: Int, height: Int): RawFrame {
    val evenW = width - width % 2
    val evenH = height - height % 2
    if (evenW == width && evenH == height) return RawFrame(pixels, width, height)
    val out = IntArray(evenW * evenH)
    for (y in 0 until evenH) {
        pixels.copyInto(out, y * evenW, y * width, y * width + evenW)
    }
    return RawFrame(out, evenW, evenH)
}

class CameraFeed(
    context: Context,
    private val onFrame: (RawFrame) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("motion-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val generation = AtomicInteger(0)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    fun start() {
        val gen = generation.incrementAndGet()
        val id = chooseCamera() ?: run {
            onError("Няма камера.")
            return
        }
        val chars = manager.getCameraCharacteristics(id)
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val size = chooseSize(chars)
        handler.post {
            if (gen != generation.get()) return@post
            closeOnCameraThread()
            val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val raw = yuvImageToArgb(image, 640) ?: return@setOnImageAvailableListener
                    var upright = rotateArgb(raw, sensor)
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT) upright = mirrorHorizontal(upright)
                    onFrame(upright)
                } catch (_: Exception) {
                } finally {
                    image.close()
                }
            }, handler)
            try {
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (gen != generation.get()) {
                            camera.close()
                            return
                        }
                        device = camera
                        try {
                            camera.createCaptureSession(
                                listOf(imageReader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(capture: CameraCaptureSession) {
                                        if (gen != generation.get()) {
                                            capture.close()
                                            return
                                        }
                                        session = capture
                                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(imageReader.surface)
                                            set(
                                                CaptureRequest.CONTROL_AF_MODE,
                                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                                            )
                                        }.build()
                                        capture.setRepeatingRequest(request, null, handler)
                                    }

                                    override fun onConfigureFailed(capture: CameraCaptureSession) {
                                        onError("Камерата не тръгна.")
                                    }
                                },
                                handler,
                            )
                        } catch (_: Exception) {
                            onError("Камерата не тръгна.")
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        onError("Камерата спря.")
                    }
                }, handler)
            } catch (_: SecurityException) {
                onError("Няма разрешение за камерата.")
            } catch (_: Exception) {
                onError("Камерата не се отваря.")
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        handler.post { closeOnCameraThread() }
    }

    fun release() {
        if (!thread.isAlive) return
        stop()
        thread.quitSafely()
    }

    private fun closeOnCameraThread() {
        session?.close()
        session = null
        device?.close()
        device = null
        reader?.close()
        reader = null
    }

    private fun chooseCamera(): String? {
        val ids = manager.cameraIdList
        return ids.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull()
    }

    private fun chooseSize(chars: CameraCharacteristics): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        return sizes.minByOrNull { abs(it.width - 1280) + abs(it.height - 720) }
            ?: Size(640, 480)
    }
}

class VideoDecoder(context: Context, uri: Uri) {
    private val extractor = MediaExtractor()
    private val pfd = context.contentResolver.openFileDescriptor(uri, "r")
    private var decoder: MediaCodec? = null
    private var inputDone = false
    var width = 0
        private set
    var height = 0
        private set
    var fps = 30f
        private set
    var durationUs = 0L
        private set
    var rotation = 0
        private set
    val frameCount: Int
        get() = if (durationUs <= 0L) 0 else (durationUs * fps / 1_000_000f).toInt().coerceAtLeast(1)

    init {
        val descriptor = pfd?.fileDescriptor ?: error("Не мога да отворя клипа.")
        extractor.setDataSource(descriptor, 0, pfd?.statSize ?: Long.MAX_VALUE)
        var track = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("video/")) {
                track = i
                break
            }
        }
        if (track < 0) error("В клипа няма видео.")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        width = format.getInteger(MediaFormat.KEY_WIDTH)
        height = format.getInteger(MediaFormat.KEY_HEIGHT)
        durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            fps = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat().coerceIn(1f, 120f)
        }
        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            rotation = format.getInteger(MediaFormat.KEY_ROTATION)
        }
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        decoder = MediaCodec.createDecoderByType(mime).also {
            it.configure(format, null, null, 0)
            it.start()
        }
    }

    fun next(): Decoded {
        val codec = decoder ?: return Decoded.End
        val info = MediaCodec.BufferInfo()
        repeat(8) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(8_000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 8_000)
            if (outIndex >= 0) {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val image = codec.getOutputImage(outIndex)
                val frame = if (image != null) {
                    try {
                        yuvImageToArgb(image, 640)
                    } finally {
                        image.close()
                    }
                } else {
                    null
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (frame != null) return Decoded.Frame(rotateArgb(frame, rotation))
                if (eos) return Decoded.End
            }
        }
        return if (inputDone) Decoded.End else Decoded.Pending
    }

    fun seekToStart() {
        val codec = decoder ?: return
        codec.flush()
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        inputDone = false
    }

    fun seekToFraction(fraction: Float) {
        val codec = decoder ?: return
        val time = (durationUs * fraction.coerceIn(0f, 0.99f)).toLong()
        codec.flush()
        extractor.seekTo(time, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        inputDone = false
    }

    fun close() {
        try {
            decoder?.stop()
        } catch (_: Exception) {
        }
        decoder?.release()
        decoder = null
        extractor.release()
        pfd?.close()
    }
}

sealed class Decoded {
    data class Frame(val raw: RawFrame) : Decoded()
    data object Pending : Decoded()
    data object End : Decoded()
}

class Mp4Encoder(private val context: Context, private val width: Int, private val height: Int, fps: Float) {
    private val frameRate = fps.toInt().coerceIn(1, 60)
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var muxerStarted = false
    private var frameIndex = 0L
    private var colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    private val yuv = ByteArray(width * height * 3 / 2)
    var uri: Uri? = null
        private set
    private var descriptor: android.os.ParcelFileDescriptor? = null

    fun start() {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "motion-${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Motion Extraction")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val target = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Не мога да създам видеото.")
        uri = target
        val pfd = context.contentResolver.openFileDescriptor(target, "w") ?: error("Не мога да запиша видеото.")
        descriptor = pfd
        colorFormat = chooseColorFormat()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, width * height * 5)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
            it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }
        muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun write(pixels: IntArray) {
        val codec = encoder ?: return
        drain(false)
        val index = codec.dequeueInputBuffer(10_000)
        if (index < 0) return
        val buffer = codec.getInputBuffer(index) ?: return
        buffer.clear()
        writeYuv(pixels, buffer)
        val pts = frameIndex * 1_000_000L / frameRate
        frameIndex++
        codec.queueInputBuffer(index, 0, yuv.size, pts, 0)
        drain(false)
    }

    fun stop(keep: Boolean) {
        val codec = encoder
        if (codec != null) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(true)
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
        }
        encoder = null
        try {
            if (muxerStarted) muxer?.stop()
        } catch (_: Exception) {
        }
        muxer?.release()
        muxer = null
        descriptor?.close()
        descriptor = null
        val target = uri
        if (target != null && Build.VERSION.SDK_INT >= 29) {
            if (keep) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                context.contentResolver.update(target, values, null, null)
            } else {
                context.contentResolver.delete(target, null, null)
                uri = null
            }
        }
    }

    private fun writeYuv(pixels: IntArray, buffer: ByteBuffer) {
        val planar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        var yIndex = 0
        val uvStart = width * height
        var uv = uvStart
        val chroma = if (planar) width * height / 4 else 0
        for (j in 0 until height) {
            val row = j * width
            for (i in 0 until width) {
                val color = pixels[row + i]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                yuv[yIndex++] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()
                if ((j and 1) == 0 && (i and 1) == 0) {
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    if (planar) {
                        val sample = (j / 2) * (width / 2) + i / 2
                        yuv[uvStart + sample] = u.toByte()
                        yuv[uvStart + chroma + sample] = v.toByte()
                    } else {
                        yuv[uv++] = u.toByte()
                        yuv[uv++] = v.toByte()
                    }
                }
            }
        }
        buffer.put(yuv, 0, yuv.size)
    }

    private fun drain(end: Boolean) {
        val codec = encoder ?: return
        val info = MediaCodec.BufferInfo()
        var spins = 0
        while (spins < 40) {
            spins++
            val index = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        track = muxer!!.addTrack(codec.outputFormat)
                        muxer!!.start()
                        muxerStarted = true
                    }
                }
                index >= 0 -> {
                    val output = codec.getOutputBuffer(index)
                    if (output != null && info.size > 0 && muxerStarted &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        muxer!!.writeSampleData(track, output, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) return
                }
            }
        }
    }

    private fun chooseColorFormat(): Int {
        val semi = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        val planar = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val encoderInfo = infos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        } ?: return semi
        val formats = encoderInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats.toSet()
        return when {
            semi in formats -> semi
            planar in formats -> planar
            else -> formats.firstOrNull() ?: semi
        }
    }
}

fun bitmapFrom(frame: RawFrame): Bitmap {
    return Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
}
