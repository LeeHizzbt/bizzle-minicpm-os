package com.bizzle.minicpm.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID

/** Copies a picked/captured/shared image into app storage as a right-way-up JPEG (max 1792 px), so the model can read it offline. */
object ImageUtil {
    fun import(ctx: Context, uri: Uri): String? = runCatching {
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        importBytes(ctx, bytes)
    }.getOrNull()

    fun importBytes(ctx: Context, bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1792 || bounds.outHeight / (sample * 2) >= 1792) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rot = runCatching {
            when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
            }
        }.getOrDefault(0f)
        val maxSide = maxOf(bmp.width, bmp.height)
        if (maxSide > 1792 || rot != 0f) {
            val scale = if (maxSide > 1792) 1792f / maxSide else 1f
            val m = Matrix().apply { postScale(scale, scale); postRotate(rot) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val dir = File(ctx.filesDir, "images").apply { mkdirs() }
        val out = File(dir, UUID.randomUUID().toString() + ".jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return out.absolutePath
    }

    fun newCameraFile(ctx: Context): File = File(File(ctx.cacheDir, "camera").apply { mkdirs() }, "shot-${System.currentTimeMillis()}.jpg")
}
