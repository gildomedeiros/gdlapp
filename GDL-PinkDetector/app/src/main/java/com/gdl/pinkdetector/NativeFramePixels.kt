package com.gdl.pinkdetector

import android.graphics.Bitmap

/** Thread-local reusable native pixels; no shared mutable detector buffers. */
object NativeFramePixels {
    private class Cache {
        var source = java.lang.ref.WeakReference<Bitmap>(null)
        var generation = -1
        var pixels = IntArray(0)
    }
    private val cache = ThreadLocal.withInitial { Cache() }
    fun read(bitmap: Bitmap): IntArray {
        val c = checkNotNull(cache.get())
        if (c.source.get() !== bitmap || c.generation != bitmap.generationId) {
            if (c.pixels.size != bitmap.width * bitmap.height)
                c.pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(c.pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            c.source = java.lang.ref.WeakReference(bitmap)
            c.generation = bitmap.generationId
        }
        return c.pixels
    }
}
