package com.temporalfpt.app

fun interface RowProgress {
    fun onRow(done: Int, total: Int): Boolean
}

object NativeFilter {
    init {
        System.loadLibrary("temporalfpt")
    }

    @JvmStatic external fun selfTest(): Int

    @JvmStatic external fun create(width: Int, height: Int, capacity: Int): Long

    @JvmStatic external fun push(handle: Long, pixels: IntArray)

    @JvmStatic external fun process(
        handle: Long,
        fps: Float,
        mode: Int,
        cutoff: Float,
        low: Float,
        high: Float,
        gain: Float,
        progress: RowProgress,
    ): Int

    @JvmStatic external fun copyFrame(handle: Long, index: Int, dest: IntArray)

    @JvmStatic external fun destroy(handle: Long)
}
