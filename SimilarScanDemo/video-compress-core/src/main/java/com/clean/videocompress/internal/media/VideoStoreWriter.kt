package com.clean.videocompress.internal.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 压缩结果保存器。
 *
 * 压缩引擎只负责生成 app cache 下的临时 MP4，最终发布到系统媒体库统一由这里完成。
 */
internal class VideoStoreWriter(private val context: Context) {
    /**
     * 保存压缩结果到系统媒体库。
     *
     * Android 10+ 使用分区存储；Android 9 及以下写入公共 Movies 目录并触发媒体扫描。
     */
    fun saveToMediaStore(sourceFile: File, displayName: String): SavedVideo {
        if (Build.VERSION.SDK_INT < 29) {
            return saveLegacy(sourceFile, displayName)
        }
        return saveScopedStorage(sourceFile, displayName)
    }

    /**
     * Android 10+ 保存路径。
     *
     * 先以 IS_PENDING=1 写入，写完后再发布，避免系统相册看到半成品文件。
     */
    private fun saveScopedStorage(sourceFile: File, displayName: String): SavedVideo {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.SIZE, sourceFile.length())
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to create video item in MediaStore")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Failed to open MediaStore output stream")
            val published = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
                put(MediaStore.Video.Media.SIZE, sourceFile.length())
            }
            resolver.update(uri, published, null, null)
            return SavedVideo(uri, sourceFile.length())
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    /**
     * Android 9 及以下保存路径。
     *
     * 旧系统没有分区存储，需要写入真实文件路径，并通知媒体扫描器刷新相册。
     */
    private fun saveLegacy(sourceFile: File, displayName: String): SavedVideo {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        if (!moviesDir.exists() && !moviesDir.mkdirs()) {
            throw IllegalStateException("Failed to create Movies directory: ${moviesDir.absolutePath}")
        }
        val targetFile = uniqueFile(moviesDir, displayName)
        try {
            sourceFile.inputStream().use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, targetFile.name)
                put(MediaStore.Video.Media.TITLE, targetFile.nameWithoutExtension)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.SIZE, targetFile.length())
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
                put(MediaStore.Video.Media.DATE_MODIFIED, targetFile.lastModified() / 1000L)
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, targetFile.absolutePath)
            }
            val insertedUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            val scannedUri = scanLegacyFile(targetFile)
            return SavedVideo(insertedUri ?: scannedUri ?: Uri.fromFile(targetFile), targetFile.length())
        } catch (error: Throwable) {
            targetFile.delete()
            throw error
        }
    }

    /**
     * 生成不冲突的输出文件名。
     */
    private fun uniqueFile(directory: File, displayName: String): File {
        val safeName = displayName.ifBlank { "compressed_${System.currentTimeMillis()}.mp4" }
        val baseName = safeName.substringBeforeLast('.', safeName)
        val extension = safeName.substringAfterLast('.', "mp4")
        var candidate = File(directory, safeName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "${baseName}_$index.$extension")
            index++
        }
        return candidate
    }

    /**
     * 旧系统写入真实文件后，需要通过 MediaScannerConnection 让系统相册尽快可见。
     */
    private fun scanLegacyFile(file: File): Uri? {
        var resultUri: Uri? = null
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("video/mp4")
        ) { _, uri ->
            resultUri = uri
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return resultUri
    }
}

/**
 * 保存到系统媒体库后的结果。
 */
internal data class SavedVideo(
    val uri: Uri,
    val sizeBytes: Long
)
