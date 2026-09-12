package com.gdl.pinkdetector

/**
 * Shared Mini 3 Pro / DJI Fly landscape search area.
 *
 * Measurements from the captured 2048 px-wide frames put the central camera
 * view at approximately x=219..1898. Its full height remains searchable. The
 * map is expected to stay collapsed inside the ignored left rail.
 */
object DjiCameraSearchArea {
    private const val LEFT_CAMERA_EDGE = 0.107f
    private const val RIGHT_CAMERA_EDGE = 0.927f

    fun contains(x: Int, width: Int): Boolean = contains(x.toFloat(), width)

    fun contains(x: Float, width: Int): Boolean {
        val nx = x / width.coerceAtLeast(1)
        return nx >= LEFT_CAMERA_EDGE && nx <= RIGHT_CAMERA_EDGE
    }
}
