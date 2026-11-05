package com.motionextraction.app

import android.content.Context
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Two IIR leaky integrators per pixel. No frame queue.
 * fast += aFast * (frame - fast)
 * slow += aSlow * (frame - slow)
 * d = fast - slow
 */
data class IirSettings(
    val enabled: Boolean = false,
    val mode: String = "abs",
    val memory: Float = 0.8f,
    val aFast: Float = 0.35f,
    val gain: Float = 5f,
    val thr: Float = 2f,
    val gamma: Float = 1f,
    val blur: Float = 1.5f,
    val dim: Float = 0.15f,
    val glowSigma: Float = 14f,
    val glowStrength: Float = 1.2f,
    val white: Boolean = true,
    val refreshSec: Float = 0f,
    val scale: Float = 0.5f,
    val lockExposure: Boolean = false,
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enabled)
            .putString("mode", mode)
            .putFloat("memory", memory)
            .putFloat("aFast", aFast)
            .putFloat("gain", gain)
            .putFloat("thr", thr)
            .putFloat("gamma", gamma)
            .putFloat("blur", blur)
            .putFloat("dim", dim)
            .putFloat("glowSigma", glowSigma)
            .putFloat("glowStrength", glowStrength)
            .putBoolean("white", white)
            .putFloat("refreshSec", refreshSec)
            .putFloat("scale", scale)
            .putBoolean("lockExposure", lockExposure)
            .apply()
    }

    companion object {
        const val PREFS = "motion_effects"

        fun load(context: Context): IirSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return IirSettings(
                enabled = prefs.getBoolean("enabled", false),
                mode = prefs.getString("mode", "abs") ?: "abs",
                memory = prefs.getFloat("memory", 0.8f),
                aFast = prefs.getFloat("aFast", 0.35f),
                gain = prefs.getFloat("gain", 5f),
                thr = prefs.getFloat("thr", 2f),
                gamma = prefs.getFloat("gamma", 1f),
                blur = prefs.getFloat("blur", 1.5f),
                dim = prefs.getFloat("dim", 0.15f),
                glowSigma = prefs.getFloat("glowSigma", 14f),
                glowStrength = prefs.getFloat("glowStrength", 1.2f),
                white = prefs.getBoolean("white", true),
                refreshSec = prefs.getFloat("refreshSec", 0f),
                scale = prefs.getFloat("scale", 0.5f),
                lockExposure = prefs.getBoolean("lockExposure", false),
            )
        }
    }
}

val iirModes = listOf(
    "gray" to "Сиво ±",
    "abs" to "Черно",
    "mask" to "Маска",
    "add" to "Добавяне",
    "glow" to "Сияние",
    "color" to "± цвят",
    "frozen" to "Замразен",
)

fun IirSettings.insects() = copy(
    enabled = true, mode = "abs",
    memory = 0.2f, aFast = 0.5f, gain = 6f, thr = 3f, blur = 1.5f, dim = 0f, scale = 0.5f,
)

fun IirSettings.grass() = copy(
    enabled = true, mode = "add",
    memory = 0.8f, aFast = 0.3f, gain = 4f, thr = 2f, blur = 1.5f, dim = 0.85f, white = false,
)

fun IirSettings.vapor() = copy(
    enabled = true, mode = "color",
    memory = 4f, aFast = 0.25f, gain = 6f, thr = 1.5f, gamma = 0.6f, blur = 4f, dim = 0.15f,
)

fun IirSettings.moon() = copy(
    enabled = true, mode = "frozen",
    memory = 5f, gain = 5f, thr = 1.5f, blur = 2f, refreshSec = 20f, dim = 0f,
)

class IirMotion {
    private var procW = 0
    private var procH = 0
    private var ready = false
    private var frameIndex = 0
    private var slowStreak = 0
    private var autoScale = 1f
    private var f = FloatArray(0)
    private var fast = FloatArray(0)
    private var slow = FloatArray(0)
    private var ref = FloatArray(0)
    private var diff = FloatArray(0)
    private var outF = FloatArray(0)
    private var scratch = FloatArray(0)
    private var mag = FloatArray(0)
    private var magB = FloatArray(0)
    private var core = FloatArray(0)
    private var out = IntArray(0)

    @Volatile var freezeNow = false
    var activeScale = 0.5f
        private set

    fun reset() {
        ready = false
        frameIndex = 0
        autoScale = 1f
        slowStreak = 0
    }

