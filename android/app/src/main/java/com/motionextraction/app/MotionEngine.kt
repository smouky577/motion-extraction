package com.motionextraction.app

/**
 * Same mix as the desktop app and the ffmpeg graph:
 * duplicate, negate, 50% opacity, delay, overlay.
 * Static pixels become mid-gray. Motion stays visible.
 */
data class MotionSettings(
    val delay: Int = 1,
    val offR: Int = 0,
    val offG: Int = 0,
    val offB: Int = 0,
    val blur: Int = 0,
    val glow: Boolean = false,
    val swap: String = "none",
    val span: Int = 30,
)

class MotionHistory(var capacity: Int = 96) {
    private val frames = ArrayDeque<IntArray>()
    private var scratchA = IntArray(0)
    private var scratchB = IntArray(0)
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    fun clear() {
        frames.clear()
        width = 0
        height = 0
    }

    fun add(frame: IntArray, frameWidth: Int, frameHeight: Int) {
        if (frameWidth != width || frameHeight != height) {
            frames.clear()
            width = frameWidth
            height = frameHeight
        }
        frames.addLast(frame)
        while (frames.size > capacity) frames.removeFirst()
    }

    fun render(settings: MotionSettings): IntArray {
        val current = frames.lastOrNull() ?: IntArray(0)
        if (current.isEmpty() || width <= 0 || height <= 0) return current
        val span = settings.span.coerceAtLeast(2)
        val limit = span * 2
        val base = settings.delay.coerceIn(0, span)
        val delayR = (base + settings.offR).coerceIn(0, limit)
        val delayG = (base + settings.offG).coerceIn(0, limit)
        val delayB = (base + settings.offB).coerceIn(0, limit)
        val need = maxOf(delayR, delayG, delayB)
        if (frames.size <= need) return current.copyOf()

        val radius = if (settings.blur <= 0) 0 else (settings.blur * width / 960).coerceAtLeast(1)
        val blurredCurrent = blur(current, width, height, radius)
        val out = if (delayR == delayG && delayG == delayB) {
            mix(blurredCurrent, blur(frameAt(delayR), width, height, radius))
        } else {
            mixChannels(
                blurredCurrent,
                blur(frameAt(delayR), width, height, radius),
                blur(frameAt(delayG), width, height, radius),
                blur(frameAt(delayB), width, height, radius),
            )
        }
        val swapped = swapAll(out, settings.swap)
        return if (settings.glow) glow(swapped, width, height) else swapped
    }

    private fun frameAt(delay: Int): IntArray {
        if (delay <= 0) return frames.last()
        val index = frames.size - 1 - delay
        if (index <= 0) return frames.first()
        return frames.elementAt(index)
    }

    private fun blur(src: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius <= 0 || width <= 1 || height <= 1) return src
        if (scratchA.size != src.size) {
            scratchA = IntArray(src.size)
            scratchB = IntArray(src.size)
        }
        boxHorizontal(src, scratchA, width, height, radius)
        boxVertical(scratchA, scratchB, width, height, radius)
        return scratchB.copyOf()
    }
}

