package com.gdl.pinkdetector

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Separate bounded logger: neither storage nor queue pressure can wait on CV. */
class DiagnosticLog(private val context: Context) {
    private val dropped = AtomicInteger()
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(256), ThreadPoolExecutor.AbortPolicy())
    private val destinations = HashMap<String, Uri?>() // logger thread only
    fun record(run: String, frame: Int, event: String) {
        val line = "wall_ms=${System.currentTimeMillis()} elapsed_ms=${SystemClock.elapsedRealtime()} frame=$frame $event\n"
        android.util.Log.i("GDL_DIAG", line)
        try {
            executor.execute {
                try {
                    val missed = dropped.getAndSet(0)
                    val text = (if (missed > 0) "LOG_QUEUE_DROPPED=$missed\n" else "") + line
                    // Always keep an app-local copy, flushed for every event.
                    val directory = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics/$run")
                    directory.mkdirs()
                    java.io.File(directory, "diagnostic.log").appendText(text)
                    val uri = if (destinations.containsKey(run)) destinations[run] else {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, "diagnostic.log")
                            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/GDL/$run")
                        }
                        val created = try { context.contentResolver.insert(
                            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) }
                            catch (_: Exception) { null }
                        destinations[run] = created
                        created
                    }
                    if (uri != null) context.contentResolver.openOutputStream(uri, "wa")?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GDL_DIAG", "Log write failed", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { dropped.incrementAndGet() }
    }
    fun close() { executor.shutdown() }
}
