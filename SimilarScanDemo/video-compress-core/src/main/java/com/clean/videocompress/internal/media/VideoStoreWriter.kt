package com.clean.videocompress.internal.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

internal class VideoStoreWriter(private val context: Context) {
    fun saveToMediaStore(sourceFile: File, displayName: String): SavedVideo {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.SIZE, sourceFile.length())
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to create video item in MediaStore")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Failed to open MediaStore output stream")
            if (Build.VERSION.SDK_INT >= 29) {
                val published = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                    put(MediaStore.Video.Media.SIZE, sourceFile.length())
                }
                resolver.update(uri, published, null, null)
            }
            return SavedVideo(uri, sourceFile.length())
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}

internal data class SavedVideo(
    val uri: Uri,
    val sizeBytes: Long
)