fun mix(current: IntArray, delayed: IntArray): IntArray {
    val out = IntArray(current.size)
    val n = minOf(current.size, delayed.size)
    for (i in 0 until n) {
        val c = current[i]
        val d = delayed[i]
        val b = ((c and 0xFF) + 255 - (d and 0xFF)) shr 1
        val g = (((c shr 8) and 0xFF) + 255 - ((d shr 8) and 0xFF)) shr 1
        val r = (((c shr 16) and 0xFF) + 255 - ((d shr 16) and 0xFF)) shr 1
        out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return out
}

private fun mixChannels(current: IntArray, red: IntArray, green: IntArray, blue: IntArray): IntArray {
    val out = IntArray(current.size)
    for (i in current.indices) {
        val c = current[i]
        val r = (((c shr 16) and 0xFF) + 255 - ((red[i] shr 16) and 0xFF)) shr 1
        val g = (((c shr 8) and 0xFF) + 255 - ((green[i] shr 8) and 0xFF)) shr 1
        val b = ((c and 0xFF) + 255 - (blue[i] and 0xFF)) shr 1
        out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return out
}

private fun swapAll(src: IntArray, mode: String): IntArray {
    if (mode == "none") return src
    val out = IntArray(src.size)
    for (i in src.indices) out[i] = swapPixel(src[i], mode)
    return out
}

fun swapPixel(color: Int, mode: String): Int {
    val b = color and 0xFF
    val g = (color shr 8) and 0xFF
    val r = (color shr 16) and 0xFF
    val (nr, ng, nb) = when (mode) {
        "rb" -> Triple(b, g, r)
        "rg" -> Triple(g, r, b)
        "gb" -> Triple(r, b, g)
        "cycle_fwd" -> Triple(g, b, r)
        "cycle_back" -> Triple(b, r, g)
        else -> return color
    }
    return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
}

private fun glow(src: IntArray, width: Int, height: Int): IntArray {
    if (width < 2 || height < 2) return src
    val radius = (width / 90).coerceIn(2, 12)
    val soft = IntArray(src.size)
    val tmp = IntArray(src.size)
    boxHorizontal(src, tmp, width, height, radius)
    boxVertical(tmp, soft, width, height, radius)
    val out = IntArray(src.size)
    for (i in src.indices) {
        val c = src[i]
        val s = soft[i]
        out[i] = (0xFF shl 24) or
            (glowChannel((c shr 16) and 0xFF, (s shr 16) and 0xFF) shl 16) or
            (glowChannel((c shr 8) and 0xFF, (s shr 8) and 0xFF) shl 8) or
            glowChannel(c and 0xFF, s and 0xFF)
    }
    return out
}

private fun glowChannel(value: Int, soft: Int): Int {
    val delta = value - 127
    val halo = soft - 127
    val boost = (kotlin.math.abs(delta) / 18).coerceAtMost(3)
    return (value + halo * boost / 2 + delta / 5).coerceIn(0, 255)
}

private fun boxHorizontal(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
    val div = radius * 2 + 1
    for (y in 0 until height) {
        val row = y * width
        var sumB = 0
        var sumG = 0
        var sumR = 0
        for (k in -radius..radius) {
            val c = src[row + k.coerceIn(0, width - 1)]
            sumB += c and 0xFF
            sumG += (c shr 8) and 0xFF
            sumR += (c shr 16) and 0xFF
        }
        for (x in 0 until width) {
            dst[row + x] = (0xFF shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
            val remove = src[row + (x - radius).coerceIn(0, width - 1)]
            val add = src[row + (x + radius + 1).coerceIn(0, width - 1)]
            sumB += (add and 0xFF) - (remove and 0xFF)
            sumG += ((add shr 8) and 0xFF) - ((remove shr 8) and 0xFF)
            sumR += ((add shr 16) and 0xFF) - ((remove shr 16) and 0xFF)
        }
    }
}

private fun boxVertical(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
    val div = radius * 2 + 1
    for (x in 0 until width) {
        var sumB = 0
        var sumG = 0
        var sumR = 0
        for (k in -radius..radius) {
            val c = src[k.coerceIn(0, height - 1) * width + x]
            sumB += c and 0xFF
            sumG += (c shr 8) and 0xFF
            sumR += (c shr 16) and 0xFF
        }
        for (y in 0 until height) {
            dst[y * width + x] = (0xFF shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
            val remove = src[(y - radius).coerceIn(0, height - 1) * width + x]
            val add = src[(y + radius + 1).coerceIn(0, height - 1) * width + x]
            sumB += (add and 0xFF) - (remove and 0xFF)
            sumG += ((add shr 8) and 0xFF) - ((remove shr 8) and 0xFF)
            sumR += ((add shr 16) and 0xFF) - ((remove shr 16) and 0xFF)
        }
    }
}