    fun render(src: IntArray, width: Int, height: Int, settings: IirSettings, fps: Float): IntArray {
        if (width < 2 || height < 2 || src.size < width * height) return src
        val started = System.nanoTime()
        val userScale = settings.scale.coerceIn(0.25f, 1f)
        val scale = (userScale * autoScale).coerceIn(0.25f, 1f)
        activeScale = scale
        val dw = (width * scale).roundToInt().coerceIn(32, width).let { it - it % 2 }
        val dh = (height * scale).roundToInt().coerceIn(32, height).let { it - it % 2 }
        ensure(dw, dh)
        downsample(src, width, height, f, dw, dh)
        val rate = fps.coerceIn(1f, 120f)
        if (settings.mode == "frozen") {
            frozenDiff(settings, rate)
        } else {
            val aFast = settings.aFast.coerceIn(0.01f, 1f)
            val aSlow = (1f / (rate * settings.memory.coerceIn(0.1f, 10f))).coerceIn(0.0001f, 1f)
            val n = dw * dh * 3
            if (!ready) {
                f.copyInto(fast, endIndex = n)
                f.copyInto(slow, endIndex = n)
                ready = true
            }
            for (i in 0 until n) {
                val value = f[i]
                fast[i] += aFast * (value - fast[i])
                slow[i] += aSlow * (value - slow[i])
                diff[i] = fast[i] - slow[i]
            }
            blur3(diff, dw, dh, 3, settings.blur)
        }
        when (settings.mode) {
            "gray" -> signedGray()
            "mask" -> masked(settings)
            "add" -> additive(settings)
            "glow" -> glow(settings, dw, dh)
            "color" -> signedColor(settings)
            "frozen" -> absolute(settings)
            else -> absolute(settings)
        }
        val pixels = width * height
        if (out.size != pixels) out = IntArray(pixels)
        upsample(outF, dw, dh, out, width, height)
        val ms = (System.nanoTime() - started) / 1_000_000f
        if (ms > 66f) {
            slowStreak++
            if (slowStreak >= 4 && autoScale > 0.26f) {
                autoScale = (autoScale * 0.8f).coerceAtLeast(0.25f)
                ready = false
                slowStreak = 0
            }
        } else {
            slowStreak = 0
        }
        return out
    }

    private fun frozenDiff(settings: IirSettings, fps: Float) {
        val n = procW * procH * 3
        val period = (fps * settings.refreshSec).roundToInt()
        val due = settings.refreshSec > 0.05f && period > 0 && frameIndex % period == 0
        if (!ready || freezeNow || due) {
            f.copyInto(ref, endIndex = n)
            blur3(ref, procW, procH, 3, 2f)
            ready = true
            freezeNow = false
        }
        frameIndex++
        f.copyInto(diff, endIndex = n)
        blur3(diff, procW, procH, 3, 2f)
        for (i in 0 until n) diff[i] -= ref[i]
    }

    private fun signedGray() {
        val n = procW * procH * 3
        for (i in 0 until n) outF[i] = (diff[i] + 128f).coerceIn(0f, 255f)
    }

    private fun absolute(settings: IirSettings) {
        val pixels = procW * procH
        val gain = settings.gain
        val thr = settings.thr
        if (settings.white) {
            for (p in 0 until pixels) {
                val i = p * 3
                val mag = maxOf(
                    absChannel(diff[i], thr),
                    absChannel(diff[i + 1], thr),
                    absChannel(diff[i + 2], thr),
                ) * gain
                val v = mag.coerceIn(0f, 255f)
                outF[i] = v
                outF[i + 1] = v
                outF[i + 2] = v
            }
        } else {
            for (i in 0 until pixels * 3) {
                outF[i] = (absChannel(diff[i], thr) * gain).coerceIn(0f, 255f)
            }
        }
    }

    private fun masked(settings: IirSettings) {
        val pixels = procW * procH
        val gamma = settings.gamma.coerceIn(0.2f, 2f)
        val dim = settings.dim.coerceIn(0f, 1f)
        for (p in 0 until pixels) {
            val i = p * 3
            val mag = maxOf(kotlin.math.abs(diff[i]), kotlin.math.abs(diff[i + 1]), kotlin.math.abs(diff[i + 2]))
            val mask = ((mag - settings.thr) / 25f).coerceIn(0f, 1f).pow(gamma)
            val factor = dim + (1f - dim) * mask
            outF[i] = (f[i] * factor).coerceIn(0f, 255f)
            outF[i + 1] = (f[i + 1] * factor).coerceIn(0f, 255f)
            outF[i + 2] = (f[i + 2] * factor).coerceIn(0f, 255f)
        }
    }

