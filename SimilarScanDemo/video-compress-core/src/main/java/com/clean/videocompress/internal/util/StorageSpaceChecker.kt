package com.clean.videocompress.internal.util

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.clean.videocompress.api.model.CompressVideoAsset
import com.clean.videocompress.api.model.VideoCompressError
import com.clean.videocompress.api.model.VideoCompressStorageLocation
import java.io.File

/**
 * 压缩前磁盘空间检查。
 *
 * 压缩会先生成 app cache 临时文件，再复制/发布到系统媒体库。
 * 因此这里同时检查临时目录和媒体库所在存储，避免长时间编码后才发现无法保存。
 */
internal object StorageSpaceChecker {
    fun checkBeforeCompress(
        context: Context,
        estimatedOutputBytes: Long,
        sourceSizeBytes: Long
    ): VideoCompressError.InsufficientStorage? {
        val requiredBytes = requiredBytes(estimatedOutputBytes, sourceSizeBytes)
        val cacheAvailable = availableBytes(context.cacheDir)
        if (cacheAvailable != null && cacheAvailable < requiredBytes) {
            return VideoCompressError.InsufficientStorage(
                location = VideoCompressStorageLocation.TEMP_CACHE,
                requiredBytes = requiredBytes,
                availableBytes = cacheAvailable
            )
        }

        val mediaAvailable = availableBytes(mediaLibraryStatDir(context))
        if (mediaAvailable != null && mediaAvailable < requiredBytes) {
            return VideoCompressError.InsufficientStorage(
                location = VideoCompressStorageLocation.MEDIA_LIBRARY,
                requiredBytes = requiredBytes,
                availableBytes = mediaAvailable
            )
        }
        return null
    }

    fun estimateOutputBytes(asset: CompressVideoAsset, targetBitrate: Int): Long {
        if (asset.durationMs <= 0L || targetBitrate <= 0) return asset.sizeBytes
        val videoBytes = targetBitrate.toLong() * asset.durationMs / 8_000L
        val audioReserveBytes = DEFAULT_AUDIO_BITRATE.toLong() * asset.durationMs / 8_000L
        return (videoBytes + audioReserveBytes).coerceAtLeast(MIN_OUTPUT_BYTES)
    }

    private fun requiredBytes(estimatedOutputBytes: Long, sourceSizeBytes: Long): Long {
        val estimated = when {
            estimatedOutputBytes > 0L -> estimatedOutputBytes
            sourceSizeBytes > 0L -> sourceSizeBytes
            else -> MIN_OUTPUT_BYTES
        }
        return estimated + SAFETY_MARGIN_BYTES
    }

    private fun availableBytes(file: File?): Long? {
        val target = existingStatTarget(file) ?: return null
        return try {
            StatFs(target.absolutePath).availableBytes
        } catch (_: Throwable) {
            null
        }
    }

    private fun existingStatTarget(file: File?): File? {
        var current = file
        while (current != null && !current.exists()) {
            current = current.parentFile
        }
        return current
    }

    @Suppress("DEPRECATION")
    private fun mediaLibraryStatDir(context: Context): File {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        return moviesDir ?: context.cacheDir
    }

    private const val DEFAULT_AUDIO_BITRATE = 192_000
    private const val MIN_OUTPUT_BYTES = 1L * 1024L * 1024L
    private const val SAFETY_MARGIN_BYTES = 32L * 1024L * 1024L
}
