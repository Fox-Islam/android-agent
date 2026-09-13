package com.foxislam.androidagent.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

data class SharedImage(val base64: String, val path: String)

/**
 * Turns a shared image into something the agent can be asked about.
 *
 * Scaled hard on the way in: a modern phone screenshot is several megapixels, and at full
 * size it costs more in image tokens than the entire rest of the conversation
 */
object Images {

    fun import(context: Context, uri: Uri): SharedImage? = runCatching {
        val bitmap = context.contentResolver.openInputStream(uri).use(BitmapFactory::decodeStream)
            ?: return null

        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest <= MAX_EDGE) bitmap else {
            val ratio = MAX_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
        }

        val bytes = ByteArrayOutputStream()
            .also { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            .toByteArray()

        val dir = File(context.filesDir, "shared").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg").apply { writeBytes(bytes) }

        SharedImage(Base64.encodeToString(bytes, Base64.NO_WRAP), file.absolutePath)
    }.getOrElse {
        Log.w(TAG, "could not read the shared image", it)
        null
    }

    private const val MAX_EDGE = 1100
    private const val QUALITY = 70
    private const val TAG = "Images"
}