    private fun additive(settings: IirSettings) {
        val pixels = procW * procH
        val gain = settings.gain
        val thr = settings.thr
        val dim = settings.dim.coerceIn(0f, 1f)
        for (p in 0 until pixels) {
            val i = p * 3
            if (settings.white) {
                val mag = maxOf(
                    absChannel(diff[i], thr),
                    absChannel(diff[i + 1], thr),
                    absChannel(diff[i + 2], thr),
                ) * gain
                outF[i] = (f[i] * dim + mag).coerceIn(0f, 255f)
                outF[i + 1] = (f[i + 1] * dim + mag).coerceIn(0f, 255f)
                outF[i + 2] = (f[i + 2] * dim + mag).coerceIn(0f, 255f)
            } else {
                outF[i] = (f[i] * dim + absChannel(diff[i], thr) * gain).coerceIn(0f, 255f)
                outF[i + 1] = (f[i + 1] * dim + absChannel(diff[i + 1], thr) * gain).coerceIn(0f, 255f)
                outF[i + 2] = (f[i + 2] * dim + absChannel(diff[i + 2], thr) * gain).coerceIn(0f, 255f)
            }
        }
    }

    private fun glow(settings: IirSettings, w: Int, h: Int) {
        val pixels = w * h
        val gain = settings.gain
        val thr = settings.thr
        for (p in 0 until pixels) {
            val i = p * 3
            val magValue = maxOf(kotlin.math.abs(diff[i]), kotlin.math.abs(diff[i + 1]), kotlin.math.abs(diff[i + 2]))
            mag[p] = ((magValue - thr) * gain).coerceIn(0f, 255f)
        }
        mag.copyInto(core, endIndex = pixels)
        mag.copyInto(magB, endIndex = pixels)
        blur3(core, w, h, 1, 2f)
        blur3(magB, w, h, 1, settings.glowSigma.coerceIn(1f, 40f))
        val strength = settings.glowStrength
        val dim = settings.dim.coerceIn(0f, 1f)
        for (p in 0 until pixels) {
            val g = ((core[p] * 0.6f + magB[p] * strength) / 255f).coerceIn(0f, 1f)
            val i = p * 3
            outF[i] = screen(f[i], dim, g)
            outF[i + 1] = screen(f[i + 1], dim, g)
            outF[i + 2] = screen(f[i + 2], dim, g)
        }
    }

    private fun signedColor(settings: IirSettings) {
        val pixels = procW * procH
        val gain = settings.gain
        val gamma = settings.gamma.coerceIn(0.2f, 2f)
        val dim = settings.dim.coerceIn(0f, 1f)
        for (p in 0 until pixels) {
            val i = p * 3
            val lum = (diff[i] + diff[i + 1] + diff[i + 2]) / 3f
            val amp = kotlin.math.abs(lum)
            val mask = ((amp - settings.thr) / 10f).coerceIn(0f, 1f).pow(gamma)
            val amount = amp * gain * mask
            val red = if (lum >= 0f) amount else 0f
            val blue = if (lum < 0f) amount else 0f
            outF[i] = (f[i] * dim + red).coerceIn(0f, 255f)
            outF[i + 1] = (f[i + 1] * dim).coerceIn(0f, 255f)
            outF[i + 2] = (f[i + 2] * dim + blue).coerceIn(0f, 255f)
        }
    }

    private fun ensure(w: Int, h: Int) {
        val color = w * h * 3
        val plane = w * h
        if (w != procW || h != procH || f.size < color) {
            procW = w
            procH = h
            ready = false
            f = FloatArray(color)
            fast = FloatArray(color)
            slow = FloatArray(color)
            ref = FloatArray(color)
            diff = FloatArray(color)
            outF = FloatArray(color)
            scratch = FloatArray(color)
            mag = FloatArray(plane)
            magB = FloatArray(plane)
            core = FloatArray(plane)
        }
    }

    private fun blur3(src: FloatArray, w: Int, h: Int, ch: Int, sigma: Float) {
        if (sigma < 0.2f || w < 2 || h < 2) return
        val radius = boxRadius(sigma).coerceAtMost(minOf(w, h) / 2)
        if (radius < 1) return
        val need = w * h * ch
        if (scratch.size < need) scratch = FloatArray(need)
        repeat(3) {
            boxH(src, scratch, w, h, ch, radius)
            boxV(scratch, src, w, h, ch, radius)
        }
    }

