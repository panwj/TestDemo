package com.clean.videocompress.internal.policy

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import com.clean.videocompress.api.model.CompressVideoAsset

/**
 * 压缩前的视频格式探测器。
 *
 * 只读取 MediaFormat 元数据，不做解码，成本较低。当前用于识别 HEVC 与 HDR：
 * - HEVC：允许 Media3 转 H.264。
 * - HDR：默认拦截，避免输出偏色、灰蒙或过曝。
 */
internal class VideoFormatInspector(private val context: Context) {
    fun inspect(asset: CompressVideoAsset): VideoFormatProfile {
        val extractor = MediaExtractor()
        return try {
            context.contentResolver.openFileDescriptor(asset.uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: return VideoFormatProfile()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    return VideoFormatProfile(
                        videoMime = mime,
                        isHevc = mime.equals("video/hevc", ignoreCase = true) ||
                            mime.equals("video/h265", ignoreCase = true),
                        isHdr = isHdr(format)
                    )
                }
            }
            VideoFormatProfile()
        } catch (_: Throwable) {
            VideoFormatProfile()
        } finally {
            extractor.release()
        }
    }

    private fun isHdr(format: MediaFormat): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
        } else {
            0
        }
        val standard = if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
            format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
        } else {
            0
        }
        return transfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
            transfer == MediaFormat.COLOR_TRANSFER_HLG ||
            standard == MediaFormat.COLOR_STANDARD_BT2020
    }
}

internal data class VideoFormatProfile(
    val videoMime: String = "",
    val isHevc: Boolean = false,
    val isHdr: Boolean = false
)
