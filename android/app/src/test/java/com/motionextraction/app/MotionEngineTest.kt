package com.motionextraction.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionEngineTest {
    @Test
    fun staticFramesCancelToGray() {
        val history = MotionHistory()
        val frame = IntArray(8) { 0xFF282828.toInt() }
        history.add(frame, 4, 2)
        history.add(frame.copyOf(), 4, 2)
        val out = history.render(MotionSettings(delay = 1, span = 30, blur = 4, glow = true, swap = "rb"))
        assertTrue(out.all { it == 0xFF7F7F7F.toInt() })
    }

    @Test
    fun waitsUntilTheDelayExists() {
        val history = MotionHistory()
        val frame = IntArray(4) { 0xFF112233.toInt() }
        history.add(frame, 2, 2)
        val out = history.render(MotionSettings(delay = 1))
        assertEquals(0xFF112233.toInt(), out[0])
    }

    @Test
    fun redChannelCanMoveAlone() {
        val history = MotionHistory()
        history.add(IntArray(4), 2, 2)
        history.add(IntArray(4) { 0xFFFF0000.toInt() }, 2, 2)
        val out = history.render(MotionSettings(delay = 0, offR = 1, offG = 0, offB = 0, span = 30))
        assertEquals(255, (out[0] shr 16) and 0xFF)
        assertEquals(127, (out[0] shr 8) and 0xFF)
        assertEquals(127, out[0] and 0xFF)
    }

    @Test
    fun swapsRedAndBlue() {
        val swapped = swapPixel(0xFFFF0000.toInt(), "rb")
        assertEquals(0xFF0000FF.toInt(), swapped)
    }

    @Test
    fun blurSpreadsASinglePixel() {
        val history = MotionHistory()
        val past = IntArray(31 * 31)
        val now = IntArray(31 * 31)
        now[15 * 31 + 15] = 0xFFFFFFFF.toInt()
        history.add(past, 31, 31)
        history.add(now, 31, 31)
        val sharp = history.render(MotionSettings(delay = 1, blur = 0))
        val soft = history.render(MotionSettings(delay = 1, blur = 12, span = 30))
        assertEquals(127, sharp[15 * 31 + 14] and 0xFF)
        assertTrue((soft[15 * 31 + 14] and 0xFF) != 127)
    }
}