    private fun boxH(src: FloatArray, dst: FloatArray, w: Int, h: Int, ch: Int, r: Int) {
        val win = (r * 2 + 1).toFloat()
        for (y in 0 until h) {
            val row = y * w * ch
            for (c in 0 until ch) {
                var sum = 0f
                for (k in -r..r) {
                    sum += src[row + k.coerceIn(0, w - 1) * ch + c]
                }
                for (x in 0 until w) {
                    dst[row + x * ch + c] = sum / win
                    val remove = (x - r).coerceIn(0, w - 1)
                    val add = (x + r + 1).coerceIn(0, w - 1)
                    sum += src[row + add * ch + c] - src[row + remove * ch + c]
                }
            }
        }
    }

    private fun boxV(src: FloatArray, dst: FloatArray, w: Int, h: Int, ch: Int, r: Int) {
        val win = (r * 2 + 1).toFloat()
        for (x in 0 until w) {
            for (c in 0 until ch) {
                var sum = 0f
                for (k in -r..r) {
                    val yy = k.coerceIn(0, h - 1)
                    sum += src[(yy * w + x) * ch + c]
                }
                for (y in 0 until h) {
                    dst[(y * w + x) * ch + c] = sum / win
                    val remove = (y - r).coerceIn(0, h - 1)
                    val add = (y + r + 1).coerceIn(0, h - 1)
                    sum += src[(add * w + x) * ch + c] - src[(remove * w + x) * ch + c]
                }
            }
        }
    }
}

private fun boxRadius(sigma: Float): Int {
    val width = sqrt(4f * sigma * sigma + 1f)
    return ((width - 1f) / 2f).roundToInt().coerceAtLeast(1)
}

private fun absChannel(value: Float, thr: Float): Float = (kotlin.math.abs(value) - thr).coerceAtLeast(0f)

private fun screen(pixel: Float, dim: Float, glow: Float): Float {
    val base = (pixel / 255f) * dim
    return ((1f - (1f - base) * (1f - glow)) * 255f).coerceIn(0f, 255f)
}

private fun downsample(src: IntArray, sw: Int, sh: Int, dst: FloatArray, dw: Int, dh: Int) {
    val xScale = sw.toFloat() / dw
    val yScale = sh.toFloat() / dh
    var i = 0
    for (y in 0 until dh) {
        val sy = ((y + 0.5f) * yScale - 0.5f).coerceIn(0f, (sh - 1).toFloat())
        val y0 = sy.toInt()
        val y1 = (y0 + 1).coerceAtMost(sh - 1)
        val fy = sy - y0
        val row0 = y0 * sw
        val row1 = y1 * sw
        for (x in 0 until dw) {
            val sx = ((x + 0.5f) * xScale - 0.5f).coerceIn(0f, (sw - 1).toFloat())
            val x0 = sx.toInt()
            val x1 = (x0 + 1).coerceAtMost(sw - 1)
            val fx = sx - x0
            val c00 = src[row0 + x0]
            val c10 = src[row0 + x1]
            val c01 = src[row1 + x0]
            val c11 = src[row1 + x1]
            dst[i++] = mix4((c00 shr 16) and 255, (c10 shr 16) and 255, (c01 shr 16) and 255, (c11 shr 16) and 255, fx, fy)
            dst[i++] = mix4((c00 shr 8) and 255, (c10 shr 8) and 255, (c01 shr 8) and 255, (c11 shr 8) and 255, fx, fy)
            dst[i++] = mix4(c00 and 255, c10 and 255, c01 and 255, c11 and 255, fx, fy)
        }
    }
}

private fun mix4(c00: Int, c10: Int, c01: Int, c11: Int, fx: Float, fy: Float): Float {
    val top = c00 + (c10 - c00) * fx
    val bottom = c01 + (c11 - c01) * fx
    return top + (bottom - top) * fy
}

private fun upsample(src: FloatArray, sw: Int, sh: Int, dst: IntArray, dw: Int, dh: Int) {
    for (y in 0 until dh) {
        val sy = (y * sh / dh).coerceIn(0, sh - 1)
        val row = sy * sw
        val outRow = y * dw
        for (x in 0 until dw) {
            val sx = (x * sw / dw).coerceIn(0, sw - 1)
            val i = (row + sx) * 3
            val r = src[i].toInt().coerceIn(0, 255)
            val g = src[i + 1].toInt().coerceIn(0, 255)
            val b = src[i + 2].toInt().coerceIn(0, 255)
            dst[outRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
